"""Application service layer for database-backed synthetic data generation."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import json
from pathlib import Path
from typing import Any

import pandas as pd
from fastapi import HTTPException, status

from app.config import Settings, get_settings
from app.model_store import load_synthesizer, model_exists, save_synthesizer
from app.schemas import GenerateResponse, StatusResponse, TrainResponse
from app.trainer import build_metadata, load_tables_from_db, sample_synthetic_data, train_synthesizer


@dataclass
class SyntheticDataService:
    """Stateful wrapper around the active synthetic data model."""

    settings: Settings
    synthesizer: Any = None
    model_type: str | None = None
    schema_name: str | None = None
    base_table: str | None = None
    trained_tables: list[str] | None = None
    trained_columns: dict[str, list[str]] | None = None
    row_counts: dict[str, int] | None = None
    primary_keys: dict[str, str | None] | None = None
    relationships: list[dict[str, str]] | None = None

    @property
    def model_path(self) -> str:
        return self.settings.model_path

    def _metadata_path(self) -> Path:
        return _metadata_file_for(self.settings.resolve_path(self.model_path))

    def startup_load_model_if_available(self) -> None:
        resolved_model_path = str(self.settings.resolve_path(self.model_path))
        if not model_exists(resolved_model_path):
            return

        self.synthesizer = load_synthesizer(resolved_model_path)
        metadata_path = self._metadata_path()
        if metadata_path.is_file():
            payload = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.model_type = payload.get("model_type")
            self.schema_name = payload.get("schema_name")
            self.base_table = payload.get("base_table")
            self.trained_tables = payload.get("trained_tables")
            self.trained_columns = payload.get("trained_columns")
            self.row_counts = payload.get("row_counts")
            self.primary_keys = payload.get("primary_keys")
            self.relationships = payload.get("relationships")
        elif self.model_type is None:
            self.model_type = _infer_model_type(self.synthesizer)

    def train(
        self,
        table_names: list[str],
        schema_name: str | None,
        base_table: str | None,
        save_model: bool,
    ) -> TrainResponse:
        loaded = load_tables_from_db(
            database_url=self.settings.database_url,
            table_names=table_names,
            schema_name=schema_name,
        )
        model_type, metadata = build_metadata(loaded)
        chosen_base_table = base_table or table_names[0]
        if chosen_base_table not in loaded.tables:
            raise ValueError("Base table must be included in the selected training tables.")

        synthesizer = train_synthesizer(model_type=model_type, loaded=loaded, metadata=metadata)

        self.synthesizer = synthesizer
        self.model_type = model_type
        self.schema_name = schema_name
        self.base_table = chosen_base_table
        self.trained_tables = list(loaded.tables.keys())
        self.trained_columns = loaded.columns
        self.row_counts = loaded.row_counts
        self.primary_keys = loaded.primary_keys
        self.relationships = loaded.relationships

        if save_model:
            save_synthesizer(synthesizer, str(self.settings.resolve_path(self.model_path)))
            self._metadata_path().write_text(
                json.dumps(
                    {
                        "model_type": self.model_type,
                        "schema_name": self.schema_name,
                        "base_table": self.base_table,
                        "trained_tables": self.trained_tables,
                        "trained_columns": self.trained_columns,
                        "row_counts": self.row_counts,
                        "primary_keys": self.primary_keys,
                        "relationships": self.relationships,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )

        return TrainResponse(
            status="trained",
            model_type=model_type,
            schema_name=schema_name,
            base_table=chosen_base_table,
            tables=self.trained_tables,
            columns=self.trained_columns,
            row_counts=self.row_counts,
            relationships=self.relationships,
            model_saved=save_model,
            message=(
                f"Trained a {model_type.replace('_', ' ')} model on "
                f"{len(self.trained_tables)} table(s)."
            ),
        )

    def generate(self, num_rows: int) -> GenerateResponse:
        if self.synthesizer is None or self.model_type is None or self.base_table is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="No trained model is available. Train or load a model first.",
            )

        base_training_rows = (self.row_counts or {}).get(self.base_table)
        if not base_training_rows:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="The loaded model is missing base table training metadata.",
            )

        sampled = sample_synthetic_data(
            synthesizer=self.synthesizer,
            model_type=self.model_type,
            base_table=self.base_table,
            requested_rows=num_rows,
            base_table_training_rows=base_training_rows,
        )
        sampled = _apply_auto_increment_ids(
            sampled,
            self.primary_keys or {},
            self.relationships or [],
        )
        sampled = _apply_consistency_rules(sampled)

        payload = {
            table_name: _dataframe_to_records(df)
            for table_name, df in sampled.items()
        }

        return GenerateResponse(
            status="ok",
            model_type=self.model_type,
            base_table=self.base_table,
            num_rows=num_rows,
            tables=list(payload.keys()),
            data=payload,
        )

    def get_status(self) -> StatusResponse:
        return StatusResponse(
            status="ok",
            model_loaded=self.synthesizer is not None,
            model_type=self.model_type,
            schema_name=self.schema_name,
            base_table=self.base_table,
            trained_tables=self.trained_tables,
            trained_columns=self.trained_columns,
            row_counts=self.row_counts,
            relationships=self.relationships,
            model_path=str(self.settings.resolve_path(self.model_path)),
        )


def create_service() -> SyntheticDataService:
    return SyntheticDataService(settings=get_settings())


def _infer_model_type(synthesizer: Any) -> str:
    class_name = synthesizer.__class__.__name__.lower()
    if "hma" in class_name:
        return "multi_table"
    if "ctgan" in class_name:
        return "single_table"
    return "unknown"


def _dataframe_to_records(df: pd.DataFrame) -> list[dict[str, Any]]:
    normalized = df.where(pd.notnull(df), None)
    records = normalized.to_dict(orient="records")
    for record in records:
        for key, value in record.items():
            if value is None:
                continue
            if hasattr(value, "isoformat"):
                record[key] = value.isoformat()
            elif isinstance(value, Decimal):
                record[key] = float(value)
            elif isinstance(value, (int, float, str, bool)):
                continue
            else:
                record[key] = str(value)
    return records


def _apply_auto_increment_ids(
    sampled: dict[str, pd.DataFrame],
    primary_keys: dict[str, str | None],
    relationships: list[dict[str, str]],
) -> dict[str, pd.DataFrame]:
    adjusted = {table_name: df.copy() for table_name, df in sampled.items()}
    id_maps: dict[str, dict[Any, int]] = {}

    for table_name, df in adjusted.items():
        primary_key = primary_keys.get(table_name)
        if not primary_key or primary_key not in df.columns:
            continue
        if primary_key.lower() != "id":
            continue

        original_values = df[primary_key].tolist()
        new_values = list(range(1, len(df) + 1))
        df[primary_key] = new_values
        id_maps[table_name] = {
            original: new_value
            for original, new_value in zip(original_values, new_values)
        }

    for relationship in relationships:
        parent_table = relationship["parent_table"]
        child_table = relationship["child_table"]
        parent_key = relationship["parent_key"]
        child_key = relationship["child_key"]

        if parent_table not in adjusted or child_table not in adjusted:
            continue
        if parent_key.lower() != "id":
            continue
        if child_key not in adjusted[child_table].columns:
            continue

        parent_map = id_maps.get(parent_table)
        if not parent_map:
            continue

        adjusted[child_table][child_key] = adjusted[child_table][child_key].map(
            lambda value: parent_map.get(value, value)
        )

    return adjusted


def _metadata_file_for(path: Path) -> Path:
    return path.with_suffix(path.suffix + ".meta.json")


def _apply_consistency_rules(sampled: dict[str, pd.DataFrame]) -> dict[str, pd.DataFrame]:
    adjusted = {table_name: df.copy() for table_name, df in sampled.items()}

    for table_name, df in adjusted.items():
        lower_columns = {column.lower(): column for column in df.columns}

        quantity_col = lower_columns.get("quantity")
        unit_price_col = lower_columns.get("unit_price")
        line_total_col = lower_columns.get("line_total")
        if quantity_col and unit_price_col and line_total_col:
            df[line_total_col] = df.apply(
                lambda row: _rounded_amount(row[quantity_col]) * _rounded_amount(row[unit_price_col]),
                axis=1,
            )

        subtotal_col = lower_columns.get("subtotal_amount")
        shipping_col = lower_columns.get("shipping_amount")
        total_col = lower_columns.get("total_amount")
        if subtotal_col and shipping_col and total_col:
            df[total_col] = df.apply(
                lambda row: _rounded_amount(row[subtotal_col]) + _rounded_amount(row[shipping_col]),
                axis=1,
            )

    order_items_table = _find_table(sampled=adjusted, candidates={"order_items", "orderitems"})
    orders_table = _find_table(sampled=adjusted, candidates={"orders"})
    if order_items_table and orders_table:
        order_items_df = adjusted[order_items_table]
        orders_df = adjusted[orders_table]
        order_id_col = _find_column(order_items_df, "order_id")
        line_total_col = _find_column(order_items_df, "line_total")
        order_pk_col = _find_column(orders_df, "id")
        subtotal_col = _find_column(orders_df, "subtotal_amount")
        shipping_col = _find_column(orders_df, "shipping_amount")
        total_col = _find_column(orders_df, "total_amount")

        if order_id_col and line_total_col and order_pk_col and subtotal_col:
            order_totals = (
                order_items_df.groupby(order_id_col)[line_total_col]
                .sum()
                .to_dict()
            )
            orders_df[subtotal_col] = orders_df[order_pk_col].map(
                lambda value: order_totals.get(value, Decimal("0.00"))
            )
            if shipping_col and total_col:
                orders_df[total_col] = orders_df.apply(
                    lambda row: _rounded_amount(row[subtotal_col]) + _rounded_amount(row[shipping_col]),
                    axis=1,
                )

    return adjusted


def _rounded_amount(value: Any) -> Decimal:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return Decimal("0.00")
    try:
        decimal_value = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return Decimal("0.00")
    return decimal_value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _find_table(sampled: dict[str, pd.DataFrame], candidates: set[str]) -> str | None:
    for table_name in sampled:
        if table_name.lower() in candidates:
            return table_name
    return None


def _find_column(df: pd.DataFrame, column_name: str) -> str | None:
    for actual_column in df.columns:
        if actual_column.lower() == column_name.lower():
            return actual_column
    return None
