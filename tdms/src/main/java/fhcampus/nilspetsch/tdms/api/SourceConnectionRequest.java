package fhcampus.nilspetsch.tdms.api;

import jakarta.validation.constraints.NotBlank;

public record SourceConnectionRequest(
    @NotBlank String url,
    @NotBlank String username,
    String password,
    @NotBlank String driverClassName
) {
}
