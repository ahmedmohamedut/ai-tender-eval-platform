package app.tendereval.backend.testsupport;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/** One RustFS (the S3 store used in every environment) per test JVM. */
public final class StorageFixture {

  public static final String ACCESS = "testaccesskey";
  public static final String SECRET = "testsecretkey-0123456789";
  public static final String DOCUMENTS = "tender-documents";
  public static final String DERIVED = "tender-derived";

  private static GenericContainer<?> container;
  private static S3AsyncClient client;

  private StorageFixture() {}

  public static synchronized void ensureStarted() {
    if (container != null) {
      return;
    }
    container =
        new GenericContainer<>("rustfs/rustfs:1.0.1")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS)
            .withEnv("RUSTFS_SECRET_KEY", SECRET)
            .withEnv("RUSTFS_VOLUMES", "/data")
            .withEnv("RUSTFS_ADDRESS", ":9000")
            .withExposedPorts(9000)
            .waitingFor(
                Wait.forHttp("/health").forPort(9000).withStartupTimeout(Duration.ofSeconds(90)));
    container.start();
    client =
        S3AsyncClient.builder()
            .endpointOverride(URI.create(endpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    for (String b : new String[] {DOCUMENTS, DERIVED, "tender-jobs", "tender-exports"}) {
      client.createBucket(r -> r.bucket(b)).join();
    }
  }

  public static String endpoint() {
    return "http://%s:%d".formatted(container.getHost(), container.getMappedPort(9000));
  }

  /** Size of an object, or -1 when it does not exist. */
  public static long size(String bucket, String key) {
    ensureStarted();
    try {
      return client
          .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
          .join()
          .contentLength();
    } catch (Exception e) {
      return -1;
    }
  }

  public static byte[] read(String bucket, String key) {
    ensureStarted();
    return client
        .getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncResponseTransformer.toBytes())
        .join()
        .asByteArray();
  }

  public static void put(String bucket, String key, byte[] data) {
    ensureStarted();
    client
        .putObject(
            r -> r.bucket(bucket).key(key),
            software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(data))
        .join();
  }
}
