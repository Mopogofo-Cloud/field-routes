package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static fieldops.Views.*;

/** Private, owner-operated setup for R2's S3 API. */
final class R2Setup {
    static final String PATH = "/admin/photo-storage";
    private static final String MODE = "FIELDOPS_PHOTO_STORAGE";
    private static final String BUCKET = "FIELDOPS_S3_BUCKET";
    private static final String ENDPOINT = "FIELDOPS_S3_ENDPOINT";
    private static final String ID = "FIELDOPS_S3_ACCESS_KEY_ID";
    private static final String SECRET = "FIELDOPS_S3_SECRET_ACCESS_KEY";
    private static final Pattern R2_ENDPOINT = Pattern.compile(
        "https://[a-f0-9]{32}(?:\\.(?:eu|us|fedramp))?\\.r2\\.cloudflarestorage\\.com/?");
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    private R2Setup() {}

    static boolean owner(ObjectNode person) {
        return owner(person, System.getenv("FIELDOPS_STORAGE_OWNER_ID"));
    }

    static boolean owner(ObjectNode person, String ownerId) {
        return person != null && person.path("is_admin").asBoolean() && !person.path("must_change_password").asBoolean()
            && person.path("id").isIntegralNumber() && person.path("id").asLong() > 0
            && person.path("id").asText().equals(ownerId);
    }

    private static Path file() {
        String custom = System.getenv("FIELDOPS_CONFIG_DIR");
        Path dir = custom == null ? Path.of(System.getProperty("user.home"), ".config", "wg-fieldops") : Path.of(custom);
        return dir.resolve("r2.properties");
    }

    private static String suggestedEndpoint() {
        String account = System.getenv("FIELDOPS_R2_ACCOUNT_ID");
        return account != null && account.matches("[a-f0-9]{32}")
            ? "https://" + account + ".r2.cloudflarestorage.com" : "";
    }

    static Map<String,String> settings() {
        return settings(System.getenv(), file());
    }

    static Map<String,String> settings(Map<String,String> env, Path path) {
        Map<String,String> environment = new HashMap<>();
        env.forEach((k,v) -> { if (k.equals(MODE) || k.startsWith("FIELDOPS_S3_")) environment.put(k, v); });
        if (env.containsKey(MODE)) return Map.copyOf(environment);
        Properties saved = new Properties();
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            saved.load(in);
            var configuration = candidate(saved.getProperty(ENDPOINT), saved.getProperty(BUCKET),
                saved.getProperty(ID), saved.getProperty(SECRET));
            if (!"s3".equals(saved.getProperty(MODE))) throw new IOException("Invalid storage mode");
            return configuration;
        } catch (NoSuchFileException e) {
            return Map.copyOf(environment);
        } catch (IOException | IllegalArgumentException | Problem e) {
            throw new Problem(503, "The server could not read its saved photo storage connection.");
        }
    }

    static Map<String,String> candidate(String endpoint, String bucket, String key, String secret) {
        endpoint = endpoint == null ? "" : endpoint.strip();
        bucket = bucket == null ? "" : bucket.strip();
        if (!R2_ENDPOINT.matcher(endpoint).matches())
            throw new Problem(422, "Copy the S3 API endpoint from the R2 dashboard. It ends in r2.cloudflarestorage.com.");
        if (!BUCKET_NAME.matcher(bucket).matches())
            throw new Problem(422, "Enter the bucket name as it appears in Cloudflare.");
        if (!credential(key) || !credential(secret))
            throw new Problem(422, "Enter both the access key ID and secret access key from the R2 token.");
        return Map.of(MODE, "s3", BUCKET, bucket, ENDPOINT, endpoint.replaceFirst("/$", ""),
            "FIELDOPS_S3_REGION", "auto", ID, key, SECRET, secret);
    }

    private static boolean credential(String value) {
        return value != null && value.matches("[!-~]{1,512}");
    }

    static synchronized void activate(Path path, Map<String,String> env, Map<String,String> config,
                                      Consumer<Map<String,String>> probe) throws IOException {
        if (env.containsKey(MODE) || "s3".equals(settings(env, path).get(MODE)))
            throw new Problem(409, "Photo storage has already been configured.");
        probe.accept(config);
        var privateDir = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        var privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        Path directory = Files.createDirectories(path.getParent(), privateDir);
        Path temporary = Files.createTempFile(directory, ".r2-", ".tmp", privateFile);
        try {
            Properties saved = new Properties();
            saved.putAll(config);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                saved.store(Channels.newOutputStream(channel), "Field Routes private R2 connection");
                channel.force(true);
            }
            // Publish the completed file atomically without replacing an existing connection.
            Files.createLink(path, temporary);
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void handle(HttpServletRequest req, HttpServletResponse res, ObjectNode person) throws IOException {
        if (!owner(person)) throw new Problem(403, "Only the storage account owner can open this page.");
        String error = "";
        String success = "";
        String account = System.getenv("FIELDOPS_R2_ACCOUNT_ID");
        boolean provision = account != null && account.matches("[a-f0-9]{32}");
        var config = settings();
        boolean external = System.getenv().containsKey(MODE);
        if ("POST".equals(req.getMethod())) {
            boolean create = "create".equals(param(req, "action"));
            try {
                if (external || "s3".equals(config.get(MODE)))
                    throw new Problem(409, "Photo storage has already been configured.");
                if (create) {
                    if (!provision) throw new Problem(422, "Set the R2 account ID on the server before creating a bucket here.");
                    boolean created = R2BucketSetup.create(account, param(req, "setup_token"));
                    success = created ? "Created field-routes-photos. You can revoke the setup token and connect the bucket below."
                        : "The bucket already exists. Check its public access settings in Cloudflare, then connect it below.";
                } else {
                    var candidate = candidate(param(req, "endpoint"), param(req, "bucket"), param(req, "access_key"), param(req, "secret"));
                    activate(file(), System.getenv(), candidate, PhotoStorageCheck::verify);
                    redirect(res, PATH);
                    return;
                }
            } catch (Problem e) {
                res.setStatus(e.status);
                error = !create && e.status == 503 ? "Could not verify the R2 connection. Check the endpoint, bucket, and token permissions, then try again." : e.getMessage();
            }
        }
        String content;
        if ("s3".equals(config.get(MODE))) {
            content = "<p>New job photos are stored in the private bucket <strong>" + h(config.get(BUCKET)) +
                "</strong>. Workers open photos through their assigned job reports.</p>";
        } else if (external) {
            content = "<p>The server is configured to store new job photos locally.</p>";
        } else {
            String dashboard = provision ? "https://dash.cloudflare.com/" + account + "/r2/overview"
                : "https://dash.cloudflare.com/?to=/:account/r2/overview";
            content = "<p>New photos are currently saved in the Field Routes database.</p>" +
                "<p>Enable R2 in the <a href='" + h(dashboard) + "' target='_blank' rel='noopener noreferrer'>Cloudflare dashboard</a>.</p>";
            if (provision) {
                content += "<details class='panel'" + ("create".equals(param(req, "action")) && success.isEmpty() ? " open" : "") +
                    "><summary>Create field-routes-photos for me</summary><form method='post' class='stack' autocomplete='off'>" +
                    hidden("action", "create") + "<p>This creates a private Standard bucket in your Cloudflare account. " +
                    "<a target='_blank' rel='noopener noreferrer' href='" + h(R2BucketSetup.templateLink(account)) +
                    "'>Create a temporary setup token in Cloudflare</a> and enter it below. Field Routes uses it for bucket setup only.</p>" +
                    field("Temporary setup token", "setup_token", "", "type='password' required autocomplete='off'") +
                    "<button>Create bucket</button></form></details>";
            }
            content += "<h2>Connect your bucket</h2><p>Use a private bucket with both the public development URL and custom domains disabled. " +
                "Under <strong>R2 → Overview → API Tokens → Manage</strong>, create an <strong>Object Read &amp; Write</strong> token " +
                "limited to your bucket. Enter its S3 credentials here.</p><form method='post' class='stack' autocomplete='off'>" +
                field("S3 API endpoint", "endpoint", suggestedEndpoint(), "type='url' required autocomplete='off' spellcheck='false'") +
                field("Bucket name", "bucket", "field-routes-photos", "required autocomplete='off' spellcheck='false'") +
                field("Access key ID", "access_key", "", "type='password' required autocomplete='off'") +
                field("Secret access key", "secret", "", "type='password' required autocomplete='new-password'") +
                "<p>Field Routes will write, read, and delete a small test object before switching new uploads to R2.</p>" +
                "<button>Verify and connect R2</button></form>";
        }
        if (external) content += "<p>Storage settings are managed on the server.</p>";
        page(res, person, "Photo storage", "<section class='panel stack'><a href='/routes'>Daily Routes</a><h1>Photo storage</h1>" +
            alert(error) + (success.isEmpty() ? "" : notice(success)) + content + "</section>");
    }
}
