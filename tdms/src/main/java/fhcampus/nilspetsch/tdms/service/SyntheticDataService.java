package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.CreateSyntheticDatasetRequest;
import fhcampus.nilspetsch.tdms.domain.Dataset;
import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetVersion;
import fhcampus.nilspetsch.tdms.domain.FileFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

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
        long seed = request.seed() != null ? request.seed() : System.currentTimeMillis();
        Random random = new Random(seed);
        Set<String> excludedTables = sanitizeNames(request.excludedTableNames());

        Set<String> explicitTargets = resolveTargetTables(request);
        List<ForeignKeyMeta> allForeignKeys = schemaIntrospectionService.listForeignKeys(request.schemaName());
        Set<String> allSchemaTables = new LinkedHashSet<>(schemaIntrospectionService.listTables(request.schemaName()));

        Set<String> selectedTables = expandTablesWithRelations(explicitTargets, allForeignKeys, request.includeRelatedTables(), request.generateWholeSchema(), allSchemaTables, excludedTables);
        if (selectedTables.isEmpty()) {
            throw new IllegalArgumentException("No target tables selected for synthetic generation.");
        }

        List<String> generationOrder = topologicalOrder(selectedTables, allForeignKeys);
        Map<String, List<SchemaColumnMeta>> columnsByTable = new LinkedHashMap<>();
        Map<String, List<String>> pkColumnsByTable = new LinkedHashMap<>();
        for (String table : generationOrder) {
            columnsByTable.put(table, schemaIntrospectionService.listColumns(request.schemaName(), table));
            pkColumnsByTable.put(table, schemaIntrospectionService.listPrimaryKeyColumns(request.schemaName(), table));
        }

        Map<String, List<Map<String, Object>>> rowsByTable = new LinkedHashMap<>();
        Map<String, List<Object>> pkValuePool = new HashMap<>();

        for (String table : generationOrder) {
            List<SchemaColumnMeta> columns = columnsByTable.get(table);
            List<Map<String, Object>> rows = generateTableRows(
                request,
                table,
                columns,
                allForeignKeys,
                pkColumnsByTable,
                pkValuePool,
                random,
                selectedTables.contains(table) && explicitTargets.contains(table),
                Boolean.TRUE.equals(request.generateWholeSchema())
            );
            rowsByTable.put(table, rows);
            cachePrimaryKeys(table, rows, pkColumnsByTable.get(table), pkValuePool);
        }

        Dataset dataset = datasetMetadataService.getOrCreateDataset(
            request.datasetName(),
            request.description(),
            DatasetSourceType.SYNTHETIC
        );
        int nextVersionNumber = datasetMetadataService.nextVersionNumber(dataset.getId());

        StoredDataFile file;
        FileFormat format;
        String tableNameLabel;
        if (rowsByTable.size() == 1) {
            String table = generationOrder.getFirst();
            file = datasetStorageService.storeAsCsv(
                dataset.getId(),
                nextVersionNumber,
                request.schemaName(),
                table,
                rowsByTable.get(table)
            );
            format = FileFormat.CSV;
            tableNameLabel = table;
        } else {
            file = datasetStorageService.storeAsZipOfCsv(
                dataset.getId(),
                nextVersionNumber,
                request.schemaName(),
                rowsByTable
            );
            format = FileFormat.ZIP;
            tableNameLabel = "MULTI_TABLE";
        }

        Map<String, Object> generationParameters = new LinkedHashMap<>();
        generationParameters.put("rowCount", request.rowCount());
        generationParameters.put("seed", seed);
        generationParameters.put("strategy", "schema-driven-relational");
        generationParameters.put("generateWholeSchema", Boolean.TRUE.equals(request.generateWholeSchema()));
        generationParameters.put("includeRelatedTables", isTrueOrNull(request.includeRelatedTables(), true));
        generationParameters.put("useExistingParentKeys", isTrueOrNull(request.useExistingParentKeys(), true));
        generationParameters.put("defaultRelatedTableRowCount", request.defaultRelatedTableRowCount());
        generationParameters.put("nullableFieldProbability", request.nullableFieldProbability());
        generationParameters.put("rowCountByTable", request.rowCountByTable());
        generationParameters.put("excludedTableNames", request.excludedTableNames());
        generationParameters.put("tables", String.join(",", generationOrder));
        generationParameters.put("foreignKeyCount", allForeignKeys.stream().filter(fk -> selectedTables.contains(fk.childTable()) && selectedTables.contains(fk.parentTable())).count());

        return datasetMetadataService.saveVersion(
            dataset,
            nextVersionNumber,
            request.schemaName(),
            tableNameLabel,
            request.schemaVersion(),
            generationParameters,
            Map.of(),
            request.createdBy(),
            format,
            file
        );
    }

    private Set<String> resolveTargetTables(CreateSyntheticDatasetRequest request) {
        Set<String> excluded = sanitizeNames(request.excludedTableNames());
        if (Boolean.TRUE.equals(request.generateWholeSchema())) {
            Set<String> tables = new LinkedHashSet<>(schemaIntrospectionService.listTables(request.schemaName()));
            tables.removeAll(excluded);
            return tables;
        }
        Set<String> tables = new LinkedHashSet<>();
        if (request.tableName() != null && !request.tableName().isBlank()) {
            tables.add(request.tableName().trim());
        }
        tables.addAll(sanitizeNames(request.tableNames()));
        tables.removeAll(excluded);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("Provide tableName, tableNames or set generateWholeSchema=true.");
        }
        return tables;
    }

    private Set<String> expandTablesWithRelations(
        Set<String> baseTables,
        List<ForeignKeyMeta> foreignKeys,
        Boolean includeRelatedTables,
        Boolean generateWholeSchema,
        Set<String> allSchemaTables,
        Set<String> excluded
    ) {
        if (Boolean.TRUE.equals(generateWholeSchema)) {
            Set<String> tables = new LinkedHashSet<>(allSchemaTables);
            tables.removeAll(excluded);
            return tables;
        }
        Set<String> selected = new LinkedHashSet<>(baseTables);
        if (!isTrueOrNull(includeRelatedTables, true)) {
            return selected;
        }
        boolean changed;
        do {
            changed = false;
            for (ForeignKeyMeta fk : foreignKeys) {
                if (selected.contains(fk.childTable()) && !selected.contains(fk.parentTable()) && !excluded.contains(fk.parentTable())) {
                    selected.add(fk.parentTable());
                    changed = true;
                }
            }
        } while (changed);
        return selected;
    }

    private Set<String> sanitizeNames(List<String> values) {
        Set<String> names = new LinkedHashSet<>();
        if (values == null) {
            return names;
        }
        values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .forEach(names::add);
        return names;
    }

    private List<String> topologicalOrder(Set<String> tables, List<ForeignKeyMeta> allForeignKeys) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        tables.forEach(table -> {
            inDegree.put(table, 0);
            edges.put(table, new ArrayList<>());
        });

        for (ForeignKeyMeta fk : allForeignKeys) {
            if (!tables.contains(fk.childTable()) || !tables.contains(fk.parentTable()) || fk.parentTable().equals(fk.childTable())) {
                continue;
            }
            edges.get(fk.parentTable()).add(fk.childTable());
            inDegree.put(fk.childTable(), inDegree.get(fk.childTable()) + 1);
        }

        Queue<String> queue = new ArrayDeque<>();
        inDegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey)
            .sorted()
            .forEach(queue::offer);

        List<String> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String table = queue.poll();
            ordered.add(table);
            for (String child : edges.getOrDefault(table, List.of())) {
                int next = inDegree.get(child) - 1;
                inDegree.put(child, next);
                if (next == 0) {
                    queue.offer(child);
                }
            }
        }

        if (ordered.size() != tables.size()) {
            tables.stream().filter(table -> !ordered.contains(table)).sorted().forEach(ordered::add);
        }
        return ordered;
    }

    private List<Map<String, Object>> generateTableRows(
        CreateSyntheticDatasetRequest request,
        String table,
        List<SchemaColumnMeta> columns,
        List<ForeignKeyMeta> allForeignKeys,
        Map<String, List<String>> pkColumnsByTable,
        Map<String, List<Object>> pkValuePool,
        Random random,
        boolean explicitTarget,
        boolean wholeSchemaMode
    ) {
        int targetRows = resolveTargetRows(table, request, explicitTarget, wholeSchemaMode);
        Map<String, ForeignKeyMeta> fkByColumn = new HashMap<>();
        allForeignKeys.stream()
            .filter(fk -> fk.childTable().equals(table))
            .forEach(fk -> fkByColumn.put(fk.childColumn(), fk));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < targetRows; i++) {
            rows.add(generateRow(i + 1, columns, fkByColumn, pkColumnsByTable, pkValuePool, request, random));
        }
        return rows;
    }

    private Map<String, Object> generateRow(
        int rowIndex,
        List<SchemaColumnMeta> columns,
        Map<String, ForeignKeyMeta> fkByColumn,
        Map<String, List<String>> pkColumnsByTable,
        Map<String, List<Object>> pkValuePool,
        CreateSyntheticDatasetRequest request,
        Random random
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (SchemaColumnMeta column : columns) {
            ForeignKeyMeta fk = fkByColumn.get(column.columnName());
            if (fk != null) {
                Object fkValue = resolveForeignKeyValue(fk, pkColumnsByTable, pkValuePool, request, random);
                row.put(column.columnName(), fkValue);
                continue;
            }

            if (column.nullable() && random.nextDouble() < nullableProbability(request)) {
                row.put(column.columnName(), null);
                continue;
            }
            row.put(column.columnName(), generateValue(rowIndex, column, random));
        }
        return row;
    }

    private Object resolveForeignKeyValue(
        ForeignKeyMeta fk,
        Map<String, List<String>> pkColumnsByTable,
        Map<String, List<Object>> pkValuePool,
        CreateSyntheticDatasetRequest request,
        Random random
    ) {
        List<String> parentPkColumns = pkColumnsByTable.getOrDefault(fk.parentTable(), List.of());
        if (parentPkColumns.size() != 1 || !parentPkColumns.contains(fk.parentColumn())) {
            return null;
        }

        List<Object> pool = pkValuePool.computeIfAbsent(fk.parentTable(), ignored -> new ArrayList<>());
        if (pool.isEmpty() && isTrueOrNull(request.useExistingParentKeys(), true)) {
            pool.addAll(schemaIntrospectionService.listDistinctColumnValues(
                request.schemaName(),
                fk.parentTable(),
                fk.parentColumn(),
                Math.max(1000, request.rowCount())
            ));
        }
        if (pool.isEmpty()) {
            pool.add(1);
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private void cachePrimaryKeys(
        String table,
        List<Map<String, Object>> rows,
        List<String> pkColumns,
        Map<String, List<Object>> pkValuePool
    ) {
        if (pkColumns == null || pkColumns.size() != 1) {
            return;
        }
        String pk = pkColumns.getFirst();
        List<Object> keys = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(pk);
            if (value != null) {
                keys.add(value);
            }
        }
        if (!keys.isEmpty()) {
            pkValuePool.put(table, keys);
        }
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

    private boolean isTrueOrNull(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private int resolveTargetRows(
        String table,
        CreateSyntheticDatasetRequest request,
        boolean explicitTarget,
        boolean wholeSchemaMode
    ) {
        if (request.rowCountByTable() != null && request.rowCountByTable().containsKey(table)) {
            Integer configured = request.rowCountByTable().get(table);
            if (configured != null && configured > 0) {
                return configured;
            }
        }
        if (wholeSchemaMode) {
            return request.rowCount();
        }
        if (explicitTarget) {
            return request.rowCount();
        }
        if (request.defaultRelatedTableRowCount() != null && request.defaultRelatedTableRowCount() > 0) {
            return request.defaultRelatedTableRowCount();
        }
        return Math.max(1, request.rowCount() / 3);
    }

    private double nullableProbability(CreateSyntheticDatasetRequest request) {
        if (request.nullableFieldProbability() == null) {
            return 0.05d;
        }
        return Math.max(0d, Math.min(1d, request.nullableFieldProbability()));
    }
}
