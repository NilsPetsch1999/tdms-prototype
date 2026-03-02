package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.service.MaskingTechnique;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateMaskedDatasetRequest(
    @NotBlank String datasetName,
    String description,
    @NotBlank String schemaName,
    @NotBlank String tableName,
    @Min(1) @Max(200000) int rowLimit,
    String schemaVersion,
    String createdBy,
    @NotNull Map<String, MaskingTechnique> maskingRules
) {
}
