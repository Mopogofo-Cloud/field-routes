package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static fieldops.StoreSql.*;

class WeeklyPayrollTest {
    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 27);
    private Connection db;

    Connection openFixture() throws Exception {
        var sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        change(sqlite, "PRAGMA foreign_keys=ON");
        return sqlite;
    }

    @BeforeEach void init() throws Exception {
        db = openFixture();
        Db.initialize(db);
        change(db, "INSERT INTO entities VALUES(1,'Utility A'),(2,'Utility B')");
        change(db, "INSERT INTO teams VALUES(1,'Crew A',1),(2,'Crew B',2)");
        change(db, """
            INSERT INTO people(id,username,name,password_hash,role,pay_basis,rate_cents)
            VALUES(1,'a','Employee A','test','Lead','production',1200),(2,'b','Employee B','test','Installer','daily',25000)
            """);
        change(db, "INSERT INTO team_members VALUES(1,1),(1,2),(2,2)");
    }

    @AfterEach void close() throws Exception { db.close(); }

    private ObjectNode config() {
        var config = App.JSON.createObjectNode().put("revision", 0);
        config.putArray("rates");
        config.putArray("formulas");
        return config;
    }

    private static void formula(ObjectNode config, String label, String basis, String expression, String credit) {
        config.withArray("formulas").addObject().put("label", label).put("basis", basis)
            .put("expression", expression).put("footage_credit", credit);
    }

    private ObjectNode route(String date, int team, String... addresses) throws Exception {
        var request = App.JSON.createObjectNode().put("date", date).put("team_id", team);
        var stops = request.putArray("addresses");
        for (String address : addresses) stops.add(address);
        return Store.createRoute(db, request);
    }

    private long completed(ObjectNode route, int i, double feet) throws Exception {
        long job = route.at("/jobs/" + i + "/id").longValue();
        change(db, "UPDATE jobs SET submitted_at='2026-11-27T07:30:00Z',feet_buried=?,outcome='done' WHERE id=?", feet, job);
        return job;
    }

    private ObjectNode calculate() throws Exception {
        return transaction(db, false, () -> WeeklyPayroll.calculate(db, FRIDAY));
    }

    private static void decimal(String expected, JsonNode value) {
        assertTrue(value.isNumber());
        assertEquals(0, new BigDecimal(expected).compareTo(value.decimalValue()));
    }

    @Test void importedRatesEnterPayrollOnlyThroughAnExplicitEntityFormula() throws Exception {
        var manifest = (ObjectNode)EntityRateCards.JSON.readTree("""
            {"listed":[{"id":71,"companyName":"Utility A"}],"entities":[{"id":71,"companyName":"Utility A",
            "billingModel":"BASE_PER_FOOT","boreRatePerFoot":2.712345,"items":[],"footageTiers":[],"roleRates":[]}],
            "itemNames":{},"roleNames":{}}
            """);
        EntityRateCards.importCatalog(db, manifest);
        completed(route("2026-11-19", 1, "First"), 0, 10);
        assertTrue(calculate().at("/workers/0/gross").isNull());
        var draft = config();
        draft.put("revision", EntityPayroll.load(db, 1).get("revision").longValue());
        formula(draft, "Measured rate", "production", "wg_bore_rate_per_foot * feet_buried", "full");
        EntityPayroll.save(db, 1, draft);
        var worker = calculate().at("/workers/0");
        decimal("27.12", worker.get("gross"));
        decimal("2.712345", worker.at("/details/0/inputs/wg_bore_rate_per_foot"));
        assertFalse(EntityPayroll.load(db, 2).has("rate_cards"));
    }

    @Test void comcastRoadRateUsesOnlyReportedRoadBoreFeetAndRechecksExistingFormulas() throws Exception {
        change(db, "UPDATE entities SET name='COMCAST - SUBCONTRACTOR 2026' WHERE id=1");
        EntityRateCards.importCatalog(db, (ObjectNode)EntityRateCards.JSON.readTree("""
            {"listed":[{"id":2,"companyName":"COMCAST - SUBCONTRACTOR 2026"}],
             "entities":[{"id":2,"companyName":"COMCAST - SUBCONTRACTOR 2026","billingModel":"TIERED_FLAT",
             "boreRatePerFoot":6.00,"boreRoadBoreRate":null}],"roleNames":{},"itemNames":{}}
            """));
        long job = completed(route("2026-11-19", 1, "Sample drop"), 0, 126);
        change(db,"""
            UPDATE jobs SET bores='[{"type":"sidewalk","feet":5},{"type":"driveway","feet":15}]',
            conduits='[{"type":"driveway","feet":15}]' WHERE id=?
            """,job);
        var draft = config();
        formula(draft, "Road bore component", "production", "wg_bore_rate_per_foot * (sidewalk_bore_feet + driveway_bore_feet)", "full");
        assertThrows(Problem.class, () -> EntityPayroll.save(db,1,draft));
        ((ObjectNode)draft.at("/formulas/0")).put("expression","wg_bore_rate_per_foot * road_bore_feet");
        EntityPayroll.save(db,1,draft);
        decimal("0",calculate().at("/workers/0/gross"));
        change(db, "UPDATE jobs SET bores=? WHERE id=?",
            "[{\"type\":\"sidewalk\",\"feet\":5},{\"type\":\"driveway\",\"feet\":15},{\"type\":\"road\",\"feet\":3}]", job);
        decimal("18",calculate().at("/workers/0/gross"));
        // A rule already stored by a previous release must receive the same check on payday.
        change(db,"UPDATE entity_payroll_formulas SET expression='driveway_bore_feet * wg_bore_rate_per_foot' WHERE entity_id=1");
        var worker = calculate().at("/workers/0");
        assertTrue(worker.get("gross").isNull());
        assertEquals("needs_review",worker.get("status").asText());
    }

    @Test void eligibleDoneStopsAggregatePerDayWithEachFormulasOwnFootageCredit() throws Exception {
        var config = config();
        config.withArray("rates").addObject().put("key", "split_rate").put("label", "Split").put("amount", "0.4000");
        formula(config, "Buried work", "production", "split_rate * feet_buried", "split");
        formula(config, "Bores", "production", "sidewalk_bore_feet + driveway_bore_feet + road_conduit_feet", "full");
        long item = one(db, "SELECT id FROM report_items WHERE name='Vault'").get("id").longValue();
        formula(config, "Units", "production", "item_" + item + " + urgent_jobs + tech_jobs + drop_jobs + completed_jobs", "split");
        formula(config, "Day rate", "daily", "days_worked * profile_rate", "full");
        EntityPayroll.save(db, 1, config);

        var mon = route("2026-11-16", 1, "One", "Pending", "Unsubmitted", "Canceled", "Removed");
        long one = completed(mon, 0, 5.5);
        change(db, """
            UPDATE jobs SET outcome=NULL,stop_label='drop',urgent=1,tech_needed=1,
            bores='[{"type":"driveway","feet":15},{"type":"sidewalk","feet":5}]',
            conduits='[{"type":"road","feet":7}]',items='[{"item":"Vault","unit":"ea","quantity":1.25}]' WHERE id=?
            """, one);
        change(db, "UPDATE jobs SET submitted_at='2026-11-20',outcome='pending',feet_buried=40 WHERE address='Pending'");
        change(db, "UPDATE jobs SET submitted_at='2026-11-20',outcome='cancel',feet_buried=40 WHERE address='Canceled'");
        long removed = completed(mon, 4, 40);
        change(db, "INSERT INTO job_removals(job_id,position,removed_at) VALUES(?,5,'2026-11-16')", removed);
        completed(route("2026-11-16", 1, "Two"), 0, 4.5);
        completed(route("2026-11-22", 1, "Sunday"), 0, 8);
        completed(route("2026-11-15", 1, "Before"), 0, 20);
        completed(route("2026-11-23", 1, "After"), 0, 20);
        change(db, "UPDATE people SET rate_cents=5,name='Changed profile' WHERE id=2");
        change(db, "DELETE FROM team_members");
        var run = calculate();
        assertEquals("2026-11-16", run.get("start_date").asText());
        assertEquals("2026-11-22", run.get("end_date").asText());
        assertEquals(2, run.get("workers").size());
        var production = run.at("/workers/0");
        assertEquals(2, production.get("days_worked").intValue());
        decimal("18", production.get("feet_buried"));
        decimal("37.85", production.get("gross")); // Split footage 3.60, full runs 27, unit and tags 7.25.
        assertEquals(6, production.get("details").size());
        var line = production.at("/details/0");
        assertEquals("split", line.get("footage_credit").asText());
        decimal("10", line.get("raw_feet_buried"));
        decimal("5", line.get("credited_feet_buried"));
        decimal("2", line.get("amount"));
        decimal("0.40", line.at("/inputs/split_rate"));
        assertEquals(2, line.get("crew_size").intValue());
        assertEquals(2, line.get("route_ids").size());
        assertEquals(2, line.get("job_ids").size());
        assertEquals("ea", line.at("/reported_items/0/unit").asText());
        var daily = run.at("/workers/1");
        assertEquals("Employee B", daily.get("name").asText());
        assertEquals(2, daily.get("details").size());
        decimal("500", daily.get("gross"));
        assertEquals("ready", daily.get("status").asText());
    }

    @Test void missingOrAmbiguousInputsKeepGrossUnresolvedAndCompletePayableLinesVisible() throws Exception {
        var cfg = config();
        formula(cfg, "Daily base", "daily", "100", "full");
        formula(cfg, "Per job", "daily", "completed_jobs", "full");
        formula(cfg, "Production", "production", "profile_rate * feet_buried", "split");
        EntityPayroll.save(db, 1, cfg);
        var a = route("2026-11-20", 1, "First");
        change(db, "UPDATE assignments SET rate_cents=NULL WHERE person_id=1");
        completed(a, 0, 20);
        completed(route("2026-11-20", 2, "Second"), 0, 12);
        var run = calculate();
        var production = run.at("/workers/0");
        assertTrue(production.get("gross").isNull());
        assertTrue(production.at("/details/0/reason").asText().contains("profile rate"));
        var daily = run.at("/workers/1");
        assertEquals(1, daily.get("days_worked").asInt());
        assertEquals("needs_review", daily.get("status").asText());
        assertTrue(daily.get("gross").isNull());
        assertTrue(daily.at("/details/0/reason").asText().contains("multiple payroll groups"));
        decimal("1", daily.at("/details/1/amount"));
        assertTrue(daily.at("/details/2/reason").asText().contains("No daily formula"));
    }

    @Test void countsBoreEntriesBySurfaceIndependentlyOfCreditConduitAndCatalogItems() throws Exception {
        var cfg = config();
        String expression = "driveway_bore_count + sidewalk_bore_count + road_bore_count + road_bore_feet";
        formula(cfg, "Count and split footage", "production", expression, "split");
        formula(cfg, "Count and full footage", "daily", expression, "full");
        EntityPayroll.save(db, 1, cfg);
        long sample = completed(route("2026-11-19", 1, "Sample drop"), 0, 126);
        change(db, """
            UPDATE jobs SET bores='[{"type":"sidewalk","feet":5},{"type":"driveway","feet":15}]',
            conduits='[{"type":"driveway","feet":15}]',
            items='[{"item":"Vault","unit":"each","quantity":1},{"item":"Riser","unit":"each","quantity":1}]' WHERE id=?
            """, sample);
        long next = completed(route("2026-11-20", 1, "Next drop"), 0, 70);
        change(db, """
            UPDATE jobs SET conduits='[{"type":"road","feet":30}]',
            items='[{"item":"Road Bore","unit":"each","quantity":5}]' WHERE id=?
            """, next);

        var before = calculate().get("workers");
        for (var worker : before) {
            var firstDay = worker.at("/details/0/inputs");
            decimal("1", firstDay.get("sidewalk_bore_count"));
            decimal("1", firstDay.get("driveway_bore_count"));
            decimal("0", firstDay.get("road_bore_count"));
            decimal("0", worker.at("/details/1/inputs/road_bore_count"));
        }
        change(db, """
            UPDATE jobs SET bores='[{"type":"road","feet":20},{"type":"road","feet":4}]' WHERE id=?
            """, next);
        var after = calculate().get("workers");
        for (var worker : after) {
            var inputs = worker.at("/details/1/inputs");
            decimal("2", inputs.get("road_bore_count"));
            decimal("0", inputs.get("sidewalk_bore_count"));
            decimal("0", inputs.get("driveway_bore_count"));
        }
        decimal("12", after.at("/0/details/1/inputs/road_bore_feet"));
        decimal("24", after.at("/1/details/1/inputs/road_bore_feet"));
    }

    @Test void amountsRoundOnEachLineAndIncompatibleItemUnitsAndNegativeNetRequireReview() throws Exception {
        var cfg = config();
        formula(cfg, "First", "daily", "0.005", "full");
        formula(cfg, "Second", "daily", "0.005", "full");
        long item = one(db, "SELECT id FROM report_items WHERE name='Vault'").get("id").asLong();
        formula(cfg, "Items", "production", "item_" + item, "full");
        EntityPayroll.save(db, 1, cfg);
        long job = completed(route("2026-11-17", 1, "Fixture stop"), 0, 0);
        change(db, """
            UPDATE jobs SET items='[{"item":"Vault","unit":"ft","quantity":3},{"item":"Vault","unit":"ea","quantity":2}]' WHERE id=?
            """, job);
        var run = calculate();
        assertTrue(run.at("/workers/0/gross").isNull());
        assertTrue(run.at("/workers/0/details/0/reason").asText().contains("different units"));
        decimal("0.02", run.at("/workers/1/gross"));
        cfg.put("revision", 1).putArray("formulas");
        formula(cfg, "Correction", "daily", "-3", "full");
        EntityPayroll.save(db, 1, cfg);
        var negative = calculate().at("/workers/1");
        assertTrue(negative.get("gross").isNull());
        assertTrue(negative.get("reason").asText().contains("negative"));
        assertThrows(Problem.class, () -> WeeklyPayroll.calculate(db, FRIDAY.plusDays(1)));
    }

    @Test void pendingAndCanceledOnlyDayRequiresSeparateDecisionsAndReportEditsInvalidateThem() throws Exception {
        change(db, "INSERT INTO people(id,username,name,password_hash,is_admin) VALUES(50,'manager','Manager','test',1)");
        var cfg = config();
        formula(cfg, "Daily", "daily", "days_worked * profile_rate", "full");
        formula(cfg, "Buried", "production", "feet_buried + completed_jobs", "split");
        EntityPayroll.save(db, 1, cfg);
        var stops = route("2026-11-18", 1, "Access issue", "Owner canceled", "Unsubmitted");
        long first = stops.at("/jobs/0/id").asLong(), second = stops.at("/jobs/1/id").asLong();
        change(db, "UPDATE jobs SET submitted_at='2026-11-18',outcome='pending',outcome_reason='Gate closed',feet_buried=20 WHERE id=?", first);
        change(db, "UPDATE jobs SET submitted_at='2026-11-18',outcome='cancel',outcome_reason='Owner canceled' WHERE id=?", second);
        var onHold = calculate();
        var line = onHold.at("/workers/0/details/0");
        String key = line.get("review_key").asText();
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertEquals("none", line.get("review_decision").asText());
        assertEquals(1, line.get("pending_jobs").asInt());
        assertEquals(1, line.get("canceled_jobs").asInt());
        assertEquals("Gate closed", line.at("/report_outcomes/0/reason").asText());
        assertEquals("needs_review", onHold.at("/workers/0/status").asText());
        assertTrue(onHold.at("/workers/1/gross").isNull());
        assertEquals(0, onHold.at("/workers/1/days_worked").asInt());
        assertEquals(key, calculate().at("/workers/0/details/0/review_key").asText());

        PayrollRuns.reviewDay(db, FRIDAY, 1, key, "none", "include", 50);
        PayrollRuns.reviewDay(db, FRIDAY, 2, key, "none", "exclude", 50);
        var resolved = calculate();
        assertEquals("include", resolved.at("/workers/0/details/0/review_decision").asText());
        decimal("10", resolved.at("/workers/0/gross"));
        decimal("0", resolved.at("/workers/0/details/0/inputs/completed_jobs"));
        assertEquals(1, resolved.at("/workers/0/days_worked").asInt());
        assertEquals("exclude", resolved.at("/workers/1/details/0/review_decision").asText());
        decimal("0", resolved.at("/workers/1/gross"));
        assertEquals(0, resolved.at("/workers/1/days_worked").asInt());
        PayrollRuns.reviewDay(db, FRIDAY, 2, key, "exclude", "include", 50);
        decimal("250", calculate().at("/workers/1/gross"));
        change(db, "UPDATE jobs SET feet_buried=23,revision=revision+1 WHERE id=?", first);
        var invalidated = calculate();
        assertNotEquals(key, invalidated.at("/workers/0/details/0/review_key").asText());
        assertTrue(invalidated.at("/workers/0/gross").isNull());
        assertTrue(invalidated.at("/workers/1/gross").isNull());
        assertEquals(409, assertThrows(Problem.class, () -> PayrollRuns.reviewDay(db, FRIDAY, 2, key, "include", "exclude", 50)).status);

        change(db, "UPDATE jobs SET outcome='done',feet_buried=4 WHERE id=?", second);
        var mixed = calculate();
        assertFalse(mixed.at("/workers/0/details/0").has("review_key"));
        assertEquals(1, mixed.at("/workers/0/details/0/job_ids").size());
        assertEquals(second, mixed.at("/workers/0/details/0/job_ids/0").asLong());
        decimal("3", mixed.at("/workers/0/gross"));
        decimal("250", mixed.at("/workers/1/gross"));
    }
}
