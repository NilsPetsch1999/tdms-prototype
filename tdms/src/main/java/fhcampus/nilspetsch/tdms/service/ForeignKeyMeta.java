package fhcampus.nilspetsch.tdms.service;

public record ForeignKeyMeta(
    String childTable,
    String childColumn,
    String parentTable,
    String parentColumn
) {
}
