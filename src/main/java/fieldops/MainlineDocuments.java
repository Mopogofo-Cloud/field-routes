package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Part;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import static fieldops.StoreSql.*;

final class MainlineDocuments {
    private MainlineDocuments() {}

    static final List<String> KINDS = List.of("redline", "fiber", "locates");

    static List<String> schema(boolean pg) {
        String table = """
            CREATE TABLE IF NOT EXISTS mainline_project_documents (
                project_id BIGINT NOT NULL REFERENCES mainline_projects(id) ON DELETE CASCADE,
                kind TEXT NOT NULL CHECK(kind IN ('redline','fiber','locates')),
                revision BIGINT NOT NULL CHECK(revision > 0), updated_at TEXT NOT NULL,
                byte_count INTEGER NOT NULL CHECK(byte_count BETWEEN 1 AND 12582912),
                sha256 TEXT NOT NULL, content %s, destination TEXT, object_key TEXT,
                PRIMARY KEY(project_id,kind), UNIQUE(destination,object_key),
                CHECK((content IS NOT NULL AND length(content)=byte_count AND destination IS NULL AND object_key IS NULL)
                   OR (content IS NULL AND destination IS NOT NULL AND object_key IS NOT NULL)))
            """.formatted(pg ? "BYTEA" : "BLOB");
        if (pg) {
            return List.of(table, """
                CREATE OR REPLACE FUNCTION fieldroutes.collect_mainline_document() RETURNS trigger
                LANGUAGE plpgsql SET search_path=pg_catalog AS $$
                BEGIN
                    IF OLD.object_key IS NOT NULL AND (TG_OP='DELETE' OR OLD.object_key IS DISTINCT FROM NEW.object_key
                                                       OR OLD.destination IS DISTINCT FROM NEW.destination) THEN
                        INSERT INTO fieldroutes.photo_cleanup(destination,object_key,eligible_at,committable)
                        VALUES(OLD.destination,OLD.object_key,floor(extract(epoch FROM clock_timestamp()))::bigint,0)
                        ON CONFLICT(destination,object_key) DO UPDATE SET eligible_at=excluded.eligible_at,committable=0;
                    END IF;
                    RETURN NULL;
                END
                $$
                """, """
                CREATE OR REPLACE TRIGGER collect_mainline_document AFTER DELETE OR UPDATE OF object_key,destination
                ON mainline_project_documents FOR EACH ROW EXECUTE FUNCTION fieldroutes.collect_mainline_document()
                """);
        }
        String enqueue = """
            BEGIN
                INSERT INTO photo_cleanup(destination,object_key,eligible_at,committable)
                VALUES(OLD.destination,OLD.object_key,unixepoch(),0)
                ON CONFLICT(destination,object_key) DO UPDATE SET eligible_at=excluded.eligible_at,committable=0;
            END
            """;
        return List.of(table,
            "CREATE TRIGGER IF NOT EXISTS remove_mainline_document AFTER DELETE ON mainline_project_documents "
                + "WHEN OLD.object_key IS NOT NULL " + enqueue,
            "CREATE TRIGGER IF NOT EXISTS replace_mainline_document AFTER UPDATE OF object_key,destination ON mainline_project_documents "
                + "WHEN OLD.object_key IS NOT NULL AND (NEW.object_key IS NOT OLD.object_key OR NEW.destination IS NOT OLD.destination) " + enqueue);
    }

    private static void validateKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) throw new Problem(404, "Project document not found.");
    }

    private static void checkAccess(Connection db, ObjectNode user, long project) throws SQLException {
        if (user == null || !Mainline.canSeeProject(db,user,project)) throw new Problem(404,"Project not found.");
    }

    static ObjectNode list(Connection db, ObjectNode user, long project) throws SQLException {
        return transaction(db, false, () -> {
            checkAccess(db,user,project);
            var documents = App.JSON.createObjectNode();
            for (var row : rows(db, """
                    SELECT kind,revision,updated_at,byte_count FROM mainline_project_documents WHERE project_id=? ORDER BY kind
                    """, project)) documents.set(row.path("kind").asText(),row);
            return documents;
        });
    }

    private static String pdfMime(byte[] header) {
        if (header.length < 5 || header[0]!='%' || header[1]!='P' || header[2]!='D' || header[3]!='F' || header[4]!='-')
            throw new Problem(422, "Choose a PDF file.");
        return "application/pdf";
    }

    private static void checkRevision(Connection db, ObjectNode user, long project, String kind, long revision) throws SQLException {
        if (user == null || !user.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        checkAccess(db,user,project);
        var row = one(db,"SELECT revision FROM mainline_project_documents WHERE project_id=? AND kind=?", project,kind);
        if ((row == null ? 0 : row.path("revision").asLong()) != revision)
            throw new Problem(409,"The project document changed. Reload the project and try again.");
    }

    static void upload(Connection db, ObjectNode user, long project, String kind, long revision, Part part) throws Exception {
        upload(db,user,project,kind,revision,part,PhotoStorage.active());
    }

    static void upload(Connection db, ObjectNode user, long project, String kind, long revision, Part part, S3Photos storage) throws Exception {
        validateKind(kind);
        if (part == null || part.getSize()<1 || part.getSize()>S3Photos.MAX_BYTES)
            throw new Problem(422,"Choose a PDF up to 12 MiB.");
        transaction(db,false,() -> { checkRevision(db,user,project,kind,revision); return null; });
        byte[] content;
        String key, hash;
        if (storage == null) {
            try (var stream = part.getInputStream()) { content = stream.readNBytes((int)part.getSize()+1); }
            if (content.length != part.getSize()) throw new Problem(422,"Invalid document size.");
            pdfMime(content);
            key = null;
            hash = S3Photos.sha256(content);
        } else {
            var staged = PhotoStorage.stage(db,storage,List.of(part),MainlineDocuments::pdfMime).getFirst();
            content = null;
            key = staged.key();
            hash = staged.hash();
        }
        transaction(db,true,() -> {
            checkRevision(db,user,project,kind,revision);
            if (storage != null) PhotoStorage.claimUpload(db,storage,key);
            change(db, """
                INSERT INTO mainline_project_documents(project_id,kind,revision,updated_at,byte_count,sha256,content,destination,object_key)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(project_id,kind) DO UPDATE SET revision=excluded.revision, updated_at=excluded.updated_at,
                  byte_count=excluded.byte_count, sha256=excluded.sha256, content=excluded.content,
                  destination=excluded.destination, object_key=excluded.object_key
                """,project,kind,revision+1,Instant.now().toString(),part.getSize(),hash,content,storage==null?null:storage.destination,key);
            return null;
        });
    }

    private record File(byte[] content, String destination, String key, int size, String hash) {}

    static byte[] download(Connection db, ObjectNode user, long project, String kind) throws SQLException {
        return download(db,user,project,kind,PhotoStorage::active);
    }

    static byte[] download(Connection db, ObjectNode user, long project, String kind, Supplier<S3Photos> storage) throws SQLException {
        validateKind(kind);
        File file = transaction(db,false,() -> {
            checkAccess(db,user,project);
            try (var stmt = db.prepareStatement("""
                    SELECT content,destination,object_key,byte_count,sha256 FROM mainline_project_documents WHERE project_id=? AND kind=?
                    """)) {
                bind(stmt,project,kind);
                try (var row = stmt.executeQuery()) {
                    if (!row.next()) throw new Problem(404,"Project document not found.");
                    return new File(row.getBytes(1),row.getString(2),row.getString(3),row.getInt(4),row.getString(5));
                }
            }
        });
        if (file.content()!=null) return file.content();
        var cloud = storage.get();
        if (cloud==null || !cloud.destination.equals(file.destination())) throw S3Photos.unavailable();
        return cloud.get(file.key(),file.size(),file.hash());
    }
}
