package app.tendereval.backend.storage;

import app.tendereval.backend.config.AppProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Object storage over the S3 API (RustFS, path style). The backend is the only component that
 * receives a browser's bytes and the only one that streams them back (rule 9): there is no
 * presigned URL anywhere. Keys are lowercase UUIDs only (rule 10).
 */
@Component
public class ObjectStorage {

  public record Head(long size, String etag) {}

  private final S3AsyncClient s3;

  public ObjectStorage(AppProperties props) {
    this.s3 =
        S3AsyncClient.builder()
            .endpointOverride(URI.create(props.s3Endpoint()))
            .region(Region.of(props.s3Region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.s3AccessKey(), props.s3SecretKey())))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(props.s3PathStyle()).build())
            // The store is S3-compatible, not S3: only send checksums when an operation needs one.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(5)))
            .build();
  }

  public Mono<String> createMultipart(String bucket, String key, String contentType) {
    return Mono.fromFuture(
            s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build()))
        .map(r -> r.uploadId());
  }

  /** Part numbers start at 1. Returns the part's ETag. */
  public Mono<CompletedPart> uploadPart(
      String bucket, String key, String uploadId, int partNumber, byte[] data) {
    return Mono.fromFuture(
            s3.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength((long) data.length)
                    .build(),
                AsyncRequestBody.fromBytes(data)))
        .map(r -> CompletedPart.builder().partNumber(partNumber).eTag(r.eTag()).build());
  }

  public Mono<Void> complete(
      String bucket, String key, String uploadId, List<CompletedPart> parts) {
    return Mono.fromFuture(
            s3.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build()))
        .then();
  }

  public Mono<Void> abort(String bucket, String key, String uploadId) {
    return Mono.fromFuture(
            s3.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build()))
        .then()
        .onErrorResume(e -> Mono.empty()); // best effort: the lifecycle rule cleans leftovers
  }

  public Mono<Head> head(String bucket, String key) {
    return Mono.fromFuture(
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()))
        .map(r -> new Head(r.contentLength(), r.eTag()))
        .onErrorResume(NoSuchKeyException.class, e -> Mono.empty())
        .onErrorResume(
            e ->
                e.getCause() instanceof NoSuchKeyException
                    || e.getMessage() != null && e.getMessage().contains("404"),
            e -> Mono.empty());
  }

  /** A whole small object (parsed files, manifests). Never used for an original. */
  public Mono<byte[]> getBytes(String bucket, String key) {
    return Mono.fromFuture(
            s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                AsyncResponseTransformer.toBytes()))
        .map(r -> r.asByteArray());
  }
}
