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

    private void setupMainlineCrew() throws Exception {
        change(db, "INSERT INTO people(id,name,username,password_hash) VALUES(2,'Crew','other','test')");
        change(db, "INSERT INTO mainline_projects(id,name,state,supervisor_person_id) VALUES(10,'Build','In Progress',1)");
        change(db, "INSERT INTO mainline_dailies(id,project_id,date) VALUES(11,10,?)", Db.today());
        change(db, "INSERT INTO mainline_daily_members(daily_id,person_id,name) VALUES(11,1,'Worker'),(11,2,'Crew')");
    }

    private ObjectNode mainlineDraft(long revision) {
        var draft = App.JSON.createObjectNode().put("revision", revision).put("total_footage", 126.75)
            .put("comments", "East span finished").put("priority", "HIGH");
        draft.putArray("driver_ids").add(2);
        draft.putArray("expenses").addObject().put("type", "Gas").put("cost", 18.50);
        return draft;
    }

    @Test void mainlineInlineSaveStoresReportAndEachGalleryWithTargetedRemovals() throws Exception {
        setupMainlineCrew();
        Part file = new Upload(image);
        MainlineMedia.add(db,worker,11,"pictures",0,List.of(file),null);
        long keep = MainlineMedia.list(db,worker,11).at("/0/id").asLong();
        MainlineMedia.add(db,worker,11,"map",0,List.of(file),cloud);
        long remove = MainlineMedia.list(db,worker,11).at("/1/id").asLong();
        var maps = image.clone();
        var pictures = image.clone();
        var receipts = image.clone();
        maps[3] = 11;
        pictures[3] = 22;
        receipts[3] = 33;
        Map<String,List<Part>> uploads = Map.of("receipts",List.of(new Upload(receipts)),
            "map",List.of(new Upload(maps)), "pictures",List.of(new Upload(pictures)));
        var draft = mainlineDraft(0);
        assertEquals(404,assertThrows(Problem.class,() ->
            MainlineMedia.saveReport(db,other,11,draft,uploads,Set.of(remove),cloud)).status);
        assertEquals(422,assertThrows(Problem.class,() -> MainlineMedia.saveReport(db,worker,11,draft,
            Map.of("map",List.of(file),"pictures",java.util.Collections.nCopies(10,file)),Set.of(remove),cloud)).status);
        assertEquals(1,puts.get());

        MainlineMedia.saveReport(db,worker,11,draft,uploads,Set.of(remove),cloud);
        var saved = Mainline.daily(db,worker,11);
        assertEquals(1,saved.path("revision").asInt());
        assertEquals(126.75,saved.path("total_footage").asDouble());
        assertEquals("East span finished",saved.path("comments").asText());
        assertEquals("HIGH",saved.path("priority").asText());
        assertEquals("Gas",saved.at("/expenses/0/type").asText());
        assertEquals(18.50,saved.at("/expenses/0/cost").asDouble());
        assertEquals(2,saved.at("/driver_ids/0").asLong());
        var gallery = MainlineMedia.list(db,worker,11);
        assertEquals(4,gallery.size());
        var expected = new java.util.HashMap<>(Map.of("map",maps,"pictures",pictures,"receipts",receipts));
        for (var photo : gallery) {
            long id = photo.path("id").asLong();
            assertNotEquals(remove,id);
            byte[] contents = id==keep ? image : expected.remove(photo.path("category").asText());
            assertArrayEquals(contents,MainlineMedia.photo(db,worker,11,id,()->cloud).content());
        }
        assertTrue(expected.isEmpty());
        assertEquals(1,count("photo_cleanup"));
        PhotoStorage.clean(db,cloud);
        assertEquals(3,objects.size());
    }

    @Test void mainlineInlineUploadFailuresAndConcurrentEditsPreserveThePublishedReport() throws Exception {
        setupMainlineCrew();
        Part file = new Upload(image);
        var before = Mainline.saveDaily(db,worker,11,App.JSON.createObjectNode().put("revision",0).put("comments","Original"));
        MainlineMedia.add(db,worker,11,"pictures",0,List.of(file),cloud);
        var originalGallery = MainlineMedia.list(db,worker,11);
        long originalPhoto = originalGallery.at("/0/id").asLong();
        var draft = mainlineDraft(1);
        Map<String,List<Part>> uploads = Map.of("pictures",List.of(file),"receipts",List.of(file));
        failPut = puts.get()+2;
        assertEquals(503,assertThrows(Problem.class,() ->
            MainlineMedia.saveReport(db,worker,11,draft,uploads,Set.of(originalPhoto),cloud)).status);
        assertEquals(before,Mainline.daily(db,worker,11));
        assertEquals(originalGallery,MainlineMedia.list(db,worker,11));
        change(db,"UPDATE photo_cleanup SET eligible_at=0");
        PhotoStorage.clean(db,cloud);
        assertEquals(1,objects.size());
        assertArrayEquals(image,MainlineMedia.photo(db,worker,11,originalPhoto,()->cloud).content());

        onPut = () -> {
            try (var second = openFixture()) {
                change(second,"UPDATE mainline_dailies SET comments='Concurrent dispatch edit', revision=revision+1 WHERE id=11");
            } catch (Exception ex) { throw new AssertionError(ex); }
        };
        assertEquals(409,assertThrows(Problem.class,() ->
            MainlineMedia.saveReport(db,worker,11,draft,Map.of("map",List.of(file)),Set.of(originalPhoto),cloud)).status);
        var newer = Mainline.daily(db,worker,11);
        assertEquals("Concurrent dispatch edit",newer.path("comments").asText());
        assertEquals(2,newer.path("revision").asInt());
        assertEquals(before.path("total_footage"),newer.path("total_footage"));
        assertEquals(before.path("driver_ids"),newer.path("driver_ids"));
        assertEquals(before.path("expenses"),newer.path("expenses"));
        assertEquals(originalGallery,MainlineMedia.list(db,worker,11));
        int sent = puts.get();
        assertEquals(409,assertThrows(Problem.class,() ->
            MainlineMedia.saveReport(db,worker,11,draft,Map.of("map",List.of(file)),Set.of(),cloud)).status);
        assertEquals(sent,puts.get());
        change(db,"UPDATE photo_cleanup SET eligible_at=0");
        PhotoStorage.clean(db,cloud);
        assertEquals(1,objects.size());
    }

    @Test void mainlineInlineLocalFilesRollbackTogetherAndCanBeRetried() throws Exception {
        setupMainlineCrew();
        Part file = new Upload(image);
        MainlineMedia.add(db,worker,11,"map",0,List.of(file),null);
        var before = Mainline.daily(db,worker,11);
        var gallery = MainlineMedia.list(db,worker,11);
        long original = gallery.at("/0/id").asLong();
        var draft = mainlineDraft(0);
        List<Part> invalidBatch = List.of(file,new Upload(new byte[]{1,2,3}));
        assertEquals(422,assertThrows(Problem.class,() -> MainlineMedia.saveReport(db,worker,11,draft,
            Map.of("pictures",invalidBatch),Set.of(original),null)).status);
        assertEquals(before,Mainline.daily(db,worker,11));
        assertEquals(gallery,MainlineMedia.list(db,worker,11));
        var admin = App.JSON.createObjectNode().put("id",99).put("is_admin",true);
        change(db, "INSERT INTO mainline_dailies(id,project_id,date) VALUES(12,10,'2000-01-01')");
        MainlineMedia.add(db,admin,12,"receipts",0,List.of(file),null);
        long otherDailyPhoto = MainlineMedia.list(db,admin,12).at("/0/id").asLong();
        assertEquals(409,assertThrows(Problem.class,() -> MainlineMedia.saveReport(db,worker,11,draft,
            Map.of(),Set.of(original,otherDailyPhoto),null)).status);
        assertEquals(before,Mainline.daily(db,worker,11));
        assertEquals(gallery,MainlineMedia.list(db,worker,11));
        assertArrayEquals(image,MainlineMedia.photo(db,admin,12,otherDailyPhoto,()->null).content());
        MainlineMedia.saveReport(db,worker,11,draft,Map.of("pictures",List.of(file)),Set.of(original),null);
        var updated = MainlineMedia.list(db,worker,11);
        assertEquals(1,updated.size());
        assertEquals("pictures",updated.at("/0/category").asText());
        assertArrayEquals(image,MainlineMedia.photo(db,worker,11,updated.at("/0/id").asLong(),()->null).content());
        assertEquals(1,Mainline.daily(db,worker,11).path("revision").asInt());
        assertEquals(0,puts.get());
    }

    @Test void mainlineGalleriesRetainEarlierBatchesAndRequireProjectSupervisor() throws Exception {
        setupMainlineCrew();
        Part picture = new Upload(image);
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.add(db,other,11,"map",0,List.of(picture),cloud)).status);
        assertEquals(0,puts.get());
        MainlineMedia.add(db,worker,11,"pictures",0,java.util.Collections.nCopies(10,picture),null);
        long latest = MainlineMedia.list(db,worker,11).get(9).get("id").asLong();
        MainlineMedia.add(db,worker,11,"pictures",latest,List.of(picture,picture),null);
        assertEquals(409,assertThrows(Problem.class,()->MainlineMedia.add(db,worker,11,"pictures",latest,List.of(picture),null)).status);
        MainlineMedia.add(db,worker,11,"map",0,List.of(picture),cloud);
        var all = MainlineMedia.list(db,worker,11);
        assertEquals(13,all.size());
        long mapId=all.get(12).get("id").asLong();
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.list(db,other,11)).status);
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.photo(db,other,11,mapId,()->cloud)).status);
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.remove(db,other,11,mapId)).status);
        assertEquals("map",all.get(12).get("category").asText());
        assertArrayEquals(image,MainlineMedia.photo(db,worker,11,mapId,()->cloud).content());
        assertEquals(422,assertThrows(Problem.class,()->MainlineMedia.add(db,worker,11,"receipts",0,
            java.util.Collections.nCopies(11,picture),cloud)).status);
        change(db, "UPDATE mainline_dailies SET date='2000-01-01' WHERE id=11");
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.photo(db,worker,11,mapId,()->cloud)).status);
        assertEquals(404,assertThrows(Problem.class,()->MainlineMedia.remove(db,worker,11,mapId)).status);
        var admin = App.JSON.createObjectNode().put("id",99).put("is_admin",true);
        assertEquals("map",MainlineMedia.remove(db,admin,11,mapId));
        assertEquals(12,MainlineMedia.list(db,admin,11).size());
        assertEquals(1,count("photo_cleanup"));
        PhotoStorage.clean(db,cloud);
        assertTrue(objects.isEmpty());
    }

    @Test void mainlineProjectDocumentsHonorCrewAccessAndCleanUpReplacedFiles() throws Exception {
        change(db, "INSERT INTO mainline_projects(id,name,state) VALUES(10,'Build','In Progress')");
        change(db, "INSERT INTO mainline_dailies(id,project_id,date) VALUES(11,10,?)", Db.today());
        change(db, "INSERT INTO mainline_daily_members(daily_id,person_id,name) VALUES(11,1,'Worker')");
        var admin = App.JSON.createObjectNode().put("is_admin",true);
        byte[] first = "%PDF-1.4\nOld plan".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] next = "%PDF-1.7\nRevised plan".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(403, assertThrows(Problem.class, () ->
            MainlineDocuments.upload(db,worker,10,"redline",0,new Upload(first),cloud)).status);
        assertEquals(422, assertThrows(Problem.class, () ->
            MainlineDocuments.upload(db,admin,10,"fiber",0,new Upload(image),cloud)).status);
        MainlineDocuments.upload(db,admin,10,"redline",0,new Upload(first),cloud);
        assertEquals(1, MainlineDocuments.list(db,worker,10).at("/redline/revision").asInt());
        assertEquals("redline",Mainline.workerAssignments(db,worker).at("/0/documents/0").asText());
        assertEquals(404,assertThrows(Problem.class,()->Mainline.daily(db,worker,11)).status);
        assertEquals(404, assertThrows(Problem.class, () ->
            MainlineDocuments.download(db,other,10,"redline",()->cloud)).status);
        assertArrayEquals(first,MainlineDocuments.download(db,worker,10,"redline",()->cloud));
        assertEquals(409, assertThrows(Problem.class, () ->
            MainlineDocuments.upload(db,admin,10,"redline",0,new Upload(next),cloud)).status);
        assertEquals(1, objects.size());
        MainlineDocuments.upload(db,admin,10,"redline",1,new Upload(next),null);
        assertEquals(1,count("photo_cleanup"));
        PhotoStorage.clean(db, cloud);
        assertTrue(objects.isEmpty());
        assertArrayEquals(next,MainlineDocuments.download(db,worker,10,"redline",()->null));
        change(db, "UPDATE mainline_dailies SET date='2000-01-01' WHERE id=11");
        assertEquals(404, assertThrows(Problem.class, () -> MainlineDocuments.list(db,worker,10)).status);
        assertArrayEquals(next,MainlineDocuments.download(db,admin,10,"redline",()->null));
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
