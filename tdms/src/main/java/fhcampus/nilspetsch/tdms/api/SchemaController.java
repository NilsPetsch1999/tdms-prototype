package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.service.SchemaIntrospectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schemas")
@Validated
@Tag(name = "Schema", description = "MySQL schema introspection endpoints")
public class SchemaController {

    private final SchemaIntrospectionService schemaIntrospectionService;

    public SchemaController(SchemaIntrospectionService schemaIntrospectionService) {
        this.schemaIntrospectionService = schemaIntrospectionService;
    }

    @GetMapping
    @Operation(summary = "List schemas", description = "Returns non-system MySQL schemas from information_schema.")
    public List<String> listSchemas() {
        return schemaIntrospectionService.listSchemas();
    }

    @GetMapping("/{schemaName}/tables")
    @Operation(summary = "List tables", description = "Lists all base tables of a schema.")
    public List<String> listTables(@PathVariable @NotBlank String schemaName) {
        return schemaIntrospectionService.listTables(schemaName);
    }

    @GetMapping("/{schemaName}/tables/{tableName}/columns")
    @Operation(summary = "List columns", description = "Lists column metadata for one table.")
    public List<SchemaColumnResponse> listColumns(
        @PathVariable @NotBlank String schemaName,
        @PathVariable @NotBlank String tableName
    ) {
        return schemaIntrospectionService.listColumns(schemaName, tableName).stream()
            .map(ApiMapper::toResponse)
            .toList();
    }

    @GetMapping("/{schemaName}/tables/{tableName}/rows")
    @Operation(summary = "Preview rows", description = "Loads sample table data from the active source connection.")
    public List<Map<String, Object>> listRows(
        @PathVariable @NotBlank String schemaName,
        @PathVariable @NotBlank String tableName,
        @RequestParam(defaultValue = "25") @Positive int limit
    ) {
        return schemaIntrospectionService.listRows(schemaName, tableName, limit);
    }
}
