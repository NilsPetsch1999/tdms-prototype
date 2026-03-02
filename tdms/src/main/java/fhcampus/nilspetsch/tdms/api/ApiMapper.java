package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.service.SchemaColumnMeta;

public final class ApiMapper {

    private ApiMapper() {
    }

    public static SchemaColumnResponse toResponse(SchemaColumnMeta meta) {
        return new SchemaColumnResponse(
            meta.columnName(),
            meta.dataType(),
            meta.nullable(),
            meta.primaryKey(),
            meta.characterMaxLength(),
            meta.numericPrecision(),
            meta.numericScale()
        );
    }

    public static DatasetResponse toResponse(Dataset dataset) {
        return new DatasetResponse(
            dataset.getId(),
            dataset.getName(),
            dataset.getDescription(),
            dataset.getSourceType(),
            dataset.getStatus(),
            dataset.getCreatedAt(),
            dataset.getUpdatedAt()
        );
    }

    public static DatasetVersionResponse toResponse(DatasetVersion version) {
        return new DatasetVersionResponse(
            version.getDataset().getId(),
            version.getId(),
            version.getVersionNumber(),
            version.getSchemaName(),
            version.getTableName(),
            version.getSchemaVersion(),
            version.getGenerationParametersJson(),
            version.getMaskingRulesJson(),
            version.getFileFormat(),
            version.getStoragePath(),
            version.getChecksumSha256(),
            version.getRowCount(),
            version.getCreatedBy(),
            version.getCreatedAt()
        );
    }
}
