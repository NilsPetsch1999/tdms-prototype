package fhcampus.nilspetsch.tdms.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record CreateSyntheticDatasetRequest(
    @NotBlank String datasetName,
    String description,
    @NotBlank String schemaName,
    String tableName,
    List<String> tableNames,
    List<String> excludedTableNames,
    Boolean generateWholeSchema,
    Boolean includeRelatedTables,
    Boolean useExistingParentKeys,
    @Min(1) @Max(200000) int rowCount,
    Map<String, Integer> rowCountByTable,
    @Min(1) @Max(200000) Integer defaultRelatedTableRowCount,
    Double nullableFieldProbability,
    Long seed,
    String schemaVersion,
    String createdBy
) {
}
