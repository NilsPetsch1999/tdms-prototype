"""Database loading and synthesizer training helpers."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pandas as pd
from sqlalchemy import create_engine, inspect

from app.metadata_utils import build_multi_table_metadata, build_single_table_metadata


@dataclass
class LoadedTables:
    """Loaded training inputs plus database-derived relationship metadata."""

    tables: dict[str, pd.DataFrame]
    row_counts: dict[str, int]
    columns: dict[str, list[str]]
    primary_keys: dict[str, str | None]
    relationships: list[dict[str, str]]


def load_tables_from_db(
    database_url: str,
    table_names: list[str],
    schema_name: str | None = None,
) -> LoadedTables:
    """Load selected tables and their DB relationship metadata."""

    engine = create_engine(database_url)
    inspector = inspect(engine)
    tables: dict[str, pd.DataFrame] = {}
    row_counts: dict[str, int] = {}
    columns: dict[str, list[str]] = {}
    primary_keys: dict[str, str | None] = {}
    relationships: list[dict[str, str]] = []
    selected = set(table_names)

    try:
        available_tables = set(inspector.get_table_names(schema=schema_name))
        missing = [table for table in table_names if table not in available_tables]
        if missing:
            raise ValueError(
                f"Tables not found in database"
                + (f" schema '{schema_name}'" if schema_name else "")
                + f": {', '.join(missing)}"
            )

        for table_name in table_names:
            df = pd.read_sql_table(table_name, con=engine, schema=schema_name)
            if df.empty:
                raise ValueError(f"Table '{table_name}' is empty and cannot be used for training.")
            if len(df) < 10:
                raise ValueError(
                    f"Table '{table_name}' must contain at least 10 rows for model training."
                )

            tables[table_name] = _normalize_dataframe(df)
            row_counts[table_name] = len(df)
            columns[table_name] = [str(column) for column in df.columns.tolist()]

            pk_constraint = inspector.get_pk_constraint(table_name, schema=schema_name) or {}
            constrained_columns = pk_constraint.get("constrained_columns") or []
            primary_keys[table_name] = constrained_columns[0] if len(constrained_columns) == 1 else None

        for child_table in table_names:
            for foreign_key in inspector.get_foreign_keys(child_table, schema=schema_name):
                parent_table = foreign_key.get("referred_table")
                constrained_columns = foreign_key.get("constrained_columns") or []
                referred_columns = foreign_key.get("referred_columns") or []

                if parent_table not in selected:
                    continue
                if len(constrained_columns) != 1 or len(referred_columns) != 1:
                    continue

                relationships.append(
                    {
                        "parent_table": parent_table,
                        "child_table": child_table,
                        "parent_key": referred_columns[0],
                        "child_key": constrained_columns[0],
                    }
                )
    finally:
        engine.dispose()

    return LoadedTables(
        tables=tables,
        row_counts=row_counts,
        columns=columns,
        primary_keys=primary_keys,
        relationships=_deduplicate_relationships(relationships),
    )


def build_metadata(loaded: LoadedTables) -> tuple[str, Any]:
    """Build the appropriate SDV metadata for one or many tables."""

    if len(loaded.tables) == 1:
        table_name = next(iter(loaded.tables))
        metadata = build_single_table_metadata(
            loaded.tables[table_name],
            primary_key=loaded.primary_keys.get(table_name),
        )
        return "single_table", metadata

    metadata = build_multi_table_metadata(
        loaded.tables,
        loaded.primary_keys,
        loaded.relationships,
    )
    return "multi_table", metadata


def train_synthesizer(model_type: str, loaded: LoadedTables, metadata: Any) -> Any:
    """Train either a CTGAN or HMA synthesizer, depending on selection size."""

    if model_type == "single_table":
        try:
            from sdv.single_table import CTGANSynthesizer
        except ImportError as exc:
            raise RuntimeError("SDV single-table support is not available.") from exc

        table_name = next(iter(loaded.tables))
        synthesizer = CTGANSynthesizer(metadata)
        synthesizer.fit(loaded.tables[table_name])
        return synthesizer

    try:
        from sdv.multi_table import HMASynthesizer
    except ImportError as exc:
        raise RuntimeError("SDV multi-table support is not available.") from exc

    synthesizer = HMASynthesizer(metadata)
    synthesizer.fit(loaded.tables)
    return synthesizer


def sample_synthetic_data(
    synthesizer: Any,
    model_type: str,
    base_table: str,
    requested_rows: int,
    base_table_training_rows: int,
) -> dict[str, pd.DataFrame]:
    """Generate synthetic data for either single-table or relational models."""

    if model_type == "single_table":
        sampled = synthesizer.sample(num_rows=requested_rows)
        return {base_table: sampled}

    scale = requested_rows / max(base_table_training_rows, 1)
    scale = max(scale, 0.01)
    sampled = synthesizer.sample(scale=scale)
    if not isinstance(sampled, dict):
        raise RuntimeError("Multi-table synthesizer returned an unexpected sample format.")
    return sampled


def _normalize_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    normalized = df.copy()
    for column_name in normalized.columns:
        if normalized[column_name].dtype != "object":
            continue

        parsed = pd.to_datetime(normalized[column_name], errors="coerce")
        if parsed.notna().sum() >= max(1, int(len(normalized) * 0.8)):
            normalized[column_name] = parsed

    return normalized


def _deduplicate_relationships(
    relationships: list[dict[str, str]],
) -> list[dict[str, str]]:
    seen: set[tuple[str, str, str, str]] = set()
    unique: list[dict[str, str]] = []
    for relationship in relationships:
        key = (
            relationship["parent_table"],
            relationship["child_table"],
            relationship["parent_key"],
            relationship["child_key"],
        )
        if key in seen:
            continue
        seen.add(key)
        unique.append(relationship)
    return unique
