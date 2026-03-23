"""Helpers for building SDV metadata from database-loaded dataframes."""

from __future__ import annotations

from typing import Any

import pandas as pd
from pandas.api.types import (
    is_bool_dtype,
    is_datetime64_any_dtype,
    is_float_dtype,
    is_integer_dtype,
)


def _infer_sdtype(series: pd.Series) -> str:
    if is_bool_dtype(series):
        return "boolean"
    if is_datetime64_any_dtype(series):
        return "datetime"
    if is_integer_dtype(series) or is_float_dtype(series):
        return "numerical"
    return "categorical"


def build_single_table_metadata(df: pd.DataFrame, primary_key: str | None = None) -> Any:
    """Create robust single-table SDV metadata."""

    try:
        from sdv.metadata import SingleTableMetadata
    except ImportError as exc:
        raise RuntimeError("SDV is required to build metadata.") from exc

    metadata = SingleTableMetadata()

    try:
        metadata.detect_from_dataframe(data=df)
    except Exception:
        columns: dict[str, dict[str, str]] = {
            column_name: {"sdtype": _infer_sdtype(df[column_name])}
            for column_name in df.columns
        }
        metadata = SingleTableMetadata.load_from_dict({"columns": columns})

    if primary_key and primary_key in df.columns:
        try:
            metadata.set_primary_key(primary_key)
        except Exception:
            pass

    return metadata


def build_multi_table_metadata(
    tables: dict[str, pd.DataFrame],
    primary_keys: dict[str, str | None],
    relationships: list[dict[str, str]],
) -> Any:
    """Create multi-table metadata and wire in detected DB relationships.

    We intentionally avoid SDV's cross-table relationship auto-detection here.
    In schemas where many tables use a generic ``id`` primary key, SDV may infer
    incorrect ``id -> id`` relationships. We only add relationships that come
    from the inspected database foreign keys.
    """

    try:
        from sdv.metadata import MultiTableMetadata
    except ImportError as exc:
        raise RuntimeError("SDV multi-table support is required to build metadata.") from exc

    metadata = MultiTableMetadata()
    for table_name, df in tables.items():
        metadata.detect_table_from_dataframe(
            table_name=table_name,
            data=df,
            infer_sdtypes=True,
            infer_keys="primary_only",
        )

    for table_name, primary_key in primary_keys.items():
        if primary_key and primary_key in tables[table_name].columns:
            try:
                metadata.set_primary_key(table_name, primary_key)
            except Exception:
                pass

    for relationship in relationships:
        try:
            metadata.add_relationship(
                parent_table_name=relationship["parent_table"],
                child_table_name=relationship["child_table"],
                parent_primary_key=relationship["parent_key"],
                child_foreign_key=relationship["child_key"],
            )
        except Exception:
            pass

    return metadata
