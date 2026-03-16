package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.domain.FileFormat;
import fhcampus.nilspetsch.tdms.service.DatasetMetadataService;
import fhcampus.nilspetsch.tdms.service.DatasetStorageService;
import fhcampus.nilspetsch.tdms.service.MaskingDataService;
import fhcampus.nilspetsch.tdms.service.SyntheticDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/datasets")
@Tag(name = "Datasets", description = "Dataset generation, masking, versioning and file access")
public class DatasetController {

    private final SyntheticDataService syntheticDataService;
    private final MaskingDataService maskingDataService;
    private final DatasetMetadataService datasetMetadataService;
    private final DatasetStorageService datasetStorageService;

    public DatasetController(
        SyntheticDataService syntheticDataService,
        MaskingDataService maskingDataService,
        DatasetMetadataService datasetMetadataService,
        DatasetStorageService datasetStorageService
    ) {
        this.syntheticDataService = syntheticDataService;
        this.maskingDataService = maskingDataService;
        this.datasetMetadataService = datasetMetadataService;
        this.datasetStorageService = datasetStorageService;
    }

    @PostMapping("/synthetic")
    @Operation(summary = "Generate synthetic dataset", description = "Creates a new synthetic dataset version from one table, selected tables, or a full schema with foreign-key aware generation.")
    public DatasetVersionResponse createSynthetic(@Valid @RequestBody CreateSyntheticDatasetRequest request) {
        DatasetVersion version = syntheticDataService.generate(request);
        return ApiMapper.toResponse(version);
    }

    @PostMapping("/masked")
    @Operation(summary = "Create masked dataset", description = "Reads source rows from MySQL and applies masking rules to create a new dataset version.")
    public DatasetVersionResponse createMasked(@Valid @RequestBody CreateMaskedDatasetRequest request) {
        DatasetVersion version = maskingDataService.mask(request);
        return ApiMapper.toResponse(version);
    }

    @GetMapping
    @Operation(summary = "List datasets", description = "Lists all datasets stored in TDMS metadata.")
    public List<DatasetResponse> listDatasets() {
        return datasetMetadataService.listDatasets().stream().map(ApiMapper::toResponse).toList();
    }

    @GetMapping("/{datasetId}/versions")
    @Operation(summary = "List dataset versions", description = "Lists all versions of one dataset in descending version order.")
    public List<DatasetVersionResponse> listVersions(@PathVariable long datasetId) {
        return datasetMetadataService.listVersions(datasetId).stream().map(ApiMapper::toResponse).toList();
    }

    @GetMapping("/{datasetId}/versions/{versionNumber}")
    @Operation(summary = "Get dataset version", description = "Returns metadata for one concrete dataset version.")
    public DatasetVersionResponse getVersion(@PathVariable long datasetId, @PathVariable int versionNumber) {
        return ApiMapper.toResponse(datasetMetadataService.getVersion(datasetId, versionNumber));
    }

    @GetMapping("/{datasetId}/versions/{versionNumber}/file")
    @Operation(summary = "Download version file", description = "Downloads the stored file for a dataset version (CSV or ZIP for multi-table synthetic bundles).")
    public ResponseEntity<Resource> downloadVersionFile(@PathVariable long datasetId, @PathVariable int versionNumber) {
        DatasetVersion version = datasetMetadataService.getVersion(datasetId, versionNumber);
        Resource resource = datasetStorageService.loadAsResource(version.getStoragePath());
        String extension = version.getFileFormat() == FileFormat.ZIP ? "zip" : "csv";
        String contentType = version.getFileFormat() == FileFormat.ZIP ? "application/zip" : "text/csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header("Content-Disposition", "attachment; filename=\"dataset-" + datasetId + "-v" + versionNumber + "." + extension + "\"")
            .body(resource);
    }
}
