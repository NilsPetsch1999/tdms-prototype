"""Application service layer for database-backed synthetic data generation."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import json
from pathlib import Path
import re
from typing import Any

import pandas as pd
from fastapi import HTTPException, status

from app.config import Settings, get_settings
from app.model_store import load_synthesizer, model_exists, save_synthesizer
from app.schemas import GenerateResponse, StatusResponse, SyntheticColumnRule, TrainResponse
from app.trainer import build_metadata, load_tables_from_db, sample_synthetic_data, train_synthesizer

AGGREGATE_PATTERN = re.compile(
    r"^\s*(SUM|COUNT|AVG|MIN|MAX)\(\s*([a-zA-Z0-9_]+)\.([a-zA-Z0-9_]+)\s+BY\s+([a-zA-Z0-9_]+)\s*\)\s*$",
    re.IGNORECASE,
)


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

    def generate(
        self,
        num_rows: int,
        column_rules_by_table: dict[str, dict[str, SyntheticColumnRule]] | None = None,
    ) -> GenerateResponse:
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
        sampled = _apply_configured_rules(
            sampled,
            column_rules_by_table or {},
            self.primary_keys or {},
            self.relationships or [],
        )

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
            primary_keys=self.primary_keys,
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


def _apply_configured_rules(
    sampled: dict[str, pd.DataFrame],
    column_rules_by_table: dict[str, dict[str, SyntheticColumnRule]],
    primary_keys: dict[str, str | None],
    relationships: list[dict[str, str]],
) -> dict[str, pd.DataFrame]:
    adjusted = {table_name: df.copy() for table_name, df in sampled.items()}
    protected_columns = _protected_columns_by_table(primary_keys, relationships)

    # Apply fixed overrides first, then aggregates, then expressions.
    # This lets expressions depend on aggregate-derived parent values.
    for table_name, rules_for_table in column_rules_by_table.items():
        df = adjusted.get(table_name)
        if df is None or not rules_for_table:
            continue

        for column_name, rule in rules_for_table.items():
            if column_name not in df.columns:
                continue
            if column_name in protected_columns.get(table_name, set()):
                continue
            if rule.strategy == "FIXED":
                df[column_name] = df.apply(
                    lambda row: _coerce_rule_result(rule.config, row.get(column_name)),
                    axis=1,
                )

    adjusted = _apply_aggregate_rules(
        adjusted,
        column_rules_by_table,
        protected_columns,
        relationships,
    )

    for table_name, rules_for_table in column_rules_by_table.items():
        df = adjusted.get(table_name)
        if df is None or not rules_for_table:
            continue

        for column_name, rule in rules_for_table.items():
            if column_name not in df.columns:
                continue
            if column_name in protected_columns.get(table_name, set()):
                continue
            if rule.strategy == "EXPRESSION":
                df[column_name] = df.apply(
                    lambda row: _evaluate_expression_rule(
                        rule.config or "",
                        row,
                        row.get(column_name),
                        table_name,
                    ),
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

def _evaluate_expression_rule(
    expression: str,
    row: pd.Series,
    current_value: Any,
    table_name: str,
) -> Any:
    resolved = _resolve_numeric_expression(expression, row, table_name)
    value = _ArithmeticParser(resolved).parse()
    return _coerce_rule_result(value, current_value)


def _resolve_numeric_expression(expression: str, row: pd.Series, table_name: str) -> str:
    resolved = expression
    for column_name in row.index:
        replacement = _decimal_to_expression_value(row[column_name])
        plain_tokens = [
            "${" + str(column_name) + "}",
            "${" + table_name + "." + str(column_name) + "}",
            table_name + "." + str(column_name),
        ]
        for token in plain_tokens:
            if token in resolved:
                resolved = resolved.replace(token, replacement)
    return resolved.replace(" ", "")


def _decimal_to_expression_value(value: Any) -> str:
    decimal_value = _rounded_amount(value)
    return decimal_value.normalize().to_eng_string()


def _coerce_rule_result(value: Any, current_value: Any) -> Any:
    if value is None:
        return None

    if isinstance(current_value, Decimal):
        return _rounded_amount(value)
    if isinstance(current_value, bool):
        if isinstance(value, str):
            return value.strip().lower() in {"true", "1", "yes"}
        return bool(value)
    if isinstance(current_value, int) and not isinstance(current_value, bool):
        return int(_rounded_amount(value))
    if isinstance(current_value, float):
        return float(_rounded_amount(value))
    if hasattr(current_value, "isoformat"):
        return value
    return value if isinstance(value, str) else str(value)


class _ArithmeticParser:
    def __init__(self, expression: str):
        self.expression = expression
        self.index = 0

    def parse(self) -> Decimal:
        value = self._parse_expression()
        if self.index != len(self.expression):
            raise ValueError(f"Invalid arithmetic expression: {self.expression}")
        return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    def _parse_expression(self) -> Decimal:
        value = self._parse_term()
        while self.index < len(self.expression):
            operator = self.expression[self.index]
            if operator not in "+-":
                break
            self.index += 1
            next_value = self._parse_term()
            value = value + next_value if operator == "+" else value - next_value
        return value

    def _parse_term(self) -> Decimal:
        value = self._parse_factor()
        while self.index < len(self.expression):
            operator = self.expression[self.index]
            if operator not in "*/":
                break
            self.index += 1
            next_value = self._parse_factor()
            value = value * next_value if operator == "*" else value / next_value
        return value

    def _parse_factor(self) -> Decimal:
        if self.index >= len(self.expression):
            raise ValueError(f"Unexpected end of expression: {self.expression}")

        current = self.expression[self.index]
        if current == "(":
            self.index += 1
            value = self._parse_expression()
            self._expect(")")
            return value
        if current == "+":
            self.index += 1
            return self._parse_factor()
        if current == "-":
            self.index += 1
            return self._parse_factor() * Decimal("-1")

        start = self.index
        while self.index < len(self.expression) and (
            self.expression[self.index].isdigit() or self.expression[self.index] == "."
        ):
            self.index += 1

        if start == self.index:
            raise ValueError(f"Expected number in expression: {self.expression}")

        return Decimal(self.expression[start:self.index])

    def _expect(self, expected: str) -> None:
        if self.index >= len(self.expression) or self.expression[self.index] != expected:
            raise ValueError(f"Expected '{expected}' in expression: {self.expression}")
        self.index += 1


def _protected_columns_by_table(
    primary_keys: dict[str, str | None],
    relationships: list[dict[str, str]],
) -> dict[str, set[str]]:
    protected: dict[str, set[str]] = {}

    for table_name, primary_key in primary_keys.items():
        if primary_key:
            protected.setdefault(table_name, set()).add(primary_key)

    for relationship in relationships:
        protected.setdefault(relationship["child_table"], set()).add(relationship["child_key"])
        protected.setdefault(relationship["parent_table"], set()).add(relationship["parent_key"])

    return protected


def _apply_aggregate_rules(
    sampled: dict[str, pd.DataFrame],
    column_rules_by_table: dict[str, dict[str, SyntheticColumnRule]],
    protected_columns: dict[str, set[str]],
    relationships: list[dict[str, str]],
) -> dict[str, pd.DataFrame]:
    adjusted = {table_name: df.copy() for table_name, df in sampled.items()}

    for target_table, rules_for_table in column_rules_by_table.items():
        target_df = adjusted.get(target_table)
        if target_df is None or not rules_for_table:
            continue

        for target_column, rule in rules_for_table.items():
            if rule.strategy != "AGGREGATE":
                continue
            if target_column in protected_columns.get(target_table, set()):
                continue
            if target_column not in target_df.columns:
                continue

            aggregate = _parse_aggregate_rule(rule.config or "", target_table, target_column)
            child_df = adjusted.get(aggregate["child_table"])
            if child_df is None:
                continue

            relationship = _find_matching_relationship(
                relationships,
                target_table=target_table,
                child_table=aggregate["child_table"],
                group_by_column=aggregate["group_by_column"],
            )
            if relationship is None:
                raise ValueError(
                    f"No matching relationship found for aggregate rule on "
                    f"{target_table}.{target_column}"
                )

            parent_key = relationship["parent_key"]
            child_key = relationship["child_key"]
            if parent_key not in target_df.columns:
                continue
            if child_key not in child_df.columns:
                continue

            aggregated_values = _compute_aggregate(
                child_df=child_df,
                value_column=aggregate["value_column"],
                group_by_column=child_key,
                function_name=aggregate["function_name"],
            )
            target_df[target_column] = target_df[parent_key].map(
                lambda value: _coerce_rule_result(
                    aggregated_values.get(value, Decimal("0.00")),
                    target_df[target_column].iloc[0] if len(target_df) else None,
                )
            )

    return adjusted


def _parse_aggregate_rule(config: str, target_table: str, target_column: str) -> dict[str, str]:
    match = AGGREGATE_PATTERN.match(config)
    if not match:
        raise ValueError(
            f"Invalid aggregate rule for {target_table}.{target_column}. "
            f"Expected format like SUM(order_items.line_total BY order_id)."
        )
    return {
        "function_name": match.group(1).upper(),
        "child_table": match.group(2),
        "value_column": match.group(3),
        "group_by_column": match.group(4),
    }


def _find_matching_relationship(
    relationships: list[dict[str, str]],
    target_table: str,
    child_table: str,
    group_by_column: str,
) -> dict[str, str] | None:
    for relationship in relationships:
        if relationship["parent_table"] != target_table:
            continue
        if relationship["child_table"] != child_table:
            continue
        if relationship["child_key"] != group_by_column:
            continue
        return relationship
    return None


def _compute_aggregate(
    child_df: pd.DataFrame,
    value_column: str,
    group_by_column: str,
    function_name: str,
) -> dict[Any, Any]:
    if group_by_column not in child_df.columns:
        raise ValueError(f"Aggregate group-by column not found: {group_by_column}")
    if function_name != "COUNT" and value_column not in child_df.columns:
        raise ValueError(f"Aggregate value column not found: {value_column}")

    grouped = child_df.groupby(group_by_column, dropna=False)

    if function_name == "COUNT":
        return grouped.size().to_dict()

    if function_name == "SUM":
        return grouped[value_column].sum().to_dict()
    if function_name == "AVG":
        return grouped[value_column].mean().to_dict()
    if function_name == "MIN":
        return grouped[value_column].min().to_dict()
    if function_name == "MAX":
        return grouped[value_column].max().to_dict()

    raise ValueError(f"Unsupported aggregate function: {function_name}")
