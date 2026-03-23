package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.CreatePythonSyntheticDatasetRequest;
import fhcampus.nilspetsch.tdms.config.SyntheticPythonServiceProperties;
import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.domain.FileFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PythonSyntheticDataService {

    private final SchemaIntrospectionService schemaIntrospectionService;
    private final DatasetStorageService datasetStorageService;
    private final DatasetMetadataService datasetMetadataService;
    private final SyntheticPythonServiceProperties properties;
    private final RestClient restClient;

    public PythonSyntheticDataService(
        SchemaIntrospectionService schemaIntrospectionService,
        DatasetStorageService datasetStorageService,
        DatasetMetadataService datasetMetadataService,
        SyntheticPythonServiceProperties properties
    ) {
        this.schemaIntrospectionService = schemaIntrospectionService;
        this.datasetStorageService = datasetStorageService;
        this.datasetMetadataService = datasetMetadataService;
        this.properties = properties;
        this.restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .build();
    }

    @Transactional
    public DatasetVersion generate(CreatePythonSyntheticDatasetRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalArgumentException("Python synthetic integration is disabled.");
        }

        List<Map<String, Object>> sourceRows = schemaIntrospectionService.listRows(request.schemaName(), request.tableName(), null);
        if (sourceRows.isEmpty()) {
            throw new IllegalArgumentException("No source rows found for " + request.schemaName() + "." + request.tableName());
        }
        if (sourceRows.size() < 10) {
            throw new IllegalArgumentException("Python CTGAN training requires at least 10 source rows.");
        }

        Path trainingCsv = datasetStorageService.storeWorkingCsv(
            request.schemaName(),
            request.tableName(),
            "python-training",
            sourceRows
        );

        PythonTrainResponse trainResponse = trainRemoteModel(trainingCsv);
        PythonGenerateResponse generateResponse = generateRemoteRows(request.rowCount());
        if (generateResponse.data() == null || generateResponse.data().isEmpty()) {
            throw new IllegalArgumentException("Python synthetic service returned no generated rows.");
        }

        Dataset dataset = datasetMetadataService.getOrCreateDataset(
            request.datasetName(),
            request.description(),
            DatasetSourceType.SYNTHETIC
        );
        int nextVersionNumber = datasetMetadataService.nextVersionNumber(dataset.getId());

        StoredDataFile file = datasetStorageService.storeAsCsv(
            dataset.getId(),
            nextVersionNumber,
            request.schemaName(),
            request.tableName(),
            generateResponse.data()
        );

        Map<String, Object> generationParameters = new LinkedHashMap<>();
        generationParameters.put("rowCount", request.rowCount());
        generationParameters.put("strategy", "python-ctgan-single-table");
        generationParameters.put("serviceBaseUrl", properties.getBaseUrl());
        generationParameters.put("trainingCsvPath", trainingCsv.toString());
        generationParameters.put("trainingRows", trainResponse.rows());
        generationParameters.put("trainingColumns", trainResponse.columns());
        generationParameters.put("remoteModelSaved", trainResponse.modelSaved());
        generationParameters.put("sourceTable", request.tableName());

        return datasetMetadataService.saveVersion(
            dataset,
            nextVersionNumber,
            request.schemaName(),
            request.tableName(),
            request.schemaVersion(),
            generationParameters,
            Map.of(),
            request.createdBy(),
            FileFormat.CSV,
            file
        );
    }

    private PythonTrainResponse trainRemoteModel(Path trainingCsv) {
        try {
            return restClient.post()
                .uri("/train")
                .body(Map.of(
                    "data_path", trainingCsv.toString(),
                    "save_model", properties.isPersistRemoteModel()
                ))
                .retrieve()
                .body(PythonTrainResponse.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalArgumentException(resolveRemoteError("training", ex), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to call Python synthetic training service: " + ex.getMessage(), ex);
        }
    }

    private PythonGenerateResponse generateRemoteRows(int rowCount) {
        try {
            return restClient.post()
                .uri("/generate")
                .body(Map.of(
                    "num_rows", rowCount,
                    "randomize_seed", false
                ))
                .retrieve()
                .body(PythonGenerateResponse.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalArgumentException(resolveRemoteError("generation", ex), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to call Python synthetic generation service: " + ex.getMessage(), ex);
        }
    }

    private String resolveRemoteError(String operation, RestClientResponseException ex) {
        String responseBody = ex.getResponseBodyAsString();
        if (responseBody != null && !responseBody.isBlank()) {
            return "Python synthetic " + operation + " failed: " + responseBody;
        }
        return "Python synthetic " + operation + " failed with HTTP " + ex.getStatusCode().value();
    }

    private record PythonTrainResponse(
        String status,
        int rows,
        List<String> columns,
        @JsonProperty("model_saved")
        boolean modelSaved,
        String message
    ) {
    }

    private record PythonGenerateResponse(
        String status,
        @JsonProperty("num_rows")
        int numRows,
        List<String> columns,
        List<Map<String, Object>> data
    ) {
    }
}
