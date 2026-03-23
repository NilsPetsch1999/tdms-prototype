"""Application service layer for synthetic data generation."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import HTTPException, status
import pandas as pd

from app.config import Settings, get_settings
from app.model_store import load_synthesizer, model_exists, save_synthesizer
from app.schemas import GenerateResponse, StatusResponse, TrainResponse
from app.trainer import build_metadata, load_real_data, sample_rows, train_ctgan


@dataclass
class SyntheticDataService:
    """Stateful service wrapper around the active synthesizer."""

    settings: Settings
    synthesizer: Any = None
    trained_on_rows: int | None = None
    trained_on_columns: list[str] | None = None

    @property
    def model_path(self) -> str:
        return self.settings.model_path

    def startup_load_model_if_available(self) -> None:
        """Load a previously saved model if present."""

        resolved_model_path = str(self.settings.resolve_path(self.model_path))
        if not model_exists(resolved_model_path):
            return

        self.synthesizer = load_synthesizer(resolved_model_path)

    def train(self, data_path: str | None, save_model: bool) -> TrainResponse:
        """Train the synthesizer on the provided CSV path."""

        configured_path = data_path or self.settings.data_path
        resolved_path = str(self.settings.resolve_path(configured_path))
        dataset = load_real_data(resolved_path)
        metadata = build_metadata(dataset)
        synthesizer = train_ctgan(dataset, metadata)

        self.synthesizer = synthesizer
        self.trained_on_rows = len(dataset)
        self.trained_on_columns = [str(column) for column in dataset.columns.tolist()]

        if save_model:
            save_synthesizer(
                synthesizer,
                str(self.settings.resolve_path(self.model_path)),
            )

        return TrainResponse(
            status="trained",
            rows=self.trained_on_rows,
            columns=self.trained_on_columns,
            model_saved=save_model,
            message=f"CTGAN synthesizer trained from '{resolved_path}'.",
        )

    def generate(self, num_rows: int) -> GenerateResponse:
        """Generate synthetic rows from the active synthesizer."""

        if self.synthesizer is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="No trained model is available. Train or load a model first.",
            )

        sampled = sample_rows(self.synthesizer, num_rows)
        sampled = sampled.where(pd.notnull(sampled), None)
        records = sampled.to_dict(orient="records")

        # Convert non-serializable types to strings
        for record in records:
            for key, value in record.items():
                if pd.isna(value):
                    record[key] = None
                elif hasattr(value, 'isoformat'):  # Timestamp, datetime
                    record[key] = value.isoformat()
                else:
                    record[key] = str(value) if not isinstance(value, (int, float, str, bool, type(None))) else value

        return records

    def get_status(self) -> StatusResponse:
        """Return current model/service status."""

        return StatusResponse(
            status="ok",
            model_loaded=self.synthesizer is not None,
            trained_on_rows=self.trained_on_rows,
            trained_on_columns=self.trained_on_columns,
            model_path=str(self.settings.resolve_path(self.model_path)),
        )

    def sample_preview(self, rows: int) -> list[dict[str, Any]]:
        """Return the first few rows of the configured real dataset."""

        preview_rows = max(1, rows)
        dataset_path = self.settings.resolve_path(self.settings.data_path)
        if not dataset_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Configured dataset not found: {dataset_path}",
            )

        df = load_real_data(str(dataset_path))
        preview = df.head(preview_rows).where(pd.notnull(df.head(preview_rows)), None)
        return preview.to_dict(orient="records")


def create_service() -> SyntheticDataService:
    """Create a service instance from application settings."""

    return SyntheticDataService(settings=get_settings())
