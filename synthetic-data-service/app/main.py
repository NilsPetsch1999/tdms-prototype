"""FastAPI entrypoint for database-backed synthetic data generation."""

from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime
import json

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import create_engine, inspect

from app.config import get_settings
from app.schemas import (
    ErrorResponse,
    GenerateRequest,
    GenerateResponse,
    StatusResponse,
    TrainRequest,
    TrainResponse,
)
from app.service import create_service


load_dotenv()

settings = get_settings()
service = create_service()


@asynccontextmanager
async def lifespan(_: FastAPI):
    service.startup_load_model_if_available()
    yield


app = FastAPI(
    title=settings.app_name,
    version="0.2.0",
    lifespan=lifespan,
)

static_dir = settings.resolve_path("static")
if static_dir.is_dir():
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

output_dir = settings.resolve_path("output")
output_dir.mkdir(parents=True, exist_ok=True)
app.mount("/output", StaticFiles(directory=str(output_dir)), name="output")


@app.get("/", include_in_schema=False)
def read_root():
    index_path = settings.resolve_path("static/index.html")
    if not index_path.is_file():
        raise HTTPException(status_code=404, detail="UI file not found.")
    return FileResponse(str(index_path))


@app.get("/health", response_model=dict[str, str], tags=["system"])
def health_check() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/status", response_model=StatusResponse, tags=["system"])
def get_status() -> StatusResponse:
    return service.get_status()


@app.post(
    "/train",
    response_model=TrainResponse,
    responses={400: {"model": ErrorResponse}},
    tags=["training"],
)
def train_model(request: TrainRequest) -> TrainResponse:
    """Train from one or more database tables."""

    try:
        return service.train(
            table_names=request.table_names,
            schema_name=request.schema_name,
            base_table=request.base_table,
            save_model=request.save_model,
        )
    except ValueError as exc:
        return JSONResponse(status_code=400, content={"detail": str(exc)})
    except RuntimeError as exc:
        return JSONResponse(status_code=500, content={"detail": str(exc)})


@app.post(
    "/generate",
    response_model=GenerateResponse,
    responses={409: {"model": ErrorResponse}, 422: {"model": ErrorResponse}},
    tags=["generation"],
)
def generate_rows(request: GenerateRequest) -> GenerateResponse:
    """Generate synthetic data from the active model."""

    response = service.generate(
        num_rows=request.num_rows,
        column_rules_by_table=request.column_rules_by_table,
    )
    filename = f"generated_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    with (output_dir / filename).open("w", encoding="utf-8") as file_obj:
        json.dump(response.model_dump(), file_obj, indent=2)
    response.filename = filename
    return response


@app.post("/delete", tags=["system"])
def delete_model():
    """Delete the current persisted model and reset in-memory status."""

    service.synthesizer = None
    service.model_type = None
    service.schema_name = None
    service.base_table = None
    service.trained_tables = None
    service.trained_columns = None
    service.row_counts = None
    service.relationships = None

    model_path = settings.resolve_path(settings.model_path)
    if model_path.exists():
        model_path.unlink()
    metadata_path = model_path.with_suffix(model_path.suffix + ".meta.json")
    if metadata_path.exists():
        metadata_path.unlink()
    return {"message": "Model deleted successfully"}


@app.get("/tables", tags=["database"])
def list_tables(schema_name: str | None = Query(None)) -> dict[str, list[str]]:
    """List available database tables for optional schema selection."""

    engine = create_engine(settings.database_url)
    try:
        inspector = inspect(engine)
        tables = inspector.get_table_names(schema=schema_name)
        return {"tables": tables}
    finally:
        engine.dispose()
