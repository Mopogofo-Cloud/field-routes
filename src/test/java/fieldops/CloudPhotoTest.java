package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import static org.junit.jupiter.api.Assertions.*;
import static fieldops.StoreSql.*;

class CloudPhotoTest {
    @TempDir Path dir;
    private HttpServer server;
    private Connection db;
    private S3Photos cloud;
    private final Map<String,byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicBoolean unsigned = new AtomicBoolean();
    private int failPut;
    private boolean failDelete;
    private Runnable onPut;
    private final byte[] image = {(byte)255,(byte)216,(byte)255,2,3,4};
    private final ObjectNode worker = App.JSON.createObjectNode().put("id", 1).put("is_admin", false);
    private final ObjectNode other = worker.deepCopy().put("id",2);

    private record Upload(byte[] payload) implements Part {
        public InputStream getInputStream() { return new ByteArrayInputStream(payload); }
        public long getSize() { return payload.length; }
        public String getName() { return "photos"; }
        public String getContentType() { return "image/jpeg"; }
        public String getSubmittedFileName() { return "field.jpg"; }
        public void write(String path) { throw new UnsupportedOperationException(); }
        public void delete() {}
        public Collection<String> getHeaderNames() { return List.of(); }
        public Collection<String> getHeaders(String name) { return List.of(); }
        public String getHeader(String name) { return null; }
    }

    Connection openFixture() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("trial.sqlite3"));
    }

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private/photos/", this::objectRequest);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        cloud = S3Photos.connect(endpoint, "private","photos/", "auto", AwsBasicCredentials.create("test-key","test-secret"));
        db = openFixture();
        if (!StoreSql.postgres(db)) change(db,"PRAGMA foreign_keys=ON");
        Db.initialize(db);
        change(db,"INSERT INTO people(id,name,username,password_hash) VALUES(1,'Worker','worker','test')");
        change(db,"INSERT INTO entities VALUES(1,'Prime')");
        change(db,"INSERT INTO teams VALUES(1,'Crew',1)");
        change(db,"INSERT INTO routes(id,date,team_id,team_name,entity_id,entity_name,address_hash) VALUES(1,?,1,'Crew',1,'Prime','hash')",Db.today());
        change(db,"INSERT INTO assignments VALUES(1,1,'Worker','Lead','daily',NULL)");
        change(db,"INSERT INTO jobs(id,route_id,position,address) VALUES(1,1,1,'Main Street')");
    }

    @AfterEach void stop() throws Exception {
        if(db!=null) db.close();
        if(cloud!=null) cloud.close();
        if(server!=null) server.stop(0);
    }

    private void objectRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ") || exchange.getRequestHeaders().containsKey("x-amz-acl"))
                unsigned.set(true);
            switch(exchange.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path,exchange.getRequestBody().readAllBytes());
                    int n = puts.incrementAndGet();
                    if (onPut != null) onPut.run();
                    exchange.sendResponseHeaders(n == failPut ? 403 : 200,-1);
                }
                case "GET" -> {
                    gets.incrementAndGet();
                    byte[] bytes = objects.get(path);
                    if(bytes == null) exchange.sendResponseHeaders(404,-1);
                    else { exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); }
                }
                case "DELETE" -> {
                    if(!failDelete) objects.remove(path);
                    exchange.sendResponseHeaders(failDelete ? 403 : 204,-1);
                }
                default -> exchange.sendResponseHeaders(405,-1);
            }
        }
    }

    private ObjectNode form(int revision) {
        return App.JSON.createObjectNode().put("fiber_type","Drop").put("feet_buried",18).put("revision",revision);
    }
    private int count(String table) throws Exception {
        return one(db,"SELECT count(*) AS n FROM " + table).get("n").intValue();
    }

    @Test void signedProbeAndAuthorizedMixedStorageWithDeferredDeletion() throws Exception {
        PhotoStorageCheck.verify(cloud);
        assertTrue(objects.isEmpty());
        assertFalse(unsigned.get());
        long local = Store.report(db,worker,1,form(0),List.of(new Upload(image)),null)
            .at("/photos/0/id").longValue();
        Db.initialize(db);
        ObjectNode report = Store.report(db,worker,1,form(1),List.of(new Upload(image)),cloud);
        long remote = report.at("/photos/1/id").longValue();
        assertEquals("/api/photos/" + remote,report.at("/photos/1/url").asText());
        assertEquals(0,one(db,"SELECT length(content) AS size FROM photos WHERE id=?",remote).get("size").intValue());
        assertEquals(0,count("photo_cleanup"));
        assertArrayEquals(image,Store.photo(db,worker,local,()->{throw new AssertionError();}).content());
        assertArrayEquals(image,Store.photo(db,worker,remote,()->cloud).content());
        int before=gets.get();
        assertEquals(404,assertThrows(Problem.class,()->Store.photo(db,other,remote,()->cloud)).status);
        assertEquals(503,assertThrows(Problem.class,()->Store.photo(db,worker,remote,()->null)).status);
        assertEquals(before,gets.get());
        objects.replaceAll((k,v)->new byte[v.length]);
        assertEquals(503,assertThrows(Problem.class,()->Store.photo(db,worker,remote,()->cloud)).status);
        ObjectNode edit = form(2);
        edit.putArray("keep_photo_ids").add(local);
        Store.report(db,worker,1,edit,List.of(),cloud);
        assertEquals(0,count("photo_objects"));
        assertEquals(1,count("photo_cleanup"));
        assertEquals(404,assertThrows(Problem.class,()->Store.photo(db,worker,remote,()->cloud)).status);
        failDelete=true;
        PhotoStorage.clean(db,cloud);
        assertEquals(1,count("photo_cleanup"));
        failDelete=false;
        change(db,"UPDATE photo_cleanup SET eligible_at=0");
        PhotoStorage.clean(db,cloud);
        assertTrue(objects.isEmpty());
        assertEquals(0,count("photo_cleanup"));
    }

    @Test void partialUploadsAndConcurrentReportNeverPublishAbandonedObjects() throws Exception {
        failPut=2;
        assertEquals(503,assertThrows(Problem.class,()->Store.report(db,worker,1,form(0),
            List.of(new Upload(image),new Upload(image)),cloud)).status);
        assertEquals(0,count("photos"));
        assertEquals(2,count("photo_cleanup"));
        assertEquals(0,one(db,"SELECT revision FROM jobs").get("revision").intValue());
        change(db,"UPDATE photo_cleanup SET eligible_at=0");
        PhotoStorage.clean(db,cloud);
        assertTrue(objects.isEmpty());
        onPut=()-> {
            try (var otherConnection=openFixture()) {
                change(otherConnection,"UPDATE jobs SET revision=revision+1 WHERE id=1");
            } catch (Exception e) { throw new AssertionError(e); }
        };
        assertEquals(409,assertThrows(Problem.class,()->Store.report(db,worker,1,form(0),List.of(new Upload(image)),cloud)).status);
        assertEquals(0,count("photos"));
        assertEquals(1,count("photo_cleanup"));
        change(db,"UPDATE photo_cleanup SET eligible_at=0");
        PhotoStorage.clean(db,cloud);
        assertTrue(objects.isEmpty());
    }

    @Test void cloudModeRequiresExplicitSecretsAndTrustedEndpoint() {
        assertNull(S3Photos.from(Map.of()));
        Map<String,String> invalid = new java.util.HashMap<>(Map.of(
            "FIELDOPS_PHOTO_STORAGE","s3","FIELDOPS_S3_BUCKET","private","FIELDOPS_S3_REGION","auto"));
        for (String endpoint : Set.of("http://example.com", "https://user:secret@example.com", "https://example.com/path")) {
            invalid.put("FIELDOPS_S3_ENDPOINT", endpoint);
            assertEquals(422,assertThrows(Problem.class,()->S3Photos.from(invalid)).status);
        }
        invalid.put("FIELDOPS_S3_ENDPOINT","https://example.com");
        assertEquals(422,assertThrows(Problem.class,()->S3Photos.from(invalid)).status);
    }
}
