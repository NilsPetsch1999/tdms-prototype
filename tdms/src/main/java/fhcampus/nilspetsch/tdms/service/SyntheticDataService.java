package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.CreateSyntheticDatasetRequest;
import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

@Service
public class SyntheticDataService {

    private final SchemaIntrospectionService schemaIntrospectionService;
    private final DatasetStorageService datasetStorageService;
    private final DatasetMetadataService datasetMetadataService;

    public SyntheticDataService(
        SchemaIntrospectionService schemaIntrospectionService,
        DatasetStorageService datasetStorageService,
        DatasetMetadataService datasetMetadataService
    ) {
        this.schemaIntrospectionService = schemaIntrospectionService;
        this.datasetStorageService = datasetStorageService;
        this.datasetMetadataService = datasetMetadataService;
    }

    @Transactional
    public DatasetVersion generate(CreateSyntheticDatasetRequest request) {
        List<SchemaColumnMeta> columns = schemaIntrospectionService.listColumns(request.schemaName(), request.tableName());
        long seed = request.seed() != null ? request.seed() : System.currentTimeMillis();
        Random random = new Random(seed);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < request.rowCount(); i++) {
            rows.add(generateRow(i + 1, columns, random));
        }

        Dataset dataset = datasetMetadataService.getOrCreateDataset(
            request.datasetName(),
            request.description(),
            DatasetSourceType.SYNTHETIC
        );
        int nextVersionNumber = datasetMetadataService.nextVersionNumber(dataset.getId());

        Map<String, Object> generationParameters = new LinkedHashMap<>();
        generationParameters.put("rowCount", request.rowCount());
        generationParameters.put("seed", seed);
        generationParameters.put("strategy", "schema-driven");

        StoredDataFile file = datasetStorageService.storeAsCsv(
            dataset.getId(),
            nextVersionNumber,
            request.schemaName(),
            request.tableName(),
            rows
        );

        return datasetMetadataService.saveVersion(
            dataset,
            nextVersionNumber,
            request.schemaName(),
            request.tableName(),
            request.schemaVersion(),
            generationParameters,
            Map.of(),
            request.createdBy(),
            file
        );
    }

    private Map<String, Object> generateRow(int rowIndex, List<SchemaColumnMeta> columns, Random random) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (SchemaColumnMeta column : columns) {
            if (column.nullable() && random.nextDouble() < 0.05) {
                row.put(column.columnName(), null);
                continue;
            }
            row.put(column.columnName(), generateValue(rowIndex, column, random));
        }
        return row;
    }

    private Object generateValue(int rowIndex, SchemaColumnMeta column, Random random) {
        String dataType = column.dataType().toLowerCase(Locale.ROOT);
        if (column.primaryKey() && isNumericType(dataType)) {
            return rowIndex;
        }
        return switch (dataType) {
            case "int", "integer", "smallint", "mediumint", "tinyint" -> random.nextInt(100_000);
            case "bigint" -> Math.abs(random.nextLong() % 10_000_000L);
            case "decimal", "numeric" -> {
                int scale = column.numericScale() == null ? 2 : column.numericScale();
                double value = random.nextDouble() * 100_000;
                yield BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
            }
            case "float", "double", "real" -> random.nextDouble() * 10_000;
            case "char", "varchar", "text", "longtext", "mediumtext" -> randomString(column.columnName(), column.characterMaxLength(), random);
            case "date" -> LocalDate.now().minusDays(random.nextInt(3650));
            case "datetime", "timestamp" -> LocalDateTime.now().minusSeconds(random.nextInt(365 * 24 * 3600));
            case "time" -> String.format("%02d:%02d:%02d", random.nextInt(24), random.nextInt(60), random.nextInt(60));
            case "boolean", "bit" -> random.nextBoolean();
            case "json" -> "{\"synthetic\":true,\"index\":" + rowIndex + "}";
            default -> randomString(column.columnName(), column.characterMaxLength(), random);
        };
    }

    private boolean isNumericType(String dataType) {
        return dataType.contains("int") || dataType.equals("decimal") || dataType.equals("numeric");
    }

    private String randomString(String prefix, Integer maxLength, Random random) {
        String value = prefix + "_" + Integer.toHexString(random.nextInt()).replace("-", "x");
        if (maxLength != null && maxLength > 0 && value.length() > maxLength) {
            return value.substring(0, maxLength);
        }
        return value;
    }
}
