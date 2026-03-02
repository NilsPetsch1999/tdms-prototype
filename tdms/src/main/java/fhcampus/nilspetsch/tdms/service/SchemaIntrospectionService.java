package fhcampus.nilspetsch.tdms.service;

import fhcampus.nilspetsch.tdms.util.IdentifierValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
                (Integer) rs.getObject("character_maximum_length"),
                (Integer) rs.getObject("numeric_precision"),
                (Integer) rs.getObject("numeric_scale")
            ),
            schemaName,
            tableName
        );
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No columns found for " + schemaName + "." + tableName);
        }
        return columns;
    }
}
