package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.util.IdentifierValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SchemaIntrospectionService {

    private final JdbcTemplate jdbcTemplate;

    public SchemaIntrospectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> listSchemas() {
        return jdbcTemplate.queryForList(
            """
                SELECT schema_name
                FROM information_schema.schemata
                WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                ORDER BY schema_name
                """,
            String.class
        );
    }

    public List<String> listTables(String schemaName) {
        IdentifierValidator.requireValid(schemaName, "schemaName");
        return jdbcTemplate.queryForList(
            """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
            String.class,
            schemaName
        );
    }

    public List<SchemaColumnMeta> listColumns(String schemaName, String tableName) {
        IdentifierValidator.requireValid(schemaName, "schemaName");
        IdentifierValidator.requireValid(tableName, "tableName");
        List<SchemaColumnMeta> columns = jdbcTemplate.query(
            """
                SELECT column_name,
                       data_type,
                       is_nullable,
                       column_key,
                       character_maximum_length,
                       numeric_precision,
                       numeric_scale
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """,
            (rs, i) -> new SchemaColumnMeta(
                rs.getString("column_name"),
                rs.getString("data_type"),
                "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                "PRI".equalsIgnoreCase(rs.getString("column_key")),
                toInteger(rs.getObject("character_maximum_length")),
                toInteger(rs.getObject("numeric_precision")),
                toInteger(rs.getObject("numeric_scale"))
            ),
            schemaName,
            tableName
        );
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No columns found for " + schemaName + "." + tableName);
        }
        return columns;
    }

    public List<String> listPrimaryKeyColumns(String schemaName, String tableName) {
        IdentifierValidator.requireValid(schemaName, "schemaName");
        IdentifierValidator.requireValid(tableName, "tableName");
        return jdbcTemplate.queryForList(
            """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_key = 'PRI'
                ORDER BY ordinal_position
                """,
            String.class,
            schemaName,
            tableName
        );
    }

    public List<ForeignKeyMeta> listForeignKeys(String schemaName) {
        IdentifierValidator.requireValid(schemaName, "schemaName");
        return jdbcTemplate.query(
            """
                SELECT kcu.table_name AS child_table,
                       kcu.column_name AS child_column,
                       kcu.referenced_table_name AS parent_table,
                       kcu.referenced_column_name AS parent_column
                FROM information_schema.key_column_usage kcu
                WHERE kcu.table_schema = ?
                  AND kcu.referenced_table_name IS NOT NULL
                ORDER BY kcu.table_name, kcu.ordinal_position
                """,
            (rs, i) -> new ForeignKeyMeta(
                rs.getString("child_table"),
                rs.getString("child_column"),
                rs.getString("parent_table"),
                rs.getString("parent_column")
            ),
            schemaName
        );
    }

    public List<Object> listDistinctColumnValues(String schemaName, String tableName, String columnName, int limit) {
        IdentifierValidator.requireValid(schemaName, "schemaName");
        IdentifierValidator.requireValid(tableName, "tableName");
        IdentifierValidator.requireValid(columnName, "columnName");
        String sql = "SELECT DISTINCT `" + columnName + "` FROM `" + schemaName + "`.`" + tableName + "` WHERE `" + columnName + "` IS NOT NULL LIMIT ?";
        return new ArrayList<>(jdbcTemplate.queryForList(sql, Object.class, limit));
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Unexpected numeric metadata value type: " + value.getClass().getName());
    }
}
