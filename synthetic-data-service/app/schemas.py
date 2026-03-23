"""Request and response schemas."""

from typing import Any

from pydantic import BaseModel, Field, field_validator

from app.config import get_settings


settings = get_settings()


class TrainRequest(BaseModel):
    """Request body for training a synthesizer."""

    data_path: str | None = None
    save_model: bool = True


class TrainResponse(BaseModel):
    """Training result metadata."""

    status: str
    rows: int
    columns: list[str]
    model_saved: bool
    message: str


class GenerateRequest(BaseModel):
    """Request body for synthetic row generation."""

    num_rows: int = Field(..., gt=0)
    randomize_seed: bool = False

    @field_validator("num_rows")
    @classmethod
    def validate_num_rows(cls, value: int) -> int:
        """Ensure the requested row count stays within service limits."""

        if value > settings.max_generate_rows:
            raise ValueError(
                f"num_rows must be less than or equal to {settings.max_generate_rows}"
            )
        return value


class GenerateResponse(BaseModel):
    """Synthetic data payload."""

    status: str
    num_rows: int
    columns: list[str]
    data: list[dict[str, Any]]
    filename: str

    status: str
    num_rows: int
    columns: list[str]
    data: list[dict[str, Any]]


class StatusResponse(BaseModel):
    """Current service/model status."""

    status: str
    model_loaded: bool
    trained_on_rows: int | None
    trained_on_columns: list[str] | None
    model_path: str


class ErrorResponse(BaseModel):
    """Error payload."""

    detail: str
