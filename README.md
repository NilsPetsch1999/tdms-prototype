# Test Data Management System (TDMS) - Prototype

## Overview
This repository contains a backend prototype for a Test Data Management System (TDMS) built with Spring Boot.

The prototype supports:
- synthetic dataset generation from a live MySQL schema
- masking/anonymization of real MySQL table data
- dataset versioning with reproducible metadata
- metadata in MySQL + generated data files on filesystem

Important: this prototype supports MySQL database instances only.

## Architecture
- Backend: Spring Boot (REST API)
- Metadata DB: MySQL
- Data storage: CSV files on local filesystem (`tdms-data/` by default)

### Why this storage approach?
For a prototype, splitting metadata and payload files is pragmatic:
- MySQL is efficient for querying metadata and version history
- CSV files are simple to inspect, export, and compare
- keeping large dataset payloads outside metadata tables avoids DB bloat

## Implemented Backend Components
- Schema Introspection Service: reads schemas/tables/columns from `information_schema`
- Test Data Generator: schema-driven synthetic generation, optional deterministic `seed`
- Masking Engine: substitution, pseudonymization, tokenization, hashing, generalization
- Version Manager: every generation/masking request creates a new immutable dataset version
- Metadata Service: stores lineage and reproducibility data

## API Endpoints
Base path: `/api`

### Schema inspection
- `GET /schemas`
- `GET /schemas/{schemaName}/tables`
- `GET /schemas/{schemaName}/tables/{tableName}/columns`

### Dataset generation and masking
- `POST /datasets/synthetic`
- `POST /datasets/masked`

### Dataset browsing and download
- `GET /datasets`
- `GET /datasets/{datasetId}/versions`
- `GET /datasets/{datasetId}/versions/{versionNumber}`
- `GET /datasets/{datasetId}/versions/{versionNumber}/file`

## OpenAPI / Swagger
After starting the backend, OpenAPI docs are available at:
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Example Requests
### Synthetic dataset
```json
POST /api/datasets/synthetic
{
  "datasetName": "customer_synth",
  "description": "Synthetic customer demo",
  "schemaName": "tdms",
  "tableName": "customer",
  "rowCount": 1000,
  "seed": 12345,
  "schemaVersion": "v1",
  "createdBy": "nils"
}
```

### Masked dataset
```json
POST /api/datasets/masked
{
  "datasetName": "customer_masked",
  "description": "Masked production-like data",
  "schemaName": "tdms",
  "tableName": "customer",
  "rowLimit": 500,
  "schemaVersion": "v1",
  "createdBy": "nils",
  "maskingRules": {
    "first_name": "SUBSTITUTION",
    "last_name": "SUBSTITUTION",
    "email": "HASHING",
    "age": "GENERALIZATION"
  }
}
```

## MySQL Setup
A MySQL container is provided in `tdms/docker-compose.yml`.

Start DB:
```bash
docker compose up -d
```

Connection used by default:
- host: `localhost`
- port: `3307`
- database: `tdms`
- user: `tdms_user`
- password: `tdms123`

## SQL Schemas Included
- Metadata schema (optional SQL-first setup):
  - `tdms/src/main/resources/sql/tdms_metadata_schema.sql`
- Sample source schema with demo data:
  - `tdms/src/main/resources/sql/sample_source_schema.sql`

If you keep `spring.jpa.hibernate.ddl-auto=update`, metadata tables are auto-created.

## Running the Backend
From `tdms/`:
```bash
./mvnw spring-boot:run
```

## Configuration
Main config file: `tdms/src/main/resources/application.properties`

Key properties:
- `spring.datasource.*` -> MySQL connection
- `tdms.storage.root` -> root folder for generated dataset files
- `tdms.storage.create-if-missing=true` -> auto-create storage path

## Scope and Limitations
- Prototype, not production-ready
- No RBAC or auth
- No distributed object storage integration
- No job queue yet (requests are processed synchronously)

## License
Academic prototype - non-production use.
