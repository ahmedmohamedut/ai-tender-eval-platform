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
    boolean devEndpoints,
    String bootstrapAdminEmail,
    int userStatusCacheSeconds,
    int sessionIdleTimeoutSeconds,
    int rateLimitUploadsPerMinute,
    int rateLimitSearchPerMinute,
    int rateLimitJobStartsPerMinute,
    int rateLimitExportsPerMinute,
    @NotBlank String keycloakAdminBaseUrl,
    @NotBlank String keycloakAdminClientId,
    @NotBlank String keycloakAdminClientSecret,
    int invitationLifespanSeconds,
    @NotBlank String s3Endpoint,
    String s3Region,
    boolean s3PathStyle,
    @NotBlank String s3AccessKey,
    @NotBlank String s3SecretKey,
    String s3BucketDocuments,
    String s3BucketDerived,
    String s3BucketJobs,
    String s3BucketExports,
    int s3MultipartPartMb,
    @NotBlank String dbIngestUsername,
    @NotBlank String dbIngestPassword,
    int dbIngestPoolMaxSize,
    int watchdogQueuedTimeoutSeconds,
    int watchdogSilenceTimeoutSeconds,
    int jobMaxMinutesDocumentProcessing,
    int jobMaxMinutesRequirementExtraction,
    int jobMaxMinutesScreeningPrecheck,
    int jobMaxMinutesEvaluation,
    int watchdogIntervalSeconds) {

  /** Ceiling for a job type (PI 7.3). */
  public int jobMaxMinutes(String jobType) {
    return switch (jobType) {
      case "document_processing" -> jobMaxMinutesDocumentProcessing;
      case "requirement_extraction" -> jobMaxMinutesRequirementExtraction;
      case "screening_precheck" -> jobMaxMinutesScreeningPrecheck;
      default -> jobMaxMinutesEvaluation;
    };
  }
}
