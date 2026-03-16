package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.domain.FileFormat;
import fhcampus.nilspetsch.tdms.repo.DatasetRepository;
import fhcampus.nilspetsch.tdms.repo.DatasetVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DatasetMetadataService {

    private final DatasetRepository datasetRepository;
    private final DatasetVersionRepository datasetVersionRepository;

    public DatasetMetadataService(
        DatasetRepository datasetRepository,
        DatasetVersionRepository datasetVersionRepository
    ) {
        this.datasetRepository = datasetRepository;
        this.datasetVersionRepository = datasetVersionRepository;
    }

    @Transactional
    public Dataset getOrCreateDataset(
        String datasetName,
        String description,
        DatasetSourceType sourceType
    ) {
        return datasetRepository.findByName(datasetName)
            .map(existing -> {
                if (existing.getSourceType() != sourceType) {
                    throw new IllegalArgumentException("Dataset source type mismatch for dataset '" + datasetName + "'");
                }
                if (description != null && !description.isBlank()) {
                    existing.setDescription(description);
                }
                return existing;
            })
            .orElseGet(() -> {
                Dataset newDataset = new Dataset();
                newDataset.setName(datasetName);
                newDataset.setDescription(description);
                newDataset.setSourceType(sourceType);
                return datasetRepository.save(newDataset);
            });
    }

    public int nextVersionNumber(long datasetId) {
        return datasetVersionRepository.findTopByDatasetIdOrderByVersionNumberDesc(datasetId)
            .map(v -> v.getVersionNumber() + 1)
            .orElse(1);
    }

    @Transactional
    public DatasetVersion saveVersion(
        Dataset dataset,
        int versionNumber,
        String schemaName,
        String tableName,
        String schemaVersion,
        Map<String, Object> generationParameters,
        Map<String, Object> maskingRules,
        String createdBy,
        FileFormat fileFormat,
        StoredDataFile storedDataFile
    ) {
        DatasetVersion version = new DatasetVersion();
        version.setDataset(dataset);
        version.setVersionNumber(versionNumber);
        version.setSchemaName(schemaName);
        version.setTableName(tableName);
        version.setSchemaVersion(schemaVersion);
        version.setGenerationParametersJson(toJson(generationParameters));
        version.setMaskingRulesJson(toJson(maskingRules));
        version.setFileFormat(fileFormat);
        version.setStoragePath(storedDataFile.relativePath());
        version.setChecksumSha256(storedDataFile.checksumSha256());
        version.setRowCount(storedDataFile.rowCount());
        version.setCreatedBy(createdBy);
        return datasetVersionRepository.save(version);
    }

    public List<Dataset> listDatasets() {
        return datasetRepository.findAll();
    }

    public List<DatasetVersion> listVersions(long datasetId) {
        return datasetVersionRepository.findByDatasetIdOrderByVersionNumberDesc(datasetId);
    }

    public DatasetVersion getVersion(long datasetId, int versionNumber) {
        return datasetVersionRepository.findByDatasetIdAndVersionNumber(datasetId, versionNumber)
            .orElseThrow(() -> new IllegalArgumentException("Dataset version not found."));
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return toJsonValue(value);
    }

    private String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .map(entry -> "\"" + escape(String.valueOf(entry.getKey())) + "\":" + toJsonValue(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(",");
                }
                builder.append(toJsonValue(item));
                first = false;
            }
            builder.append("]");
            return builder.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String escape(String raw) {
        return raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }
}
