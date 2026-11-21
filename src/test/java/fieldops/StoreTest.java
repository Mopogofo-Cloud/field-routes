package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    private ObjectNode object(String json) throws Exception {
        return (ObjectNode) App.JSON.readTree(json);
    }

    private record TestPhoto(byte[] bytes) implements Part {
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public long getSize() { return bytes.length; }
        public String getName() { return "photos"; }
        public String getContentType() { return "image/jpeg"; }
        public String getSubmittedFileName() { return "photo.jpg"; }
        public void write(String path) { throw new UnsupportedOperationException(); }
        public void delete() {}
        public Collection<String> getHeaderNames() { return List.of(); }
        public Collection<String> getHeaders(String name) { return List.of(); }
        public String getHeader(String name) { return null; }
    }

    @Test
    void dispatchAndReportPreserveHistoryAndAssignmentAccess() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            Db.initialize(db);
            ObjectNode profile = object("""
                {"name":"Crew lead","username":"crew","role":"Lead","pay_basis":"daily","pay_rate":null,
                 "email":"lead@example.test","password":"example password here"}
                """);
            long personId = Store.saveProfile(db, profile, null).get("id").longValue();
            StoreSql.change(db, "UPDATE people SET wgidig_account_type=?, wgidig_employee_id=?, wgidig_availability=? WHERE id=?",
                "Admin", "source-42", "Busy at import", personId);
            long entityId = Store.addEntity(db, object("{\"name\":\"Prime\"}")).get("id").longValue();
            ObjectNode team = object("{\"name\":\"Team\"}").put("entity_id", entityId);
            team.putArray("member_ids").add(personId);
            long teamId = Store.saveTeam(db, team, null).get("id").longValue();
            ObjectNode assignment = object("{\"addresses\":[\"104 Straße İstasyon ﬀ Côte ꭰ Ave\",\"Second site\"]}")
                .put("team_id", teamId).put("date", Db.today());
            ObjectNode route = Store.createRoute(db, assignment);
            assertEquals("b26f2adc970d8f1323ce678b34047bfbe2c1a217b25d55146b67c7b851144ff9",
                StoreSql.one(db, "SELECT address_hash FROM routes WHERE id=?", route.get("id").longValue()).get("address_hash").asText());
            long jobId = route.at("/jobs/1/id").longValue();
            assertTrue(route.at("/members/0/pay_rate").isNull());
            assertEquals(409, assertThrows(Problem.class, () -> Store.createRoute(db, assignment)).status);
            profile.remove(List.of("email","password"));
            profile.put("pay_rate", 150).put("role","");
            ObjectNode edited = Store.saveProfile(db, profile, personId);
            assertEquals("lead@example.test", edited.path("email").asText());
            assertEquals("Admin", edited.path("wgidig_account_type").asText());
            assertEquals("source-42", edited.path("wgidig_employee_id").asText());
            assertEquals("Busy at import", edited.path("wgidig_availability").asText());
            assertEquals(0, StoreSql.one(db, "SELECT is_admin FROM people WHERE id=?", personId).path("is_admin").asInt());
            ObjectNode admin = object("{\"is_admin\":true}");
            ObjectNode worker = object("{\"is_admin\":false}").put("id", personId);
            ObjectNode other = worker.deepCopy().put("id", personId + 100);
            assertTrue(Store.routes(db, admin, null).at("/0/members/0/pay_rate").isNull());
            assertFalse(Store.routes(db, worker, null).get(0).has("members"));
            assertNull(Store.routes(db, worker, null).findValue("wgidig_availability"));
            assertTrue(Store.routes(db, worker, LocalDate.parse(Db.today()).minusDays(1).toString()).isEmpty());
            assertTrue(Store.routes(db, other, null).isEmpty());
            ObjectNode report = object("""
                {"feet_buried":"12.5","fiber_type":"24 count","bores":[{"type":"driveway","feet":6}],"revision":0}
                """);
            Part photo = new TestPhoto(new byte[]{(byte)255,(byte)216,(byte)255,0});
            ObjectNode saved = Store.report(db, worker, jobId, report, List.of(photo));
            assertEquals("Crew lead", saved.path("submitted_by").asText());
            assertEquals("driveway", saved.at("/bores/0/type").asText());
            assertEquals(1, saved.path("revision").asInt());
            long photoId = saved.at("/photos/0/id").longValue();
            assertEquals("image/jpeg", Store.photo(db, worker, photoId).mime());
            assertEquals(404, assertThrows(Problem.class, () -> Store.photo(db, other, photoId)).status);
            assertEquals(404, assertThrows(Problem.class, () -> Store.report(db, other, jobId, report, List.of())).status);
            assertEquals(409, assertThrows(Problem.class, () -> Store.report(db, worker, jobId, report, List.of())).status);
            report.put("revision", 1).putArray("keep_photo_ids");
            Part invalid = new TestPhoto(new byte[]{0});
            assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, worker, jobId, report, List.of(invalid))).status);
            assertArrayEquals(new byte[]{(byte)255,(byte)216,(byte)255,0}, Store.photo(db, worker, photoId).content());
            assertTrue(Store.report(db, worker, jobId, report, List.of()).path("photos").isEmpty());
        }
    }
}
