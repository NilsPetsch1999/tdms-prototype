# Synthetic Data Service

Production-style Python microservice for single-table synthetic tabular data generation using FastAPI and SDV CTGAN.

## Features

- Load a real dataset from a local CSV file
- Detect or build SDV single-table metadata
- Train an `sdv.single_table.CTGANSynthesizer`
- Save and reload a trained model
- Generate synthetic rows through a JSON REST API
- Expose health and status endpoints for integration with a Java backend

## Project Structure

```text
synthetic-data-service/
  app/
    __init__.py
    main.py
    config.py
    schemas.py
    service.py
    trainer.py
    metadata_utils.py
    model_store.py
  data/
    real_data.csv
  models/
    .gitkeep
  tests/
    test_health.py
    test_generate_validation.py
  requirements.txt
  README.md
```

## Setup

Use Python 3.11 or newer.

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

Optional environment variables:

```bash
APP_NAME="Synthetic Data Service"
DATA_PATH="data/real_data.csv"
MODEL_PATH="models/ctgan_synthesizer.pkl"
MAX_GENERATE_ROWS=10000
DEFAULT_SAMPLE_ROWS=100
RANDOM_STATE=42
```

## Run

Start the API from the `synthetic-data-service` directory:

```bash
uvicorn app.main:app --reload
```

Swagger UI will then be available at `http://127.0.0.1:8000/docs`.

## API Endpoints

### Health check

```bash
curl http://127.0.0.1:8000/health
```

### Status

```bash
curl http://127.0.0.1:8000/status
```

### Train

Request body:

```json
{
  "data_path": "data/real_data.csv",
  "save_model": true
}
```

Example:

```bash
curl -X POST http://127.0.0.1:8000/train ^
  -H "Content-Type: application/json" ^
  -d "{\"data_path\":\"data/real_data.csv\",\"save_model\":true}"
```

### Generate

Request body:

```json
{
  "num_rows": 100,
  "randomize_seed": false
}
```

Example:

```bash
curl -X POST http://127.0.0.1:8000/generate ^
  -H "Content-Type: application/json" ^
  -d "{\"num_rows\":100,\"randomize_seed\":false}"
```

### Sample preview

```bash
curl "http://127.0.0.1:8000/sample-preview?rows=5"
```

## Tests

Run the minimal test suite with:

```bash
pytest
```

## Notes for Java Integration

The service is intentionally JSON-only and stateless at the HTTP boundary, which makes it easy for a Java backend to call with a normal HTTP client such as Spring `WebClient`, `RestClient`, or OpenFeign.

Typical integration flow:

1. Java sends `POST /train` with the CSV path to train or refresh the model.
2. Java checks `GET /status` to confirm the model is loaded.
3. Java sends `POST /generate` with the requested synthetic row count.
4. Java consumes the returned JSON array and stores or forwards it as needed.

## Implementation Notes

- This service supports single-table tabular data only.
- Authentication, databases, Docker, and frontend code are intentionally out of scope.
- Metadata detection is version-tolerant and falls back to a simple dtype mapping when SDV auto-detection is unavailable.
