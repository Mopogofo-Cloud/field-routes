package fieldops;

import jakarta.servlet.http.Part;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import static fieldops.StoreSql.*;

final class PhotoStorage {
    private PhotoStorage() {}
    private static final Logger LOG = Logger.getLogger(PhotoStorage.class.getName());
    private static S3Photos cloud;
    private static ScheduledExecutorService worker;

    static synchronized S3Photos active() {
        if (cloud == null) cloud = S3Photos.from(R2Setup.settings());
        return cloud;
    }

    static synchronized void start() {
        active();
        worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("photo-cleanup").factory());
        worker.scheduleWithFixedDelay(() -> {
            try {
                S3Photos store = active();
                if (store == null) return;
                try (Connection db = Db.open()) { clean(db, store); }
            } catch (Exception ex) { LOG.warning("Photo cleanup is waiting to retry."); }
        }, 5, 60, TimeUnit.SECONDS);
    }

    static synchronized void stop() {
        if (worker != null) worker.shutdownNow();
        if (cloud != null) cloud.close();
        cloud = null;
    }

    record Upload(String key, Part part, String mime, String hash) {}

    private static String dbTime(Connection db) throws SQLException {
        return postgres(db) ? "CAST(floor(extract(epoch FROM clock_timestamp())) AS bigint)" : "unixepoch()";
    }

    // Persist a cleanup reservation before any PUT. A crash, stale job edit, timeout, or partial group
    // upload leaves a retryable cleanup record, without exposing uncommitted photos to workers.
    static List<Upload> stage(Connection db, S3Photos store, List<Part> parts) throws Exception {
        return stage(db, store, parts, Store::mime);
    }

    static List<Upload> stage(Connection db, S3Photos store, List<Part> parts,
                              Function<byte[],String> identifyMime) throws Exception {
        List<Upload> uploads = new ArrayList<>();
        for (Part part : parts) {
            try (var input = new BufferedInputStream(part.getInputStream())) {
                input.mark(16);
                String mime = identifyMime.apply(input.readNBytes(12));
                input.reset();
                var digest = S3Photos.digest();
                try (var checked = new DigestInputStream(input, digest)) {
                    long size = checked.transferTo(java.io.OutputStream.nullOutputStream());
                    if (size != part.getSize() || size < 1 || size > S3Photos.MAX_BYTES)
                        throw new Problem(422, "Invalid photo size.");
                }
                uploads.add(new Upload(store.newKey(), part, mime, HexFormat.of().formatHex(digest.digest())));
            }
        }
        transaction(db, true, () -> {
            String now = dbTime(db);
            for (Upload u : uploads) change(db, """
                INSERT INTO photo_cleanup(destination,object_key,eligible_at,committable) VALUES(?,?,%s+86400,1)
                """.formatted(now), store.destination, u.key);
            return null;
        });
        for (Upload u : uploads) {
            var source = ContentStreamProvider.fromInputStreamSupplier(() -> {
                try { return u.part.getInputStream(); }
                catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
            store.put(u.key, u.mime, RequestBody.fromContentProvider(source, u.part.getSize(), u.mime));
        }
        return uploads;
    }

    static void publish(Connection db, long jobId, S3Photos store, List<Upload> uploads) throws SQLException {
        for (Upload u : uploads) {
            claimUpload(db, store, u.key);
            // The parent row provides job authorization and a stable photo ID for both backends.
            long id = insertId(db, "INSERT INTO photos(job_id,mime,content) VALUES(?,?,?)", jobId, u.mime, new byte[0]);
            change(db, """
                INSERT INTO photo_objects(photo_id,destination,object_key,byte_count,sha256) VALUES(?,?,?,?,?)
                """, id, store.destination, u.key, u.part.getSize(), u.hash);
        }
    }

    static void claimUpload(Connection db, S3Photos store, String key) throws SQLException {
        if (change(db, """
            DELETE FROM photo_cleanup WHERE destination=? AND object_key=? AND committable=1 AND eligible_at>%s
            """.formatted(dbTime(db)), store.destination, key) != 1) throw S3Photos.unavailable();
    }

    static void clean(Connection db, S3Photos store) throws SQLException {
        String now = dbTime(db);
        for (int i = 0; i < 32; i++) {
            String key = transaction(db, true, () -> {
                var row = one(db, """
                    SELECT c.object_key FROM photo_cleanup c WHERE c.destination=? AND c.eligible_at<=%s
                      AND NOT EXISTS(SELECT 1 FROM photo_objects p
                          WHERE p.destination=c.destination AND p.object_key=c.object_key)
                      AND NOT EXISTS(SELECT 1 FROM mainline_media m
                          WHERE m.destination=c.destination AND m.object_key=c.object_key)
                      AND NOT EXISTS(SELECT 1 FROM mainline_project_documents d
                          WHERE d.destination=c.destination AND d.object_key=c.object_key)
                    ORDER BY c.eligible_at LIMIT 1
                    """.formatted(now), store.destination);
                if (row == null) return null;
                String k = row.path("object_key").textValue();
                // Claim outside the network call. A crashed cleaner leaves the claim due again in five minutes.
                change(db, "UPDATE photo_cleanup SET eligible_at=" + now + "+300,committable=0 WHERE destination=? AND object_key=?",
                    store.destination, k);
                return k;
            });
            if (key == null) return;
            try { store.delete(key); }
            catch (Problem unavailable) { LOG.warning("Private photo removal is waiting to retry."); continue; }
            change(db, "DELETE FROM photo_cleanup WHERE destination=? AND object_key=? AND committable=0",
                store.destination, key);
        }
    }
}
