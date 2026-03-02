package fhcampus.nilspetsch.tdms.service;

public record StoredDataFile(
    String relativePath,
    String checksumSha256,
    int rowCount
) {
}
