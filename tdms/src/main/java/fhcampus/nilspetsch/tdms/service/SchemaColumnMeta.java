package fhcampus.nilspetsch.tdms.service;

public record SchemaColumnMeta(
    String columnName,
    String dataType,
    boolean nullable,
    boolean primaryKey,
    Integer characterMaxLength,
    Integer numericPrecision,
    Integer numericScale
) {
}
