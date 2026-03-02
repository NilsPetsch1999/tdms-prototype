package fhcampus.nilspetsch.tdms.api;

public record SchemaColumnResponse(
    String columnName,
    String dataType,
    boolean nullable,
    boolean primaryKey,
    Integer characterMaxLength,
    Integer numericPrecision,
    Integer numericScale
) {
}
