package fieldops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Access is scoped to an explicitly configured bucket and prefix using application credentials. */
final class S3Photos implements AutoCloseable {
    final String destination;
    final String prefix;
    private final String bucket;
    private final S3Client client;
    static final int MAX_BYTES = 12 * 1024 * 1024;

    S3Photos(S3Client client, URI endpoint, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.destination = sha256((endpoint + "|" + bucket + "|" + prefix).getBytes(StandardCharsets.UTF_8));
    }

    static S3Photos from(Map<String,String> settings) {
        String mode = settings.getOrDefault("FIELDOPS_PHOTO_STORAGE", "sqlite");
        if (mode.equals("sqlite")) return null;
        if (!mode.equals("s3")) throw badConfig("Choose sqlite or s3 for photo storage.");
        String bucket = required(settings, "FIELDOPS_S3_BUCKET");
        String region = required(settings, "FIELDOPS_S3_REGION");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains(".."))
            throw badConfig("Invalid photo bucket name.");
        if (!region.equals("auto") && !region.matches("[a-z]{2}(-[a-z0-9]+)+-[0-9]+"))
            throw badConfig("Invalid photo bucket region.");
        String host = settings.get("FIELDOPS_S3_ENDPOINT");
        if (host == null || host.isBlank()) {
            if (region.equals("auto")) throw badConfig("Specify the R2 S3 endpoint.");
            host = "https://s3." + region + (region.startsWith("cn-") ? ".amazonaws.com.cn" : ".amazonaws.com");
        }
        URI endpoint;
        try { endpoint = URI.create(host); }
        catch (IllegalArgumentException ex) { throw badConfig("Invalid photo storage endpoint."); }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !Set.of("", "/").contains(endpoint.getRawPath()))
            throw badConfig("Use the HTTPS origin of your S3 endpoint.");
        endpoint = URI.create("https://" + endpoint.getRawAuthority());
        String prefix = settings.getOrDefault("FIELDOPS_S3_PREFIX", "field-routes/photos/");
        if (!prefix.endsWith("/")) prefix += "/";
        if (prefix.length() > 500 || !prefix.matches("([a-zA-Z0-9._-]+/)+") ||
                ("/" + prefix).contains("/../") || ("/" + prefix).contains("/./"))
            throw badConfig("Invalid photo object prefix.");
        String id = required(settings, "FIELDOPS_S3_ACCESS_KEY_ID");
        String secret = required(settings, "FIELDOPS_S3_SECRET_ACCESS_KEY");
        String token = settings.get("FIELDOPS_S3_SESSION_TOKEN");
        AwsCredentials credentials = token == null || token.isBlank()
            ? AwsBasicCredentials.create(id, secret) : AwsSessionCredentials.create(id, secret, token);
        return connect(endpoint, bucket, prefix, region, credentials);
    }

    static S3Photos connect(URI endpoint, String bucket, String prefix, String region, AwsCredentials credentials) {
        S3Client client = S3Client.builder().region(Region.of(region)).endpointOverride(endpoint)
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(40)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(90)).apiCallAttemptTimeout(Duration.ofSeconds(45)))
            .build();
        return new S3Photos(client, endpoint, bucket, prefix);
    }

    private static String required(Map<String,String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) throw badConfig("Missing photo storage setting " + key + ".");
        return value;
    }

    private static Problem badConfig(String message) { return new Problem(422, message); }
    static Problem unavailable() { return new Problem(503, "Private photo storage is unavailable. Please try again."); }
    String newKey() { return prefix + UUID.randomUUID(); }

    private void requireKey(String key) {
        if (!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw unavailable();
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    void put(String key, String mime, RequestBody body) {
        requireKey(key);
        try {
            client.putObject(r -> r.bucket(bucket).key(key).contentType(mime).cacheControl("private, no-store"), body);
        } catch (SdkException | UncheckedIOException ex) { throw unavailable(); }
    }

    byte[] get(String key, int length, String hash) {
        requireKey(key);
        if (length < 1 || length > MAX_BYTES) throw unavailable();
        try (var response = client.getObject(r -> r.bucket(bucket).key(key))) {
            if (!Long.valueOf(length).equals(response.response().contentLength())) throw unavailable();
            byte[] bytes = new byte[length];
            if (response.readNBytes(bytes, 0, length) != length || response.read() != -1 ||
                    !sha256(bytes).equals(hash)) throw unavailable();
            return bytes;
        } catch (IOException | SdkException ex) { throw unavailable(); }
    }

    void delete(String key) {
        requireKey(key);
        try { client.deleteObject(r -> r.bucket(bucket).key(key)); }
        catch (SdkException ex) { throw unavailable(); }
    }

    @Override public void close() { client.close(); }
}
