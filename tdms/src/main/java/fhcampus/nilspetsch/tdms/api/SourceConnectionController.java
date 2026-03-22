package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.service.SourceDatabaseConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/source-connection")
@Validated
@Tag(name = "Source Connection", description = "Runtime configuration for the source MySQL database connection")
public class SourceConnectionController {

    private final SourceDatabaseConnectionService sourceDatabaseConnectionService;

    public SourceConnectionController(SourceDatabaseConnectionService sourceDatabaseConnectionService) {
        this.sourceDatabaseConnectionService = sourceDatabaseConnectionService;
    }

    @GetMapping
    @Operation(summary = "Get active source connection", description = "Returns the currently active source database connection details.")
    public SourceConnectionResponse getConnection() {
        return sourceDatabaseConnectionService.describeCurrentConnection();
    }

    @PutMapping
    @Operation(summary = "Update source connection", description = "Validates and activates a new source database connection at runtime.")
    public SourceConnectionResponse updateConnection(@Valid @RequestBody SourceConnectionRequest request) {
        return sourceDatabaseConnectionService.updateConnection(request);
    }
}
