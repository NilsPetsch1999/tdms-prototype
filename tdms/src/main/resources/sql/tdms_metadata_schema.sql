-- TDMS metadata schema for MySQL 8+
-- Execute manually if you prefer SQL-first setup over Hibernate ddl-auto.

CREATE TABLE IF NOT EXISTS datasets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500) NULL,
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS dataset_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    schema_version VARCHAR(64) NULL,
    generation_parameters_json LONGTEXT NULL,
    masking_rules_json LONGTEXT NULL,
    file_format VARCHAR(16) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    row_count INT NOT NULL,
    created_by VARCHAR(100) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_dataset_version UNIQUE (dataset_id, version_number),
    CONSTRAINT fk_dataset_version_dataset FOREIGN KEY (dataset_id) REFERENCES datasets (id)
);
