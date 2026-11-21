package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobDetailsTest {
    private Connection db;
    private final ObjectNode admin = App.JSON.createObjectNode().put("id", 1).put("is_admin", true);
    private final ObjectNode assigned = App.JSON.createObjectNode().put("id", 2).put("is_admin", false);
    private final ObjectNode outside = App.JSON.createObjectNode().put("id", 3).put("is_admin", false);

    @BeforeEach void init() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var sql = db.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            Db.initialize(db);
            sql.execute("INSERT INTO people(id,username,password_hash,name,is_admin) VALUES(1,'admin','test','Dispatcher',1)");
            sql.execute("INSERT INTO people(id,username,password_hash,name,role,pay_basis) VALUES(2,'crew','test','Crew A','Lead','daily'),(3,'outside','test','Crew B','Lead','daily')");
            sql.execute("INSERT INTO entities VALUES(1,'Utility')");
            sql.execute("INSERT INTO teams VALUES(1,'North',1)");
            sql.execute("INSERT INTO team_members VALUES(1,2)");
        }
    }

    @AfterEach void close() throws Exception { db.close(); }

    @Test void vehicleBookingsFollowTeamAndDateAndKeepTheOriginalVehicleDetails() throws Exception {
        var truck = App.JSON.createObjectNode().put("name", "Truck A").put("brand", "Ford").put("plate", "001-A");
        Vehicles.save(db, truck, null);
        long vehicleId = Vehicles.list(db).get(0).path("id").asLong();
        var request = App.JSON.createObjectNode().put("team_id", 1).put("date", "2027-01-12");
        request.putArray("vehicle_ids").add(vehicleId);
        request.putArray("addresses").add("First stop");
        var first = Store.createRoute(db, request);
        assertFalse(Vehicles.list(db, "2027-01-12").get(0).path("available").asBoolean());
        assertTrue(Vehicles.list(db, "2027-01-12", 1).get(0).path("available").asBoolean());
        request.putArray("addresses").add("Second stop");
        Store.createRoute(db, request);
        StoreSql.change(db, "INSERT INTO teams(id,name,entity_id) VALUES(2,'South',1)");
        StoreSql.change(db, "INSERT INTO team_members(team_id,person_id) VALUES(2,3)");
        request.put("team_id", 2);
        assertEquals(409, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        assertTrue(Store.teamRoutes(db, admin, 2, "2027-01-12").isEmpty());
        request.put("date", "2027-01-13");
        Store.createRoute(db, request);
        Vehicles.save(db, truck.put("name", "Truck B"), vehicleId);
        assertEquals("Truck A", Store.teamRoutes(db, admin, 1, "2027-01-12").at("/0/vehicles/0/name").asText());
        assertEquals(vehicleId, PreviousCrews.get(db, first.path("id").asLong()).at("/vehicle_ids/0").asLong());
        assertEquals(1, PreviousCrews.list(db, "2027-01-12").size());
        assertEquals(2, PreviousCrews.list(db, "2027-01-13").size());
    }

    private long route(String day, String... addresses) throws Exception {
        var input = App.JSON.createObjectNode().put("date", day).put("team_id", 1);
        var list = input.putArray("addresses");
        for (String address : addresses) list.add(address);
        return Store.createRoute(db, input).at("/jobs/0/id").asLong();
    }

    @Test void wizardTeamAndRouteCommitTogetherAndRollBackOnFinalErrors() throws Exception {
        var team = App.JSON.createObjectNode().put("name", "Marked crew").put("entity_id", 1);
        team.putArray("member_ids").add(3);
        var route = App.JSON.createObjectNode().put("date", Db.today());
        route.putArray("addresses").addObject().put("address", "Wizard job").put("external_job_id", "001-A");
        route.putArray("vehicle_ids").add(1000);
        assertThrows(Problem.class, () -> Store.createTeamRoute(db, team, route));
        assertEquals(1, Store.teams(db).size());
        assertTrue(Store.routes(db, admin, null).isEmpty());
        route.putArray("vehicle_ids");
        var saved = Store.createTeamRoute(db, team, route);
        assertEquals("Marked crew", saved.get("team_name").textValue());
        assertEquals("Utility", saved.get("entity_name").textValue());
        assertEquals(1, saved.get("member_ids").size());
        assertEquals(3, saved.at("/member_ids/0").intValue());
        assertTrue(Store.routes(db, assigned, null).isEmpty());
        assertEquals("001-A", Store.routes(db, outside, null).at("/0/jobs/0/external_job_id").textValue());
        assertEquals(409, assertThrows(Problem.class, () -> Store.createTeamRoute(db, team, route)).status);
        assertEquals(2, Store.teams(db).size());
        assertEquals(1, Store.routes(db, admin, null).size());
    }

    @Test void previousCrewSelectionsSnapshotEachRouteWithoutEditingSavedTeamOrHistory() throws Exception {
        String beforeDate = LocalDate.parse(Db.today()).minusDays(1).toString();
        long oldJob = route(beforeDate, "Original stop");
        StoreSql.change(db, "INSERT INTO entities(name) VALUES('Other utility')");
        var crew = App.JSON.createObjectNode().put("name", "North").put("entity_id", 2).put("source_team_id", 1);
        crew.putArray("member_ids").add(3);
        var route = App.JSON.createObjectNode().put("date", Db.today());
        route.putArray("addresses").add("Next stop");
        var result = Store.createTeamRoute(db, crew, route);
        assertEquals(1, result.get("team_id").intValue());
        assertEquals(2, result.get("entity_id").intValue());
        assertEquals("Other utility", result.get("entity_name").textValue());
        assertEquals(3, result.at("/member_ids/0").intValue());
        assertEquals(2, Store.teams(db).at("/0/member_ids/0").intValue());
        assertEquals(1, Store.teams(db).at("/0/entity_id").intValue());
        assertEquals("Crew A", JobDetails.get(db, admin, oldJob).at("/route/crew_names/0").textValue());
        assertEquals("Utility", Store.routes(db, admin, beforeDate).at("/0/entity_name").textValue());
        assertTrue(Store.routes(db, assigned, null).isEmpty());
        assertEquals("Next stop", Store.routes(db, outside, null).at("/0/jobs/0/address").textValue());
        assertEquals(409, assertThrows(Problem.class, () -> Store.createTeamRoute(db, crew, route)).status);

        crew.put("name", "Distinct crew");
        assertNotEquals(1, Store.createTeamRoute(db, crew, route).get("team_id").intValue());
        assertEquals(2, Store.teams(db).size());
        crew.put("name", "North").put("source_team_id", 100);
        assertEquals(422, assertThrows(Problem.class, () -> Store.createTeamRoute(db, crew, route)).status);
    }

    @Test void repeatableReportDetailsRoundTripAndSurviveOlderEdits() throws Exception {
        long jobId = route(Db.today(), "Report test stop");
        var report = App.JSON.createObjectNode().put("feet_buried", 28).put("fiber_type", "Original fiber")
            .put("revision", 0).put("tech_needed", true).put("comments", "Gate on left.\nCall on arrival.");
        var bores = report.putArray("bores");
        bores.addObject().put("type", "sidewalk").put("feet", 5);
        bores.addObject().put("type", "driveway").put("feet", 15);
        var conduits = report.putArray("conduits");
        conduits.addObject().put("type", "existing").put("feet", new BigDecimal("4.25"));
        conduits.addObject().put("type", "existing").put("feet", 2);
        var items = report.putArray("items");
        items.addObject().put("item", "Fixture A").put("quantity", new BigDecimal("1.5")).put("unit", "box");
        items.addObject().put("item", "Fixture A").put("quantity", 8).put("unit", "each");
        items.addObject().put("item", "Fixture B").put("quantity", 2).put("unit", "");

        assertEquals(404, assertThrows(Problem.class, () -> Store.report(db, outside, jobId, report, List.of())).status);
        var saved = Store.report(db, assigned, jobId, report, List.of());
        for (String key : List.of("bores", "conduits", "items", "tech_needed", "comments"))
            assertEquals(report.get(key), saved.get(key), key);
        var inRoute = Store.routes(db, assigned, null).at("/0/jobs/0");
        for (String key : List.of("bores", "conduits", "items", "tech_needed", "comments"))
            assertEquals(saved.get(key), inRoute.get(key), key);
        assertEquals(409, assertThrows(Problem.class, () -> Store.report(db, admin, jobId, report, List.of())).status);

        var older = App.JSON.createObjectNode().put("revision", 1).put("feet_buried", 30).put("fiber_type", "Original fiber");
        Store.report(db, admin, jobId, older, List.of());
        var edited = JobDetails.get(db, assigned, jobId).get("job");
        assertEquals(2, edited.get("revision").intValue());
        assertEquals(30, edited.get("feet_buried").intValue());
        for (String key : List.of("bores", "conduits", "items", "tech_needed", "comments"))
            assertEquals(saved.get(key), edited.get(key), key);

        older.put("revision", 2).put("tech_needed", false);
        var noTech = Store.report(db, assigned, jobId, older, List.of());
        assertTrue(noTech.get("tech_needed").isBoolean());
        assertFalse(noTech.get("tech_needed").booleanValue());
        older.put("revision", 3).putNull("tech_needed").put("comments", "");
        for (String key : List.of("bores", "conduits", "items")) older.putArray(key);
        var cleared = Store.report(db, assigned, jobId, older, List.of());
        for (String key : List.of("bores", "conduits", "items")) assertTrue(cleared.get(key).isEmpty(), key);
        assertTrue(cleared.get("tech_needed").isNull());
        assertEquals("", cleared.get("comments").textValue());
    }

    @Test void invalidReportLinesLeaveTheJobAvailableForCorrection() throws Exception {
        long jobId = route(Db.today(), "Validation test stop");
        var report = App.JSON.createObjectNode().put("revision", 0).put("feet_buried", 10).put("fiber_type", "Fixture")
            .put("comments", "First submission");
        var item = report.putArray("items").addObject().put("item", "Fixture").put("unit", "each");
        for (String invalidQuantity : List.of("0", "-1", "1.001", "10000001", "one")) {
            item.put("quantity", invalidQuantity);
            assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, jobId, report, List.of())).status);
        }
        item.put("quantity", "1.25");
        report.putArray("bores").addObject().put("type", "existing").put("feet", 5);
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, jobId, report, List.of())).status);
        report.remove("bores");
        report.putArray("conduits").addObject().put("type", "driveway").put("feet", "-2");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, jobId, report, List.of())).status);
        report.remove("conduits");
        report.put("tech_needed", "yes");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, jobId, report, List.of())).status);
        var job = JobDetails.get(db, assigned, jobId).get("job");
        assertEquals(0, job.get("revision").intValue());
        assertTrue(job.get("submitted_at").isNull());
        assertEquals("", job.get("comments").textValue());
        assertTrue(job.get("items").isEmpty());
        report.put("tech_needed", true);
        assertEquals(1, Store.report(db, assigned, jobId, report, List.of()).get("revision").intValue());
    }

    @Test void pendingAndCanceledReportsCanExplainMissingMeasurementsAndBeRevisited() throws Exception {
        long job = route(Db.today(), "Outcome test stop");
        var unsubmitted = JobDetails.get(db, assigned, job).get("job");
        assertTrue(unsubmitted.get("outcome").isNull());
        assertEquals("assigned", unsubmitted.get("status").textValue());
        var report = App.JSON.createObjectNode().put("revision", 0).put("outcome", "pending");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, job, report, List.of())).status);
        report.put("outcome_reason", "Access gate was locked.");
        var pending = Store.report(db, assigned, job, report, List.of());
        assertEquals("pending", pending.get("outcome").textValue());
        assertEquals("pending", pending.get("status").textValue());
        assertTrue(pending.get("feet_buried").isNull());
        assertTrue(pending.get("fiber_type").isNull());
        assertEquals("Access gate was locked.", pending.get("outcome_reason").textValue());
        var oldClient = App.JSON.createObjectNode().put("revision", 1).put("feet_buried", 3).put("fiber_type", "Legacy type");
        Store.report(db, admin, job, oldClient, List.of());
        var inRoute = Store.routes(db, assigned, null).at("/0/jobs/0");
        assertEquals("pending", inRoute.get("status").textValue());
        assertEquals("Access gate was locked.", inRoute.get("outcome_reason").textValue());

        report.put("revision", 2).put("outcome", "cancel").remove("outcome_reason");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, job, report, List.of())).status);
        report.put("outcome_reason", "Owner canceled.");
        var canceled = Store.report(db, assigned, job, report, List.of());
        assertEquals("cancel", canceled.get("status").textValue());
        assertEquals("Owner canceled.", canceled.get("outcome_reason").textValue());
        assertEquals(3, canceled.get("feet_buried").intValue());
        assertEquals("Legacy type", canceled.get("fiber_type").textValue());
        report.put("revision", 3).put("outcome", "done");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, job, report, List.of())).status);
        report.put("feet_buried", 25).put("fiber_type", "Fixture");
        var done = Store.report(db, assigned, job, report, List.of());
        assertEquals("submitted", done.get("status").textValue());
        assertEquals("done", done.get("outcome").textValue());
        assertTrue(done.get("outcome_reason").isNull());
        assertEquals(25, done.get("feet_buried").intValue());
        assertEquals(pending.get("submitted_at"), done.get("submitted_at"));
    }

    @Test void dispatchLabelsAndUrgencyFollowStopsThroughReportsAndRemoval() throws Exception {
        String day = Db.today();
        var request = App.JSON.createObjectNode().put("team_id", 1).put("date", day)
            .put("stop_label", "Drop").put("urgent", true);
        request.putArray("addresses").add("Oak Street").add("Pine Lane");
        var route = Store.createRoute(db, request);
        long routeId = route.path("id").longValue();
        long oak = route.at("/jobs/0/id").longValue(), pine = route.at("/jobs/1/id").longValue();
        for (var stop : route.path("jobs")) {
            assertEquals("drop", stop.get("stop_label").textValue());
            assertTrue(stop.get("urgent").isBoolean() && stop.get("urgent").booleanValue());
        }
        request.put("stop_label", "damage").put("urgent", false);
        assertEquals(409, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        RouteStops.add(db, admin, 1, day, routeId, 0, List.of("Birch Drive", "Elm Road"), "Survey", false);
        var crew = Store.routes(db, assigned, null).get(0);
        assertEquals("survey", crew.at("/jobs/2/stop_label").textValue());
        assertEquals("survey", crew.at("/jobs/3/stop_label").textValue());
        assertFalse(crew.at("/jobs/2/urgent").booleanValue());
        assertTrue(Store.routes(db, outside, null).isEmpty());

        RouteStops.classify(db, admin, 1, day, routeId, 1, oak, "damage", false);
        RouteStops.classify(db, admin, 1, day, routeId, 2, oak, "damage", false);
        assertEquals(2, Store.teamRoutes(db, admin, 1, day).at("/0/revision").intValue());
        var report = App.JSON.createObjectNode().put("revision", 0).put("feet_buried", 20)
            .put("fiber_type", "Drop");
        var submitted = Store.report(db, assigned, oak, report, List.of());
        assertEquals("damage", submitted.get("stop_label").textValue());
        assertTrue(submitted.get("urgent").isBoolean() && !submitted.get("urgent").booleanValue());
        assertEquals("drop", JobDetails.get(db, assigned, pine).at("/job/stop_label").textValue());
        assertEquals(409, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 1, day, routeId, 1, oak, "", true)).status);
        RouteStops.classify(db, admin, 1, day, routeId, 2, oak, "", true);
        var urgentOnly = JobDetails.get(db, assigned, oak).path("job");
        assertEquals("", urgentOnly.get("stop_label").textValue());
        assertTrue(urgentOnly.get("urgent").booleanValue());
        assertEquals(1, urgentOnly.get("revision").intValue());

        RouteStops.remove(db, admin, 1, day, routeId, 3, pine);
        var archived = Store.teamRoutes(db, admin, 1, day).at("/0/removed_jobs/0");
        assertEquals("drop", archived.get("stop_label").textValue());
        assertTrue(archived.get("urgent").isBoolean() && archived.get("urgent").booleanValue());
        assertTrue(JobDetails.get(db, admin, pine).at("/job/urgent").booleanValue());
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 1, day, routeId, 4, pine, "", false)).status);
        assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, assigned, pine)).status);
    }

    @Test void labelingValidatesInputsAndRouteOwnership() throws Exception {
        String day = Db.today();
        long jobId = route(day, "Legacy entry");
        var context = JobDetails.get(db, assigned, jobId);
        assertEquals("", context.at("/job/stop_label").textValue());
        assertTrue(context.at("/job/urgent").isBoolean() && !context.at("/job/urgent").booleanValue());
        long routeId = context.at("/route/id").longValue();
        assertEquals(403, assertThrows(Problem.class, () -> RouteStops.classify(db, assigned, 1, day, routeId, 0, jobId, "survey", true)).status);
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 2, day, routeId, 0, jobId, "survey", true)).status);
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 1, day, routeId, 0, 999, "survey", true)).status);
        assertEquals(422, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 1, day, routeId, 0, jobId, "invalid", true)).status);
        assertEquals(422, assertThrows(Problem.class, () -> RouteStops.add(db, admin, 1, day, routeId, 0, List.of("More"), "invalid", false)).status);
        var request = App.JSON.createObjectNode().put("team_id", 1).put("date", day).put("stop_label", "other");
        request.putArray("addresses").add("New");
        assertEquals(422, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        request.put("stop_label", false);
        assertEquals(422, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        request.put("stop_label", "survey").put("urgent", "yes");
        assertEquals(422, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        var routes = Store.teamRoutes(db, admin, 1, day);
        assertEquals(1, routes.size());
        assertEquals(1, routes.at("/0/jobs").size());
        assertEquals(0, routes.at("/0/revision").intValue());
    }

    @Test void dispatchCanSetPerAddressIdsAndCorrectThemFromEitherAdminScreen() throws Exception {
        String day = Db.today();
        var request = App.JSON.createObjectNode().put("date", day).put("team_id", 1);
        var addresses = request.putArray("addresses");
        addresses.add("Old format");
        addresses.addObject().put("address", "New format").put("external_job_id", " 001A-05 ");
        addresses.addObject().put("address", "Later assignment");
        var route = Store.createRoute(db, request);
        long routeId = route.get("id").longValue(), job = route.at("/jobs/1/id").longValue();
        assertEquals("", route.at("/jobs/0/external_job_id").textValue());
        assertEquals("001A-05", route.at("/jobs/1/external_job_id").textValue());
        assertEquals("", route.at("/jobs/2/external_job_id").textValue());
        ((ObjectNode)addresses.get(1)).put("external_job_id", "Different");
        assertEquals(409, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        RouteStops.addEntries(db, admin, 1, day, routeId, 0,
            List.of(new RouteStops.Stop("Added stop", "001A-05"), new RouteStops.Stop("Blank ID", "")), "damage", true);
        var stops = Store.routes(db, assigned, null).at("/0/jobs");
        assertEquals("001A-05", stops.at("/3/external_job_id").textValue());
        assertEquals("", stops.at("/4/external_job_id").textValue());
        assertEquals("damage", stops.at("/3/stop_label").textValue());
        assertTrue(stops.at("/3/urgent").booleanValue());
        assertTrue(Store.routes(db, outside, null).isEmpty());
        assertEquals(403, assertThrows(Problem.class, () -> JobDetails.updateAddress(db, assigned, job, "New format", 0, "99")).status);

        RouteStops.classify(db, admin, 1, day, routeId, 1, job, "", false, "00B-9");
        assertEquals(409, assertThrows(Problem.class, () -> JobDetails.updateAddress(db, admin, job, "New format", 0, "old")).status);
        var edited = JobDetails.updateAddress(db, admin, job, "Amended address", 1);
        assertEquals("00B-9", edited.get("external_job_id").textValue());
        assertEquals(409, assertThrows(Problem.class, () -> RouteStops.classify(db, admin, 1, day, routeId, 2, job, "", false, "old")).status);
        var report = App.JSON.createObjectNode().put("revision", 2).put("outcome", "pending").put("outcome_reason", "Gate closed");
        assertEquals("00B-9", Store.report(db, assigned, job, report, List.of()).get("external_job_id").textValue());
        report.put("revision", 3).put("external_job_id", "forbidden");
        assertEquals(422, assertThrows(Problem.class, () -> Store.report(db, assigned, job, report, List.of())).status);
        var cleared = JobDetails.updateAddress(db, admin, job, "Amended address", 3, "");
        assertEquals("", cleared.get("external_job_id").textValue());
        assertEquals("Gate closed", cleared.get("outcome_reason").textValue());
        long appended = stops.at("/3/id").longValue();
        RouteStops.remove(db, admin, 1, day, routeId, 4, appended);
        assertEquals("001A-05", Store.teamRoutes(db, admin, 1, day).at("/0/removed_jobs/0/external_job_id").textValue());
        assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, assigned, appended)).status);
    }

    @Test void invalidExternalJobIdsRejectTheEntireDispatch() throws Exception {
        var request = App.JSON.createObjectNode().put("team_id", 1).put("date", Db.today());
        var addresses = request.putArray("addresses");
        addresses.add("Valid stop");
        var invalid = addresses.addObject().put("address", "Invalid stop").put("external_job_id", 123);
        assertEquals(422, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        invalid.put("external_job_id", "a".repeat(121));
        assertEquals(422, assertThrows(Problem.class, () -> Store.createRoute(db, request)).status);
        assertTrue(Store.routes(db, assigned, null).isEmpty());
    }

    @Test void workersSeeNotesOnlyOnAssignedStopsToday() throws Exception {
        LocalDate today = LocalDate.parse(Db.today());
        long job = route(today.toString(), "10 Today Road");
        var note = JobDetails.addNote(db, admin, job, "  Use the south entrance. ");
        assertEquals("Dispatcher", note.get("author").asText());
        assertDoesNotThrow(() -> Instant.parse(note.get("created_at").asText()));
        var context = JobDetails.get(db, assigned, job);
        assertEquals("Use the south entrance.", context.at("/job/notes/0/text").asText());
        assertEquals(today.toString(), context.at("/route/date").asText());
        assertEquals(0, context.at("/job/revision").asInt());
        assertEquals("assigned", context.at("/job/status").asText());
        assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, outside, job)).status);
        assertEquals(403, assertThrows(Problem.class, () -> JobDetails.addNote(db, assigned, job, "Worker note")).status);
        assertEquals(403, assertThrows(Problem.class, () -> JobDetails.updateAddress(db, assigned, job, "Other", 0)).status);
        for (LocalDate date : List.of(today.minusDays(1), today.plusDays(1))) {
            long otherDate = route(date.toString(), "20 Deferred Road");
            assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, assigned, otherDate)).status);
            JobDetails.addNote(db, admin, otherDate, "Access reviewed.");
            assertEquals(date.toString(), JobDetails.get(db, admin, otherDate).at("/route/date").asText());
            assertEquals("Corrected", JobDetails.updateAddress(db, admin, otherDate, "Corrected", 0).get("address").asText());
        }
    }

    @Test void staleCorrectionsAndDuplicateRoutesRollbackCleanly() throws Exception {
        long job = route(Db.today(), "21 Old Road", "22 Other Road");
        route(Db.today(), "21 Duplicate Road", "22 Other Road");
        assertEquals(409, assertThrows(Problem.class, () -> JobDetails.updateAddress(db, admin, job, "21 Duplicate Road", 0)).status);
        assertEquals("21 Old Road", JobDetails.get(db, admin, job).at("/job/address").asText());
        assertEquals(0, JobDetails.updateAddress(db, admin, job, "21 Old Road", 0).get("revision").asInt());
        assertEquals(1, JobDetails.updateAddress(db, admin, job, "21 New Road", 0).get("revision").asInt());
        assertEquals(409, assertThrows(Problem.class, () -> JobDetails.updateAddress(db, admin, job, "Stale Road", 0)).status);
        assertEquals(409, assertThrows(Problem.class, () -> route(Db.today(), "21 New Road", "22 Other Road")).status);
        route(Db.today(), "21 Old Road", "22 Other Road");
    }

    @Test void editingStopsPreservesRemainingReportsAndRevokesWorkerAccessToRemovedStops() throws Exception {
        String day = Db.today();
        long first = route(day, "10 Main", "20 Oak", "30 Elm");
        var original = Store.teamRoutes(db, admin, 1, day).get(0);
        long routeId = original.path("id").longValue();
        long middle = original.at("/jobs/1/id").longValue();
        long last = original.at("/jobs/2/id").longValue();
        var report = App.JSON.createObjectNode().put("revision", 0).put("fiber_type", "12ct").put("feet_buried", 18);
        Store.report(db, assigned, middle, report, List.of());
        Store.report(db, assigned, last, report, List.of());
        JobDetails.addNote(db, admin, middle, "Gate closed");
        long photo = StoreSql.insertId(db, "INSERT INTO photos(job_id,mime,content) VALUES(?,'image/jpeg',?)", middle, new byte[]{(byte)0xFE});
        StoreSql.change(db, "UPDATE team_members SET person_id=3 WHERE team_id=1");

        RouteStops.remove(db, admin, 1, day, routeId, 0, middle);
        var now = Store.routes(db, assigned, null).get(0);
        assertEquals(2, now.path("jobs").size());
        assertEquals(first, now.at("/jobs/0/id").longValue());
        assertEquals(last, now.at("/jobs/1/id").longValue());
        assertEquals(2, now.at("/jobs/1/position").intValue());
        assertEquals(2, JobDetails.get(db, assigned, last).at("/job/position").intValue());
        assertEquals(18, now.at("/jobs/1/feet_buried").intValue());
        assertEquals(1, now.at("/jobs/1/revision").intValue());
        assertFalse(now.has("removed_jobs"));
        assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, assigned, middle)).status);
        assertEquals(404, assertThrows(Problem.class, () -> Store.photo(db, assigned, photo)).status);
        assertEquals(404, assertThrows(Problem.class, () -> Store.report(db, assigned, middle, report, List.of())).status);
        var archived = JobDetails.get(db, admin, middle).get("job");
        assertTrue(archived.hasNonNull("removed_at"));
        assertEquals(2, archived.get("position").intValue());
        assertEquals(2, archived.get("revision").intValue());
        assertEquals(18, archived.get("feet_buried").intValue());
        assertEquals("Gate closed", archived.at("/notes/0/text").textValue());
        assertEquals(photo, archived.at("/photos/0/id").longValue());
        assertArrayEquals(new byte[]{(byte)254}, Store.photo(db, admin, photo).content());
        assertEquals(middle, Store.teamRoutes(db, admin, 1, day).at("/0/removed_jobs/0/id").longValue());

        RouteStops.add(db, admin, 1, day, routeId, 1, List.of(" 40 River ", "50 Park"));
        now = Store.routes(db, assigned, null).get(0);
        assertEquals(4, now.path("jobs").size());
        assertEquals("40 River", now.at("/jobs/2/address").textValue());
        assertEquals("50 Park", now.at("/jobs/3/address").textValue());
        assertEquals(4, now.at("/jobs/3/position").intValue());
        assertEquals(2, now.path("revision").intValue());
        assertTrue(Store.routes(db, outside, null).isEmpty());
        assertEquals(409, assertThrows(Problem.class, () -> RouteStops.add(db, admin, 1, day, routeId, 1, List.of("40 River", "50 Park"))).status);
        assertEquals(4, Store.routes(db, assigned, null).at("/0/jobs").size());
        assertEquals(409, assertThrows(Problem.class, () -> route(day, "10 Main", "30 Elm", "40 River", "50 Park")).status);
        JobDetails.updateAddress(db, admin, last, "31 Elm", 1);
        assertEquals(3, Store.teamRoutes(db, admin, 1, day).at("/0/revision").intValue());
        assertEquals(409, assertThrows(Problem.class, () -> route(day, "10 Main", "31 Elm", "40 River", "50 Park")).status);
    }

    @Test void scopeConflictsAndEmptyRoutesAreHandledWithoutLosingAssignments() throws Exception {
        String day = Db.today();
        long one = route(day, "A");
        long two = route(day, "A", "B");
        var routes = Store.teamRoutes(db, admin, 1, day);
        long first = routes.at("/0/id").longValue(), second = routes.at("/1/id").longValue(), end = routes.at("/1/jobs/1/id").longValue();
        assertEquals(403, assertThrows(Problem.class, () -> RouteStops.add(db, assigned, 1, day, first, 0, List.of("C"))).status);
        assertEquals(403, assertThrows(Problem.class, () -> RouteStops.remove(db, assigned, 1, day, first, 0, one)).status);
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.remove(db, admin, 2, day, first, 0, one)).status);
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.add(db, admin, 1, "2000-01-01", first, 0, List.of("C"))).status);
        assertEquals(404, assertThrows(Problem.class, () -> RouteStops.remove(db, admin, 1, day, first, 0, two)).status);
        assertEquals(409, assertThrows(Problem.class, () -> RouteStops.add(db, admin, 1, day, first, 0, List.of("B"))).status);
        assertEquals(409, assertThrows(Problem.class, () -> RouteStops.remove(db, admin, 1, day, second, 0, end)).status);
        assertEquals(422, assertThrows(Problem.class, () -> RouteStops.add(db, admin, 1, day, first, 0, List.of("C", " "))).status);
        assertEquals(0, JobDetails.get(db, assigned, end).at("/job/revision").intValue());
        RouteStops.remove(db, admin, 1, day, first, 0, one);
        RouteStops.remove(db, admin, 1, day, second, 0, two);
        RouteStops.remove(db, admin, 1, day, second, 1, end);
        assertTrue(Store.routes(db, assigned, null).isEmpty());
        assertEquals(2, Store.teamRoutes(db, admin, 1, day).size());
        assertEquals(1, JobDetails.get(db, admin, end).at("/job/position").intValue());
        RouteStops.add(db, admin, 1, day, first, 1, List.of("A"));
        assertEquals(1, Store.routes(db, assigned, null).at("/0/jobs/0/position").intValue());
        assertNotEquals(one, Store.routes(db, assigned, null).at("/0/jobs/0/id").longValue());
    }

    @Test void workerCannotReopenOrReviseTheirReportOutsideItsWorkDate() throws Exception {
        LocalDate today = LocalDate.parse(Db.today());
        long job = route(today.toString(), "15 Crew Road");
        var report = App.JSON.createObjectNode().put("revision", 0).put("fiber_type", "12ct").put("feet_buried", 36);
        Store.report(db, assigned, job, report, List.of());
        JobDetails.addNote(db, admin, job, "Use side gate.");
        long photo = StoreSql.insertId(db, "INSERT INTO photos(job_id,mime,content) VALUES(?,'image/jpeg',?)",
            job, new byte[]{(byte)255,(byte)216,(byte)255});
        long routeId = JobDetails.get(db, assigned, job).at("/route/id").longValue();
        assertEquals("image/jpeg", Store.photo(db, assigned, photo).mime());
        assertEquals(1, Store.routes(db, assigned, null).size());

        report.put("revision", 1).put("feet_buried", 50).putArray("keep_photo_ids");
        for (LocalDate otherDay : List.of(today.minusDays(1), today.plusDays(1))) {
            StoreSql.change(db, "UPDATE routes SET date=? WHERE id=?", otherDay.toString(), routeId);
            assertTrue(Store.routes(db, assigned, null).isEmpty());
            assertTrue(Store.routes(db, assigned, otherDay.toString()).isEmpty());
            assertEquals(404, assertThrows(Problem.class, () -> JobDetails.get(db, assigned, job)).status);
            assertEquals(404, assertThrows(Problem.class, () -> Store.photo(db, assigned, photo)).status);
            assertEquals(404, assertThrows(Problem.class, () -> Store.report(db, assigned, job, report, List.of())).status);
            var saved = JobDetails.get(db, admin, job).get("job");
            assertEquals(36, saved.get("feet_buried").asInt());
            assertEquals(1, saved.get("revision").asInt());
            assertEquals(photo, saved.at("/photos/0/id").longValue());
            assertEquals("Use side gate.", saved.at("/notes/0/text").asText());
        }
    }

    @Test void adminCorrectionsKeepOriginalReportAndNotesAttribution() throws Exception {
        long job = route(Db.today(), "30 Crew Road");
        var report = App.JSON.createObjectNode().put("revision", 0).put("fiber_type", "12ct").put("feet_buried", "97.5");
        var original = Store.report(db, assigned, job, report, List.of());
        var note = JobDetails.addNote(db, admin, job, "Corrected footage confirmed.");
        report.put("revision", 1).put("feet_buried", "103");
        var corrected = Store.report(db, admin, job, report, List.of());
        assertEquals(2, corrected.get("revision").asInt());
        assertEquals(103, corrected.get("feet_buried").asInt());
        assertEquals(original.get("submitted_at"), corrected.get("submitted_at"));
        assertEquals("Crew A", corrected.get("submitted_by").asText());
        assertEquals("Dispatcher", corrected.get("last_edited_by").asText());
        assertDoesNotThrow(() -> Instant.parse(corrected.get("last_edited_at").asText()));
        assertEquals(note, JobDetails.get(db, assigned, job).at("/job/notes/0"));
        assertEquals(409, assertThrows(Problem.class, () -> Store.report(db, admin, job, report, List.of())).status);
        long historical = route(LocalDate.parse(Db.today()).minusDays(1).toString(), "70 Earlier Road");
        report.put("revision", 0);
        assertEquals("Dispatcher", Store.report(db, admin, historical, report, List.of()).get("submitted_by").asText());
    }
}
