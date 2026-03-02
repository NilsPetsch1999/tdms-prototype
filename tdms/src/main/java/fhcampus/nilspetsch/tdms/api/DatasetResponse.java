package fhcampus.nilspetsch.tdms.api;

import fhcampus.nilspetsch.tdms.domain.DatasetSourceType;
import fhcampus.nilspetsch.tdms.domain.DatasetStatus;

import java.time.Instant;

public record DatasetResponse(
    Long id,
    String name,
    String description,
    DatasetSourceType sourceType,
    DatasetStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
