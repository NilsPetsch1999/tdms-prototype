package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.api.CreateSyntheticDatasetRequest;
import fhcampus.nilspetsch.tdms.api.SyntheticColumnRule;
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
import java.util.Arrays;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SyntheticDataService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern SUM_AGGREGATE_PATTERN = Pattern.compile(
        "SUM\\(\\s*([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)\\s+BY\\s+([a-zA-Z0-9_]+)\\s*\\)",
        Pattern.CASE_INSENSITIVE
    );
    private static final List<String> FIRST_NAMES = List.of("Nina", "Lukas", "Elena", "Sophie", "Jonas", "Mia", "David", "Lea");
    private static final List<String> LAST_NAMES = List.of("Huber", "Mayer", "Wagner", "Gruber", "Pichler", "Steiner", "Bauer", "Hofer");
    private static final List<String> CITIES = List.of("Vienna", "Linz", "Graz", "Salzburg", "Innsbruck");
    private static final List<String> STATUSES = List.of("NEW", "PROCESSING", "PAID", "SHIPPED", "COMPLETED");
    private static final List<String> PAYMENT_METHODS = List.of("CREDIT_CARD", "PAYPAL", "INVOICE", "BANK_TRANSFER");
    private static final List<String> SEGMENTS = List.of("RETAIL", "BUSINESS", "ENTERPRISE");
    private static final List<String> CATEGORIES = List.of("Electronics", "Office", "Accessories", "Software");
    private static final List<String> BRANDS = List.of("Nimbus", "Vista", "Orbit", "NorthSeat", "CoreLine");

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

        applyDerivedRelationships(rowsByTable, columnsByTable, allForeignKeys, pkColumnsByTable, request);

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
        generationParameters.put("columnRulesByTable", request.columnRulesByTable());
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
            rows.add(generateRow(table, i + 1, columns, fkByColumn, pkColumnsByTable, pkValuePool, request, random));
        }
        return rows;
    }

    private Map<String, Object> generateRow(
        String table,
        int rowIndex,
        List<SchemaColumnMeta> columns,
        Map<String, ForeignKeyMeta> fkByColumn,
        Map<String, List<String>> pkColumnsByTable,
        Map<String, List<Object>> pkValuePool,
        CreateSyntheticDatasetRequest request,
        Random random
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, SyntheticColumnRule> rules = rulesForTable(request, table);
        List<DeferredRule> deferredRules = new ArrayList<>();
        for (SchemaColumnMeta column : columns) {
            SyntheticColumnRule rule = rules.get(column.columnName());
            if (hasDeferredRule(rule)) {
                deferredRules.add(new DeferredRule(column, rule));
                continue;
            }
            if (hasExplicitRule(rule)) {
                row.put(column.columnName(), applyRule(rule, column, rowIndex, row, random));
                continue;
            }

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
            row.put(column.columnName(), generateValue(rowIndex, column, row, random));
        }

        for (DeferredRule deferredRule : deferredRules) {
            row.put(
                deferredRule.column().columnName(),
                applyRule(deferredRule.rule(), deferredRule.column(), rowIndex, row, random)
            );
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

    private Object generateValue(int rowIndex, SchemaColumnMeta column, Map<String, Object> row, Random random) {
        String columnName = column.columnName().toLowerCase(Locale.ROOT);
        String dataType = column.dataType().toLowerCase(Locale.ROOT);
        if (column.primaryKey() && isNumericType(dataType)) {
            return rowIndex;
        }
        if (columnName.equals("first_name")) {
            return pick(FIRST_NAMES, random);
        }
        if (columnName.equals("last_name")) {
            return pick(LAST_NAMES, random);
        }
        if (columnName.equals("email")) {
            Object firstName = row.get("first_name");
            Object lastName = row.get("last_name");
            if (firstName != null && lastName != null) {
                return (firstName.toString() + "." + lastName.toString()).toLowerCase(Locale.ROOT) + "@example.org";
            }
        }
        if (columnName.equals("city")) {
            return pick(CITIES, random);
        }
        if (columnName.equals("country_code")) {
            return "AT";
        }
        if (columnName.equals("segment")) {
            return pick(SEGMENTS, random);
        }
        if (columnName.equals("status")) {
            return pick(STATUSES, random);
        }
        if (columnName.equals("payment_method")) {
            return pick(PAYMENT_METHODS, random);
        }
        if (columnName.equals("category")) {
            return pick(CATEGORIES, random);
        }
        if (columnName.equals("brand")) {
            return pick(BRANDS, random);
        }
        if (columnName.equals("order_number")) {
            return "ORD-" + String.format("%05d", rowIndex);
        }
        if (columnName.equals("customer_number")) {
            return "CUST-" + String.format("%04d", rowIndex);
        }
        if (columnName.equals("sku")) {
            return "SKU-" + String.format("%05d", rowIndex);
        }
        if (columnName.equals("phone")) {
            return "+43 6" + (10000000 + random.nextInt(90000000));
        }
        if (columnName.equals("postal_code")) {
            return String.valueOf(1000 + random.nextInt(8000));
        }
        return switch (dataType) {
            case "int", "integer", "smallint", "mediumint", "tinyint" -> {
                if (columnName.contains("quantity")) {
                    yield 1 + random.nextInt(10);
                }
                if (columnName.contains("stock")) {
                    yield 5 + random.nextInt(500);
                }
                yield random.nextInt(100_000);
            }
            case "bigint" -> Math.abs(random.nextLong() % 10_000_000L);
            case "decimal", "numeric" -> {
                int scale = column.numericScale() == null ? 2 : column.numericScale();
                double value;
                if (columnName.contains("unit_price")) {
                    value = 10 + (random.nextDouble() * 1_990);
                } else if (columnName.contains("shipping")) {
                    value = random.nextBoolean() ? 0 : 4.9 + (random.nextInt(5) * 5);
                } else {
                    value = random.nextDouble() * 100_000;
                }
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

    private void applyDerivedRelationships(
        Map<String, List<Map<String, Object>>> rowsByTable,
        Map<String, List<SchemaColumnMeta>> columnsByTable,
        List<ForeignKeyMeta> foreignKeys,
        Map<String, List<String>> pkColumnsByTable,
        CreateSyntheticDatasetRequest request
    ) {
        applyConfiguredAggregates(rowsByTable, pkColumnsByTable, foreignKeys, request);

        for (Map.Entry<String, List<Map<String, Object>>> entry : rowsByTable.entrySet()) {
            String table = entry.getKey();
            Set<String> columns = columnNames(columnsByTable.get(table));
            for (Map<String, Object> row : entry.getValue()) {
                if (columns.contains("quantity") && columns.contains("unit_price")) {
                    BigDecimal lineTotal = multiply(row.get("quantity"), row.get("unit_price"));
                    if (lineTotal != null) {
                        if (columns.contains("line_total") && !hasExplicitRule(request, table, "line_total")) {
                            row.put("line_total", lineTotal);
                        }
                        if (columns.contains("total_price") && !hasExplicitRule(request, table, "total_price")) {
                            row.put("total_price", lineTotal);
                        }
                    }
                }
            }
        }

        for (ForeignKeyMeta fk : foreignKeys) {
            List<Map<String, Object>> childRows = rowsByTable.get(fk.childTable());
            List<Map<String, Object>> parentRows = rowsByTable.get(fk.parentTable());
            List<String> parentPkColumns = pkColumnsByTable.getOrDefault(fk.parentTable(), List.of());
            if (childRows == null || parentRows == null || parentPkColumns.size() != 1 || !parentPkColumns.contains(fk.parentColumn())) {
                continue;
            }

            Map<Object, BigDecimal> childTotalsByParent = new HashMap<>();
            for (Map<String, Object> childRow : childRows) {
                BigDecimal childAmount = firstNonNullAmount(childRow, "line_total", "total_price", "amount");
                Object parentId = childRow.get(fk.childColumn());
                if (parentId != null && childAmount != null) {
                    childTotalsByParent.merge(parentId, childAmount, BigDecimal::add);
                }
            }

            Set<String> parentColumns = columnNames(columnsByTable.get(fk.parentTable()));
            for (Map<String, Object> parentRow : parentRows) {
                Object parentId = parentRow.get(fk.parentColumn());
                BigDecimal subtotal = childTotalsByParent.get(parentId);
                if (subtotal == null) {
                    continue;
                }
                if (parentColumns.contains("subtotal_amount") && !hasExplicitRule(request, fk.parentTable(), "subtotal_amount")) {
                    parentRow.put("subtotal_amount", subtotal);
                }
                if (parentColumns.contains("total_amount") && !hasExplicitRule(request, fk.parentTable(), "total_amount")) {
                    BigDecimal shipping = toBigDecimal(parentRow.get("shipping_amount"));
                    parentRow.put("total_amount", subtotal.add(shipping == null ? BigDecimal.ZERO : shipping));
                }
            }
        }
    }

    private void applyConfiguredAggregates(
        Map<String, List<Map<String, Object>>> rowsByTable,
        Map<String, List<String>> pkColumnsByTable,
        List<ForeignKeyMeta> foreignKeys,
        CreateSyntheticDatasetRequest request
    ) {
        if (request.columnRulesByTable() == null || request.columnRulesByTable().isEmpty()) {
            return;
        }

        for (Map.Entry<String, Map<String, SyntheticColumnRule>> tableEntry : request.columnRulesByTable().entrySet()) {
            String parentTable = tableEntry.getKey();
            List<Map<String, Object>> parentRows = rowsByTable.get(parentTable);
            List<String> parentPkColumns = pkColumnsByTable.getOrDefault(parentTable, List.of());
            if (parentRows == null || parentPkColumns.size() != 1) {
                continue;
            }

            String parentPkColumn = parentPkColumns.getFirst();
            for (Map.Entry<String, SyntheticColumnRule> columnEntry : tableEntry.getValue().entrySet()) {
                SyntheticColumnRule rule = columnEntry.getValue();
                if (rule == null || rule.strategy() == null || !"AGGREGATE".equalsIgnoreCase(rule.strategy())) {
                    continue;
                }

                AggregateRule aggregateRule = parseAggregateRule(rule.config(), parentTable, columnEntry.getKey());
                ForeignKeyMeta matchingForeignKey = foreignKeys.stream()
                    .filter(fk -> fk.childTable().equals(aggregateRule.childTable()))
                    .filter(fk -> fk.childColumn().equals(aggregateRule.groupByColumn()))
                    .filter(fk -> fk.parentTable().equals(parentTable))
                    .filter(fk -> fk.parentColumn().equals(parentPkColumn))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "No matching foreign key found for aggregate rule on " + parentTable + "." + columnEntry.getKey()
                    ));

                List<Map<String, Object>> childRows = rowsByTable.get(aggregateRule.childTable());
                if (childRows == null) {
                    continue;
                }

                Map<Object, BigDecimal> sumsByParent = new HashMap<>();
                for (Map<String, Object> childRow : childRows) {
                    Object parentReference = childRow.get(matchingForeignKey.childColumn());
                    BigDecimal amount = toBigDecimal(childRow.get(aggregateRule.valueColumn()));
                    if (parentReference != null && amount != null) {
                        sumsByParent.merge(parentReference, amount, BigDecimal::add);
                    }
                }

                for (Map<String, Object> parentRow : parentRows) {
                    Object parentId = parentRow.get(parentPkColumn);
                    BigDecimal sum = sumsByParent.get(parentId);
                    if (sum != null) {
                        parentRow.put(columnEntry.getKey(), sum.setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
        }
    }

    private Set<String> columnNames(List<SchemaColumnMeta> columns) {
        Set<String> names = new LinkedHashSet<>();
        if (columns == null) {
            return names;
        }
        columns.forEach(column -> names.add(column.columnName()));
        return names;
    }

    private boolean hasExplicitRule(CreateSyntheticDatasetRequest request, String table, String columnName) {
        return hasExplicitRule(rulesForTable(request, table).get(columnName));
    }

    private Map<String, SyntheticColumnRule> rulesForTable(CreateSyntheticDatasetRequest request, String table) {
        if (request.columnRulesByTable() == null) {
            return Map.of();
        }
        Map<String, SyntheticColumnRule> rules = request.columnRulesByTable().get(table);
        return rules == null ? Map.of() : rules;
    }

    private boolean hasExplicitRule(SyntheticColumnRule rule) {
        return rule != null && rule.strategy() != null && !rule.strategy().isBlank() && !"DEFAULT".equalsIgnoreCase(rule.strategy());
    }

    private boolean hasDeferredRule(SyntheticColumnRule rule) {
        if (!hasExplicitRule(rule)) {
            return false;
        }
        return "EXPRESSION".equalsIgnoreCase(rule.strategy())
            || "AGGREGATE".equalsIgnoreCase(rule.strategy())
            || ("TEMPLATE".equalsIgnoreCase(rule.strategy()) && rule.config() != null && rule.config().contains("${"));
    }

    private Object applyRule(
        SyntheticColumnRule rule,
        SchemaColumnMeta column,
        int rowIndex,
        Map<String, Object> row,
        Random random
    ) {
        String strategy = rule.strategy().trim().toUpperCase(Locale.ROOT);
        String config = rule.config() == null ? "" : rule.config().trim();
        return switch (strategy) {
            case "FIXED" -> convertToColumnType(column, config);
            case "CHOICE" -> convertToColumnType(column, pick(splitConfigValues(config), random));
            case "RANGE" -> generateRangedValue(column, config, random);
            case "TEMPLATE" -> convertToColumnType(column, resolveTemplate(config, rowIndex, row));
            case "EXPRESSION" -> evaluateExpression(config, column, row);
            case "AGGREGATE" -> null;
            default -> generateValue(rowIndex, column, row, random);
        };
    }

    private AggregateRule parseAggregateRule(String config, String parentTable, String parentColumn) {
        if (config == null || config.isBlank()) {
            throw new IllegalArgumentException("Aggregate rule is missing config for " + parentTable + "." + parentColumn);
        }
        Matcher matcher = SUM_AGGREGATE_PATTERN.matcher(config.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid aggregate rule for " + parentTable + "." + parentColumn
                    + ". Expected format: SUM(child_table.amount_column BY child_fk_column)"
            );
        }
        return new AggregateRule(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private List<String> splitConfigValues(String config) {
        return Arrays.stream(config.split("\\|"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private Object generateRangedValue(SchemaColumnMeta column, String config, Random random) {
        List<String> values = splitConfigValues(config);
        if (values.size() != 2) {
            throw new IllegalArgumentException("RANGE rule must use 'min|max' for column " + column.columnName());
        }
        String dataType = column.dataType().toLowerCase(Locale.ROOT);
        if (dataType.equals("date")) {
            LocalDate min = LocalDate.parse(values.getFirst());
            LocalDate max = LocalDate.parse(values.get(1));
            long days = Math.max(0, max.toEpochDay() - min.toEpochDay());
            return min.plusDays(days == 0 ? 0 : random.nextLong(days + 1));
        }
        if (dataType.equals("datetime") || dataType.equals("timestamp")) {
            LocalDateTime min = LocalDateTime.parse(values.getFirst());
            LocalDateTime max = LocalDateTime.parse(values.get(1));
            long seconds = Math.max(0, java.time.Duration.between(min, max).getSeconds());
            return min.plusSeconds(seconds == 0 ? 0 : random.nextLong(seconds + 1));
        }
        BigDecimal min = new BigDecimal(values.getFirst());
        BigDecimal max = new BigDecimal(values.get(1));
        if (isIntegerLike(dataType)) {
            long low = min.longValue();
            long high = max.longValue();
            long bound = Math.max(0, high - low);
            long value = low + (bound == 0 ? 0 : random.nextLong(bound + 1));
            if (dataType.equals("bigint")) {
                return value;
            }
            return (int) value;
        }
        BigDecimal delta = max.subtract(min);
        BigDecimal scaled = min.add(delta.multiply(BigDecimal.valueOf(random.nextDouble())));
        int scale = column.numericScale() == null ? 2 : column.numericScale();
        return scaled.setScale(scale, RoundingMode.HALF_UP);
    }

    private Object evaluateExpression(String expression, SchemaColumnMeta column, Map<String, Object> row) {
        BigDecimal value = new ArithmeticParser(resolveNumericExpression(expression, row)).parse();
        return castNumericValue(value, column);
    }

    private String resolveTemplate(String template, int rowIndex, Map<String, Object> row) {
        String resolved = template.replace("${rowIndex}", String.valueOf(rowIndex));
        Matcher matcher = VARIABLE_PATTERN.matcher(resolved);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            Object value = row.get(variable);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String resolveNumericExpression(String expression, Map<String, Object> row) {
        Matcher matcher = VARIABLE_PATTERN.matcher(expression);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1).trim();
            Object value = row.get(variable);
            if (value == null) {
                throw new IllegalArgumentException("Expression references missing value for column: " + variable);
            }
            BigDecimal numericValue = toBigDecimal(value);
            if (numericValue == null) {
                throw new IllegalArgumentException("Expression column must be numeric: " + variable);
            }
            matcher.appendReplacement(builder, numericValue.stripTrailingZeros().toPlainString());
        }
        matcher.appendTail(builder);
        return builder.toString().replaceAll("\\s+", "");
    }

    private Object convertToColumnType(SchemaColumnMeta column, String value) {
        if (value == null) {
            return null;
        }
        String dataType = column.dataType().toLowerCase(Locale.ROOT);
        return switch (dataType) {
            case "int", "integer", "smallint", "mediumint", "tinyint" -> Integer.parseInt(value);
            case "bigint" -> Long.parseLong(value);
            case "decimal", "numeric" -> new BigDecimal(value).setScale(column.numericScale() == null ? 2 : column.numericScale(), RoundingMode.HALF_UP);
            case "float", "double", "real" -> Double.parseDouble(value);
            case "date" -> LocalDate.parse(value);
            case "datetime", "timestamp" -> LocalDateTime.parse(value);
            case "boolean", "bit" -> Boolean.parseBoolean(value);
            default -> trimToMaxLength(value, column.characterMaxLength());
        };
    }

    private Object castNumericValue(BigDecimal value, SchemaColumnMeta column) {
        String dataType = column.dataType().toLowerCase(Locale.ROOT);
        return switch (dataType) {
            case "int", "integer", "smallint", "mediumint", "tinyint" -> value.intValue();
            case "bigint" -> value.longValue();
            case "decimal", "numeric" -> value.setScale(column.numericScale() == null ? 2 : column.numericScale(), RoundingMode.HALF_UP);
            case "float", "double", "real" -> value.doubleValue();
            default -> trimToMaxLength(value.stripTrailingZeros().toPlainString(), column.characterMaxLength());
        };
    }

    private BigDecimal multiply(Object left, Object right) {
        BigDecimal first = toBigDecimal(left);
        BigDecimal second = toBigDecimal(right);
        if (first == null || second == null) {
            return null;
        }
        return first.multiply(second).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal firstNonNullAmount(Map<String, Object> row, String... columnNames) {
        for (String columnName : columnNames) {
            BigDecimal value = toBigDecimal(row.get(columnName));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String string && !string.isBlank()) {
            return new BigDecimal(string);
        }
        return null;
    }

    private boolean isNumericType(String dataType) {
        return dataType.contains("int") || dataType.equals("decimal") || dataType.equals("numeric");
    }

    private boolean isIntegerLike(String dataType) {
        return dataType.contains("int");
    }

    private String trimToMaxLength(String value, Integer maxLength) {
        if (maxLength != null && maxLength > 0 && value.length() > maxLength) {
            return value.substring(0, maxLength);
        }
        return value;
    }

    private String randomString(String prefix, Integer maxLength, Random random) {
        String value = prefix + "_" + Integer.toHexString(random.nextInt()).replace("-", "x");
        return trimToMaxLength(value, maxLength);
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

    private <T> T pick(List<T> values, Random random) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(random.nextInt(values.size()));
    }

    private record DeferredRule(
        SchemaColumnMeta column,
        SyntheticColumnRule rule
    ) {
    }

    private record AggregateRule(
        String childTable,
        String valueColumn,
        String groupByColumn
    ) {
    }

    private static final class ArithmeticParser {
        private final String input;
        private int index;

        private ArithmeticParser(String input) {
            this.input = input;
        }

        private BigDecimal parse() {
            BigDecimal value = parseExpression();
            if (index != input.length()) {
                throw new IllegalArgumentException("Invalid arithmetic expression: " + input);
            }
            return value;
        }

        private BigDecimal parseExpression() {
            BigDecimal value = parseTerm();
            while (index < input.length()) {
                char operator = input.charAt(index);
                if (operator != '+' && operator != '-') {
                    break;
                }
                index++;
                BigDecimal next = parseTerm();
                value = operator == '+' ? value.add(next) : value.subtract(next);
            }
            return value;
        }

        private BigDecimal parseTerm() {
            BigDecimal value = parseFactor();
            while (index < input.length()) {
                char operator = input.charAt(index);
                if (operator != '*' && operator != '/') {
                    break;
                }
                index++;
                BigDecimal next = parseFactor();
                value = operator == '*'
                    ? value.multiply(next)
                    : value.divide(next, 8, RoundingMode.HALF_UP);
            }
            return value;
        }

        private BigDecimal parseFactor() {
            if (index >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of arithmetic expression: " + input);
            }
            char current = input.charAt(index);
            if (current == '(') {
                index++;
                BigDecimal value = parseExpression();
                expect(')');
                return value;
            }
            if (current == '+') {
                index++;
                return parseFactor();
            }
            if (current == '-') {
                index++;
                return parseFactor().negate();
            }

            int start = index;
            while (index < input.length()) {
                char candidate = input.charAt(index);
                if ((candidate >= '0' && candidate <= '9') || candidate == '.') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected number in arithmetic expression: " + input);
            }
            return new BigDecimal(input.substring(start, index));
        }

        private void expect(char expected) {
            if (index >= input.length() || input.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in arithmetic expression: " + input);
            }
            index++;
        }
    }
}
