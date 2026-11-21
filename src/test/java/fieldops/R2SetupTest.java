package fieldops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class R2SetupTest {
    @TempDir Path temp;
    final String endpoint = "https://" + "a".repeat(32) + ".r2.cloudflarestorage.com";
    final Map<String,String> candidate = R2Setup.candidate(endpoint, "test-bucket", "EXAMPLE", "test-secret");

    @Test void returnSiteAndOwnerAreRestricted() {
        assertEquals("", Views.returnPath("https://example.com"));
        assertEquals("", Views.returnPath("//example.com"));
        assertEquals(R2Setup.PATH, Views.returnPath(R2Setup.PATH));
        assertFalse(R2Setup.owner(null, "2"));
        var person = App.JSON.createObjectNode().put("id", 2).put("is_admin", true);
        assertTrue(R2Setup.owner(person, "2"));
        assertFalse(R2Setup.owner(person, "3"));
        assertFalse(R2Setup.owner(person, null));
        assertFalse(R2Setup.owner(person.put("must_change_password", true), "2"));
        assertFalse(R2Setup.owner(person.put("must_change_password", false).put("is_admin", false), "2"));
    }

    @Test void supportsOfficialEndpointsAndValidatedBuckets() {
        for (String extra : List.of("", ".eu", ".us", ".fedramp")) {
            String host = endpoint.replace(".r2.", extra + ".r2.");
            assertEquals(host, R2Setup.candidate(host + "/", "bucket-name", "key", "secret").get("FIELDOPS_S3_ENDPOINT"));
        }
        for (String invalid : List.of(endpoint + ".example.org", endpoint + "/bucket", endpoint + "@example.org",
                endpoint + "?key", endpoint + ":123", endpoint.replace("https", "http"), "http://localhost")) {
            assertThrows(Problem.class, () -> R2Setup.candidate(invalid, "test-bucket", "key", "secret"));
        }
        for (String invalid : List.of("a", "bucket/path", "", "bucket_name", "Bucket"))
            assertThrows(Problem.class, () -> R2Setup.candidate(endpoint, invalid, "key", "secret"));
    }

    @Test void checksBeforeEnablingAndNeverReplacesAnExistingConnection() throws Exception {
        Path file = temp.resolve("private/r2.properties");
        var empty = Map.<String,String>of();
        assertFalse(R2Setup.settings(empty, file).containsKey("FIELDOPS_PHOTO_STORAGE"));
        assertThrows(Problem.class, () -> R2Setup.activate(file, empty, candidate, config -> { throw new Problem(503, "Offline"); }));
        assertFalse(Files.exists(file));

        var checks = new AtomicInteger();
        R2Setup.activate(file, empty, candidate, config -> {
            assertEquals(candidate, config);
            assertFalse(Files.exists(file));
            checks.incrementAndGet();
        });
        assertEquals(1, checks.get());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(candidate, R2Setup.settings(empty, file));
        assertEquals(409, assertThrows(Problem.class, () -> R2Setup.activate(file, empty, candidate,
            config -> fail("connection must stay intact"))).status);

        var forced = Map.of("FIELDOPS_PHOTO_STORAGE", "sqlite");
        assertEquals(forced, R2Setup.settings(forced, file));
        assertThrows(Problem.class, () -> R2Setup.activate(temp.resolve("elsewhere/r2.properties"), forced, candidate,
            config -> fail("server-managed connection must stay intact")));

        Files.writeString(file, "FIELDOPS_PHOTO_STORAGE=s3\n");
        assertEquals(503, assertThrows(Problem.class, () -> R2Setup.settings(empty, file)).status);
    }
}
