"""FastAPI entrypoint for the synthetic data microservice."""

from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime
import json
import os

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
import pandas as pd

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


settings = get_settings()
service = create_service()


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Load a persisted model at startup when available."""

    service.startup_load_model_if_available()
    yield


app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    lifespan=lifespan,
)

app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
def read_root():
    return FileResponse("static/index.html")


@app.get("/health", response_model=dict[str, str], tags=["system"])
def health_check() -> dict[str, str]:
    """Simple health endpoint for liveness checks."""

    return {"status": "ok"}


@app.get("/status", response_model=StatusResponse, tags=["system"])
def get_status() -> StatusResponse:
    """Return current model availability and training metadata."""

    return service.get_status()


@app.post(
    "/train",
    response_model=TrainResponse,
    responses={400: {"model": ErrorResponse}},
    tags=["training"],
)
def train_model(request: TrainRequest) -> TrainResponse:
    """Train a CTGAN synthesizer from a local CSV file."""

    try:
        return service.train(data_path=request.data_path, save_model=request.save_model)
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
    """Generate synthetic rows from the active trained model."""

    result = service.generate(num_rows=request.num_rows)
    # Save to JSON
    os.makedirs("output", exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"generated_{timestamp}.json"
    filepath = f"output/{filename}"
    df = pd.DataFrame(result)
    columns = list(df.columns)
    response = GenerateResponse(
        status="ok",
        num_rows=request.num_rows,
        columns=columns,
        data=result,
        filename=filename
    )
    with open(filepath, 'w') as f:
        json.dump(response.dict(), f, indent=2)
    return response


@app.get(
    "/sample-preview",
    response_model=list[dict],
    responses={404: {"model": ErrorResponse}},
    tags=["data"],
)
def sample_preview(rows: int = Query(5, ge=1, le=100)) -> list[dict]:
    """Return a small preview of the configured real CSV dataset."""

    return service.sample_preview(rows)


@app.post("/upload")
async def upload_csv(file: UploadFile = File(...)):
    """Upload a CSV file for training."""

    import os
    os.makedirs("data", exist_ok=True)
    file_path = f"data/{file.filename}"
    with open(file_path, "wb") as f:
        content = await file.read()
        f.write(content)
    return {"message": f"File {file.filename} uploaded successfully", "path": file_path}


@app.post("/delete")
def delete_model():
    """Delete the current trained model."""

    import os
    service.synthesizer = None
    service.trained_on_rows = None
    service.trained_on_columns = None
    model_path = str(settings.resolve_path(settings.model_path))
    if os.path.exists(model_path):
        os.remove(model_path)
    return {"message": "Model deleted successfully"}


@app.get("/download/{filename}")
def download_json(filename: str):
    """Download a generated JSON file."""

    filepath = f"output/{filename}"
    if not os.path.exists(filepath):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(filepath, media_type='application/json', filename=filename)
