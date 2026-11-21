package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static fieldops.StoreSql.change;
import static org.junit.jupiter.api.Assertions.*;

class JobReportDataTest {
    private static final LocalDate DAY = LocalDate.of(2026, 2, 28);
    private final ObjectNode admin = App.JSON.createObjectNode().put("id", 1).put("is_admin", true);
    private Connection db;

    @BeforeEach void setUp() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        Db.initialize(db);
        change(db, """
            INSERT INTO people(id,username,password_hash,name,is_admin) VALUES
            (1,'dispatch','x','Dispatch',1),(2,'crew','x','Crew Member',0)
            """);
        change(db, "INSERT INTO entities VALUES(1,'Current entity')");
        change(db, "INSERT INTO teams VALUES(1,'Current team',1)");
    }

    @AfterEach void tearDown() throws Exception { db.close(); }

    private void route(int id, LocalDate date, String name) throws Exception {
        change(db, """
            INSERT INTO routes(id,date,team_id,team_name,entity_id,entity_name,address_hash)
            VALUES(?,?,1,?,1,'Original entity',?)
            """, id, date.toString(), name, "route-" + id);
        change(db, """
            INSERT INTO assignments(route_id,person_id,name,role,pay_basis)
            VALUES(?,2,'Crew at dispatch','Foreman','daily')
            """, id);
    }

    private void job(int id, int route, int stop, String outcome, String submissionTime) throws Exception {
        change(db, """
            INSERT INTO jobs(id,route_id,position,address,outcome,submitted_by,submitted_at)
            VALUES(?,?,?,'15 Main Street',?,?,?)
            """, id, route, stop, outcome, submissionTime == null ? null : 2, submissionTime);
    }

    @Test void acceptsAnInclusiveMonthIncludingShortMonthEnds() {
        for (var range : List.of(List.of("2026-01-01", "2026-01-31"), List.of("2026-01-31", "2026-02-28"),
                List.of("2028-01-31", "2028-02-29"), List.of("2026-11-20", "2026-12-19"),
                List.of("2026-02-01", "2026-02-28"), List.of("9999-12-31", "9999-12-31")))
            assertDoesNotThrow(() -> JobReportData.validateRange(LocalDate.parse(range.get(0)), LocalDate.parse(range.get(1))));

        for (var range : List.of(List.of("2026-01-01", "2026-02-01"), List.of("2026-01-31", "2026-03-01"),
                List.of("2026-11-20", "2026-12-20"), List.of("2026-02-01", "2026-01-31")))
            assertEquals(422, assertThrows(Problem.class, () -> JobReportData.validateRange(
                LocalDate.parse(range.get(0)), LocalDate.parse(range.get(1)))).status);
        assertThrows(Problem.class, () -> JobReportData.validateRange(null, DAY));
        assertThrows(Problem.class, () -> JobReportData.validateRange(DAY, null));
        assertThrows(Problem.class, () -> JobReportData.validateRange(LocalDate.MIN, DAY));
        assertThrows(Problem.class, () -> JobReportData.validateRange(DAY, LocalDate.MAX));
    }

    @Test void reportsAreAdminOnlyEvenWhenEmpty() throws Exception {
        assertEquals(403, assertThrows(Problem.class, () -> JobReportData.load(db, null, DAY, DAY)).status);
        var worker = App.JSON.createObjectNode().put("id", 2).put("is_admin", false);
        assertEquals(403, assertThrows(Problem.class, () -> JobReportData.load(db, worker, DAY, DAY)).status);
        assertTrue(JobReportData.load(db, admin, DAY, DAY).isEmpty());
    }

    @Test void selectsAllSubmittedOutcomesByRouteDateInStableTeamAndStopOrder() throws Exception {
        route(1, DAY, "Zulu");
        route(2, DAY, "alpha");
        route(3, DAY.plusDays(1), "Bravo");
        route(4, DAY.minusDays(1), "Excluded");
        route(5, DAY.plusDays(2), "Excluded");
        String timestamp = "2026-01-01T08:00:00Z";
        job(11, 1, 1, null, timestamp); // Older reports predate the outcome column.
        job(21, 2, 1, "pending", timestamp);
        job(20, 2, 2, "cancel", timestamp);
        job(22, 2, 3, null, null);
        job(23, 2, 4, null, null);
        change(db, "INSERT INTO job_removals VALUES(20,2,'2026-03-05T00:00:00Z'),(23,4,'2026-03-05T00:00:00Z')");
        change(db, "UPDATE jobs SET outcome_reason='No access' WHERE id=21");
        job(31, 3, 1, "done", timestamp);
        job(41, 4, 1, "done", DAY.toString());
        job(51, 5, 1, "done", DAY.toString());
        var reports = JobReportData.load(db, admin, DAY, DAY.plusDays(1));
        assertEquals(List.of(21,20,11,31), reports.valueStream().map(r -> r.at("/job/id").intValue()).toList());
        assertEquals(List.of("pending","cancel","done","done"),
            reports.valueStream().map(r -> r.at("/job/outcome").textValue()).toList());
        assertEquals("No access", reports.at("/0/job/outcome_reason").textValue());
        assertTrue(reports.at("/1/job/archived").asBoolean());
        assertEquals("2026-03-05T00:00:00Z", reports.at("/1/job/removed_at").textValue());
        assertFalse(reports.at("/2/job/archived").asBoolean());
    }

    @Test void preservesSnapshotCrewWorkAttributionNotesAndLocalAndObjectPhotoMetadata() throws Exception {
        route(1, DAY, "Original crew");
        job(7, 1, 1, "done", "2026-03-01T12:00:00Z");
        change(db, """
            UPDATE jobs SET external_job_id='001-A',stop_label='damage',urgent=1,revision=3,
            feet_buried=14.5,fiber_type='RG 6 (Copper)',tech_needed=1,comments='Check gate',
            bores='[{"type":"sidewalk","feet":4},{"type":"driveway","feet":8}]',
            conduits='[{"type":"existing","feet":3.5}]',items='[{"item":"Vault","quantity":2,"unit":"each"}]',
            last_edited_by=1,last_edited_at='2026-03-02T12:00:00Z' WHERE id=7
            """);
        change(db, """
            INSERT INTO job_notes(id,job_id,author_id,author_name,text,created_at) VALUES
            (1,7,1,'Original dispatcher','Gate code supplied','2026-03-01T00:00:00Z')
            """);
        change(db, "INSERT INTO photos(id,job_id,mime,content) VALUES(19,7,'image/jpeg',x'0af1'),(20,7,'image/png',x'')");
        change(db, """
            INSERT INTO photo_objects(photo_id,destination,object_key,byte_count,sha256)
            VALUES(20,'offline-test-store','photo-key',8241,'test-hash')
            """);

        var report = JobReportData.load(db, admin, DAY, DAY).get(0);
        assertEquals("Original crew", report.at("/route/team_name").textValue());
        assertEquals("Original entity", report.at("/route/entity_name").textValue());
        assertEquals("Crew at dispatch", report.at("/route/crew_names/0").textValue());
        assertEquals(2, report.at("/route/members/0/id").intValue());
        assertEquals("Foreman", report.at("/route/members/0/role").textValue());
        assertFalse(report.at("/route/members/0").has("pay_rate"));
        var job = (ObjectNode) report.get("job");
        var detail = JobDetails.get(db, admin, 7).get("job");
        detail.properties().forEach(entry -> {
            if (!entry.getKey().equals("photos")) assertEquals(entry.getValue(), job.get(entry.getKey()), entry.getKey());
        });
        assertEquals("Crew Member", job.get("submitted_by").textValue());
        assertEquals("Dispatch", job.get("last_edited_by").textValue());
        assertEquals("Original dispatcher", job.at("/notes/0/author").textValue());
        assertEquals(2, job.get("bores").size());
        assertEquals("each", job.at("/items/0/unit").textValue());
        assertEquals(2, job.get("photos").size());
        assertEquals("/api/photos/19", job.at("/photos/0/url").textValue());
        assertEquals("image/jpeg", job.at("/photos/0/mime").textValue());
        assertEquals(2, job.at("/photos/0/byte_count").asLong());
        assertEquals(8241, job.at("/photos/1/byte_count").asLong());
        assertFalse(job.at("/photos/1").has("content"));
        assertFalse(job.at("/photos/1").has("object_key"));
    }
}
