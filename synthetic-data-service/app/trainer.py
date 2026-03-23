"""Data loading and CTGAN training helpers."""

from __future__ import annotations

from typing import Any

import pandas as pd

from app.metadata_utils import build_single_table_metadata


def load_real_data(csv_path: str) -> pd.DataFrame:
    """Load and lightly normalize a CSV dataset for single-table synthesis."""

    try:
        df = pd.read_csv(csv_path)
    except FileNotFoundError as exc:
        raise ValueError(f"Dataset not found: {csv_path}") from exc
    except Exception as exc:
        raise ValueError(f"Failed to read dataset '{csv_path}': {exc}") from exc

    if df.empty:
        raise ValueError("Dataset is empty. Provide a CSV with at least 10 rows.")

    if len(df) < 10:
        raise ValueError("Dataset must contain at least 10 rows for CTGAN training.")

    for column_name in df.columns:
        if df[column_name].dtype != "object":
            continue

        parsed = pd.to_datetime(df[column_name], errors="coerce")
        if parsed.notna().sum() >= max(1, int(len(df) * 0.8)):
            df[column_name] = parsed

    return df


def build_metadata(df: pd.DataFrame) -> Any:
    """Build metadata for a single-table dataframe."""

    return build_single_table_metadata(df)


def train_ctgan(df: pd.DataFrame, metadata: Any) -> Any:
    """Train a CTGAN synthesizer using the installed SDV version."""

    try:
        from sdv.single_table import CTGANSynthesizer
    except ImportError as exc:
        raise RuntimeError(
            "SDV is not installed. Install dependencies from requirements.txt before training."
        ) from exc

    synthesizer = CTGANSynthesizer(metadata)
    synthesizer.fit(df)
    return synthesizer


def sample_rows(synthesizer: Any, num_rows: int) -> pd.DataFrame:
    """Generate synthetic rows from a trained synthesizer."""

    return synthesizer.sample(num_rows=num_rows)
