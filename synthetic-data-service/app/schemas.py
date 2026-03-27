"""Request and response schemas for database-backed synthetic generation."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator

from app.config import get_settings


settings = get_settings()


class TrainRequest(BaseModel):
    """Request body for model training from database tables."""

    table_names: list[str] = Field(..., min_length=1)
    schema_name: str | None = None
    base_table: str | None = None
    save_model: bool = True

    @field_validator("table_names")
    @classmethod
    def validate_table_names(cls, value: list[str]) -> list[str]:
        cleaned = [table.strip() for table in value if table and table.strip()]
        unique = list(dict.fromkeys(cleaned))
        if not unique:
            raise ValueError("Provide at least one table name.")
        return unique

    @model_validator(mode="after")
    def validate_base_table(self) -> "TrainRequest":
        if self.base_table and self.base_table not in self.table_names:
            raise ValueError("base_table must be one of the selected table_names.")
        return self


class TrainResponse(BaseModel):
    """Training result metadata."""

    status: str
    model_type: str
    schema_name: str | None
    base_table: str
    tables: list[str]
    columns: dict[str, list[str]]
    row_counts: dict[str, int]
    relationships: list[dict[str, str]]
    model_saved: bool
    message: str


class GenerateRequest(BaseModel):
    """Request body for synthetic row generation."""

    num_rows: int = Field(..., gt=0)
    randomize_seed: bool = False
    column_rules_by_table: dict[str, dict[str, "SyntheticColumnRule"]] | None = None

    @field_validator("num_rows")
    @classmethod
    def validate_num_rows(cls, value: int) -> int:
        if value > settings.max_generate_rows:
            raise ValueError(
                f"num_rows must be less than or equal to {settings.max_generate_rows}"
            )
        return value


class GenerateResponse(BaseModel):
    """Synthetic data payload."""

    status: str
    model_type: str
    base_table: str
    num_rows: int
    tables: list[str]
    data: dict[str, list[dict[str, Any]]]
    filename: str | None = None


class SyntheticColumnRule(BaseModel):
    """Column-level post-processing rule for generated output."""

    strategy: str
    config: str | None = None

    @field_validator("strategy")
    @classmethod
    def validate_strategy(cls, value: str) -> str:
        normalized = value.strip().upper()
        if normalized not in {"DEFAULT", "FIXED", "EXPRESSION", "AGGREGATE"}:
            raise ValueError("strategy must be DEFAULT, FIXED, EXPRESSION, or AGGREGATE")
        return normalized


class StatusResponse(BaseModel):
    """Current service/model status."""

    status: str
    model_loaded: bool
    model_type: str | None
    schema_name: str | None
    base_table: str | None
    trained_tables: list[str] | None
    trained_columns: dict[str, list[str]] | None
    row_counts: dict[str, int] | None
    primary_keys: dict[str, str | None] | None
    relationships: list[dict[str, str]] | None
    model_path: str


class ErrorResponse(BaseModel):
    """Error payload."""

    detail: str
