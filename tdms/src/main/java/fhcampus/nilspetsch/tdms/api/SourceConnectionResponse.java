package fhcampus.nilspetsch.tdms.api;

public record SourceConnectionResponse(
    String url,
    String username,
    String password,
    String driverClassName,
    String databaseProductName,
    String databaseProductVersion,
    String currentCatalog
) {
}
