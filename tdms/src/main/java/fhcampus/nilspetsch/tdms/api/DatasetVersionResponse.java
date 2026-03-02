package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.domain.FileFormat;

import java.time.Instant;

public record DatasetVersionResponse(
    Long datasetId,
    Long versionId,
    Integer versionNumber,
    String schemaName,
    String tableName,
    String schemaVersion,
    String generationParametersJson,
    String maskingRulesJson,
    FileFormat fileFormat,
    String storagePath,
    String checksumSha256,
    Integer rowCount,
    String createdBy,
    Instant createdAt
) {
}
