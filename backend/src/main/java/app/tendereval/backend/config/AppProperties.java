package app.tendereval.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration read from the environment (PI 7.3). A missing required key stops the application at
 * start: application.yaml maps each key without a default, so an unset variable fails placeholder
 * resolution, and blank values fail validation here.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotBlank String publicBaseUrl,
    @NotBlank String keycloakIssuerUri,
    @NotBlank String keycloakJwkSetUri,
    @NotBlank String keycloakAudience,
    @NotBlank String spaClientId,
    @NotBlank String natsUrl,
    boolean devEndpoints) {}
