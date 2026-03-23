"""Helpers for building single-table SDV metadata."""

from __future__ import annotations

from typing import Any

import pandas as pd
from pandas.api.types import (
    is_bool_dtype,
    is_datetime64_any_dtype,
    is_integer_dtype,
    is_float_dtype,
)


def _load_single_table_metadata_class() -> type[Any]:
    """Import SDV metadata types lazily to remain version tolerant."""

    try:
        from sdv.metadata import SingleTableMetadata

        return SingleTableMetadata
    except ImportError as exc:
        raise RuntimeError(
            "SDV is required to build metadata. Install dependencies from requirements.txt."
        ) from exc


def _infer_sdtype(series: pd.Series) -> str:
    """Map pandas dtypes to simple SDV sdtypes."""

    if is_bool_dtype(series):
        return "boolean"
    if is_datetime64_any_dtype(series):
        return "datetime"
    if is_integer_dtype(series) or is_float_dtype(series):
        return "numerical"
    return "categorical"


def build_single_table_metadata(df: pd.DataFrame) -> Any:
    """Create SDV metadata using auto-detection first, then a manual fallback.

    Metadata quality strongly affects the realism and consistency of synthetic
    data, so the fallback keeps the mapping conservative and easy to inspect.
    """

    metadata_cls = _load_single_table_metadata_class()
    metadata = metadata_cls()

    try:
        detect_from_dataframe = getattr(metadata, "detect_from_dataframe", None)
        if callable(detect_from_dataframe):
            detect_from_dataframe(data=df)
            return metadata
    except Exception:
        pass

    columns: dict[str, dict[str, str]] = {}
    for column_name in df.columns:
        columns[column_name] = {"sdtype": _infer_sdtype(df[column_name])}

    metadata_dict = {"columns": columns}

    try:
        load_from_dict = getattr(metadata, "load_from_dict", None)
        if callable(load_from_dict):
            load_from_dict(metadata_dict)
            return metadata
    except Exception:
        pass

    try:
        return metadata_cls.load_from_dict(metadata_dict)
    except AttributeError as exc:
        raise RuntimeError("Unable to create SDV metadata with the installed version.") from exc
