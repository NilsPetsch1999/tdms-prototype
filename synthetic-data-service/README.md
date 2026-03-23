# Synthetic Data Service

Python microservice for synthetic tabular data generation from a live database using FastAPI and SDV.

## What It Does

- Lists available database tables
- Trains on one selected table or multiple related tables
- Uses `CTGANSynthesizer` for a single table
- Uses `HMASynthesizer` for multiple related tables
- Preserves detected foreign-key relationships for relational generation
- Saves and reloads the trained model plus its training metadata
- Generates JSON output and stores each generation run in `output/`

## Environment

Create a `.env` file like this:

```env
DATABASE_URL=mysql+pymysql://tdms_user:tdms123@localhost:3307/tdms
```

Optional settings:

```env
APP_NAME=Synthetic Data Service
MODEL_PATH=models/ctgan_synthesizer.pkl
MAX_GENERATE_ROWS=10000
```

## Setup

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## Run

```bash
uvicorn app.main:app --reload
```

Open:

- UI: `http://127.0.0.1:8000/`
- Swagger: `http://127.0.0.1:8000/docs`

## Main Flow

1. Load tables from the connected database.
2. Select one or more tables for training.
3. Optionally choose a base table.
4. Train the model.
5. Generate a synthetic dataset.

For multi-table training, the base table acts as the anchor for generation size. If you request `1000` rows, the service scales the relational output so the base table is generated around that size while keeping linked child tables consistent.

## API Examples

### List tables

```bash
curl http://127.0.0.1:8000/tables
```

### Train on one table

```json
{
  "table_names": ["customers"],
  "schema_name": null,
  "base_table": "customers",
  "save_model": true
}
```

### Train on multiple related tables

```json
{
  "table_names": ["customers", "orders", "order_items"],
  "schema_name": null,
  "base_table": "orders",
  "save_model": true
}
```

### Generate

```json
{
  "num_rows": 250,
  "randomize_seed": false
}
```

## Tests

```bash
pytest
```

## Notes

- Single-table generation returns one table in the response payload.
- Multi-table generation returns a dictionary of table names to generated rows.
- Composite primary keys and composite foreign keys are intentionally skipped for now to keep metadata generation stable.
