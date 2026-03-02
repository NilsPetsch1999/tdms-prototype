package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.CreateMaskedDatasetRequest;
import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.util.HashUtil;
import fhcampus.nilspetsch.tdms.util.IdentifierValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MaskingDataService {

    private static final String PSEUDO_SALT = "tdms-prototype";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaIntrospectionService schemaIntrospectionService;
    private final DatasetStorageService datasetStorageService;
    private final DatasetMetadataService datasetMetadataService;

    public MaskingDataService(
        JdbcTemplate jdbcTemplate,
        SchemaIntrospectionService schemaIntrospectionService,
        DatasetStorageService datasetStorageService,
        DatasetMetadataService datasetMetadataService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIntrospectionService = schemaIntrospectionService;
        this.datasetStorageService = datasetStorageService;
        this.datasetMetadataService = datasetMetadataService;
    }

    @Transactional
    public DatasetVersion mask(CreateMaskedDatasetRequest request) {
        IdentifierValidator.requireValid(request.schemaName(), "schemaName");
        IdentifierValidator.requireValid(request.tableName(), "tableName");
        schemaIntrospectionService.listColumns(request.schemaName(), request.tableName());

        String selectSql = "SELECT * FROM `" + request.schemaName() + "`.`" + request.tableName() + "` LIMIT ?";
        List<Map<String, Object>> sourceRows = jdbcTemplate.queryForList(selectSql, request.rowLimit());
        List<Map<String, Object>> maskedRows = sourceRows.stream().map(row -> maskRow(row, request.maskingRules())).toList();

        Dataset dataset = datasetMetadataService.getOrCreateDataset(
            request.datasetName(),
            request.description(),
            DatasetSourceType.MASKED_REAL
        );
        int nextVersion = datasetMetadataService.nextVersionNumber(dataset.getId());

        StoredDataFile file = datasetStorageService.storeAsCsv(
            dataset.getId(),
            nextVersion,
            request.schemaName(),
            request.tableName(),
            maskedRows
        );

        Map<String, Object> generationParams = new LinkedHashMap<>();
        generationParams.put("rowLimit", request.rowLimit());
        generationParams.put("source", "masked-real");

        Map<String, Object> masking = new LinkedHashMap<>();
        request.maskingRules().forEach((k, v) -> masking.put(k, v.name()));

        return datasetMetadataService.saveVersion(
            dataset,
            nextVersion,
            request.schemaName(),
            request.tableName(),
            request.schemaVersion(),
            generationParams,
            masking,
            request.createdBy(),
            file
        );
    }

    private Map<String, Object> maskRow(Map<String, Object> source, Map<String, MaskingTechnique> rules) {
        Map<String, Object> masked = new LinkedHashMap<>();
        source.forEach((column, value) -> {
            MaskingTechnique technique = rules.get(column);
            masked.put(column, applyTechnique(value, technique));
        });
        return masked;
    }

    private Object applyTechnique(Object value, MaskingTechnique technique) {
        if (value == null || technique == null) {
            return value;
        }
        return switch (technique) {
            case SUBSTITUTION -> substitute(value);
            case PSEUDONYMIZATION -> "pseudo_" + HashUtil.sha256Hex(PSEUDO_SALT + "|" + value).substring(0, 16);
            case TOKENIZATION -> "tok_" + UUID.nameUUIDFromBytes(String.valueOf(value).getBytes()).toString().substring(0, 12);
            case HASHING -> HashUtil.sha256Hex(String.valueOf(value));
            case GENERALIZATION -> generalize(value);
        };
    }

    private Object substitute(Object value) {
        if (value instanceof Number) {
            return 0;
        }
        if (value instanceof LocalDate) {
            return LocalDate.of(2000, 1, 1);
        }
        if (value instanceof LocalDateTime) {
            return LocalDateTime.of(2000, 1, 1, 0, 0, 0);
        }
        return "masked_value";
    }

    private Object generalize(Object value) {
        if (value instanceof Number number) {
            int n = number.intValue();
            int low = (n / 10) * 10;
            return low + "-" + (low + 9);
        }
        if (value instanceof LocalDate date) {
            return date.getYear();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.truncatedTo(ChronoUnit.DAYS).toString();
        }
        String str = String.valueOf(value);
        if (str.length() <= 2) {
            return "**";
        }
        return str.substring(0, 2) + "***";
    }
}
