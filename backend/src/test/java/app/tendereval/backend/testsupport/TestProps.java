package app.tendereval.backend.testsupport;

import app.tendereval.backend.config.AppProperties;

/** Application properties for unit tests, with the defaults the application itself uses. */
public final class TestProps {

  private TestProps() {}

  public static AppProperties defaults() {
    return withUploadsPerMinute(10);
  }

  public static AppProperties withUploadsPerMinute(int uploadsPerMinute) {
    return new AppProperties(
        "http://localhost:18080",
        "http://issuer.test/realms/tender-eval",
        "http://issuer.test/jwks",
        "tender-api",
        "tender-spa",
        "nats://nats.test:4222",
        false,
        "",
        30,
        1800,
        uploadsPerMinute,
        120,
        20,
        10,
        "http://kc.test/admin/realms/tender-eval",
        "tender-backend-admin",
        "secret",
        259200,
        "http://s3.test",
        "us-east-1",
        true,
        "access",
        "secret",
        "tender-documents",
        "tender-derived",
        "tender-jobs",
        "tender-exports",
        8,
        "tender_ai_ingest",
        "ingest",
        2,
        120,
        300,
        30,
        60,
        30,
        360,
        30);
  }

  public static AppProperties withKeycloak(String adminBase) {
    AppProperties d = defaults();
    return new AppProperties(
        d.publicBaseUrl(),
        d.keycloakIssuerUri(),
        d.keycloakJwkSetUri(),
        d.keycloakAudience(),
        d.spaClientId(),
        d.natsUrl(),
        d.devEndpoints(),
        d.bootstrapAdminEmail(),
        d.userStatusCacheSeconds(),
        d.sessionIdleTimeoutSeconds(),
        d.rateLimitUploadsPerMinute(),
        d.rateLimitSearchPerMinute(),
        d.rateLimitJobStartsPerMinute(),
        d.rateLimitExportsPerMinute(),
        adminBase,
        "tender-backend-admin",
        "s3cret",
        259200,
        d.s3Endpoint(),
        d.s3Region(),
        d.s3PathStyle(),
        d.s3AccessKey(),
        d.s3SecretKey(),
        d.s3BucketDocuments(),
        d.s3BucketDerived(),
        d.s3BucketJobs(),
        d.s3BucketExports(),
        d.s3MultipartPartMb(),
        d.dbIngestUsername(),
        d.dbIngestPassword(),
        d.dbIngestPoolMaxSize(),
        d.watchdogQueuedTimeoutSeconds(),
        d.watchdogSilenceTimeoutSeconds(),
        d.jobMaxMinutesDocumentProcessing(),
        d.jobMaxMinutesRequirementExtraction(),
        d.jobMaxMinutesScreeningPrecheck(),
        d.jobMaxMinutesEvaluation(),
        d.watchdogIntervalSeconds());
  }
}
