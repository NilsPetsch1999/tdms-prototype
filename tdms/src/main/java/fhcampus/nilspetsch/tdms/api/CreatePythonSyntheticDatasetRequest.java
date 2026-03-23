package fhcampus.nilspetsch.tdms.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreatePythonSyntheticDatasetRequest(
    @NotBlank String datasetName,
    String description,
    @NotBlank String schemaName,
    @NotBlank String tableName,
    @Min(1) @Max(200000) int rowCount,
    String schemaVersion,
    String createdBy
) {
}
