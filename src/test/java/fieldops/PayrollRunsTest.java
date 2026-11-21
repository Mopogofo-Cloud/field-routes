package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

class PayrollRunsTest {
    private static final LocalDate CHECK_DATE = LocalDate.of(2020, 11, 20);

    Connection openFixture() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private Connection fixture() throws Exception {
        var db = openFixture();
        Db.initialize(db);
        change(db, "INSERT INTO entities(id,name) VALUES(1,'Office')");
        change(db, "INSERT INTO teams(id,name,entity_id) VALUES(1,'First',1)");
        for (int i = 1; i <= 2; i++) {
            change(db, """
                INSERT INTO people(id,name,username,password_hash,role,pay_basis,address)
                VALUES(?,?,?,'example','Crew',?,'Original mailing address')
                """, i, "Person " + i, "worker" + i, i == 1 ? "production" : "daily");
            change(db, "INSERT INTO team_members(team_id,person_id) VALUES(1,?)", i);
        }
        var req = App.JSON.createObjectNode().put("team_id",1).put("date","2020-11-13");
        req.putArray("addresses").add("Work address");
        Store.createRoute(db, req);
        change(db, "UPDATE jobs SET feet_buried=18,submitted_at='2020-11-14T10:00:00Z',outcome='done'");
        var config = App.JSON.createObjectNode().put("revision",0);
        config.putArray("rates");
        config.putArray("formulas").addObject().put("label","Footage").put("basis","production")
            .put("expression","feet_buried * 2").put("footage_credit","split");
        EntityPayroll.save(db,1,config);
        return db;
    }

    @Test void partialAndLateGenerationPreserveIssuedAmountsAndCannotReusePaperNumbers() throws Exception {
        try (var db = fixture()) {
            var partial = PayrollRuns.generate(db,CHECK_DATE);
            assertEquals("partial", partial.get("run_status").asText());
            assertEquals("generated",partial.at("/workers/0/statement_status").asText());
            assertFalse(partial.at("/workers/0/source_changed").asBoolean());
            assertEquals(18,partial.at("/workers/0/gross").intValue());
            assertEquals("needs_review",partial.at("/workers/1/status").asText());
            assertTrue(partial.at("/workers/1/gross").isNull());
            PayrollRuns.numberChecks(db,CHECK_DATE,1L,1001);
            PayrollRuns.numberChecks(db,CHECK_DATE,1L,1001);
            assertThrows(Problem.class,() -> PayrollRuns.numberChecks(db,CHECK_DATE,1L,1002));
            change(db,"UPDATE jobs SET feet_buried=100");
            change(db,"UPDATE people SET address='New mailing address'");
            ObjectNode config = EntityPayroll.load(db,1);
            config.remove("entity_id");
            config.withArray("formulas").addObject().put("basis","daily").put("label","Daily")
                .put("expression","150 * days_worked").put("footage_credit","full");
            EntityPayroll.save(db,1,config);
            var issued = PayrollRuns.generate(db,CHECK_DATE);
            assertEquals("generated",issued.path("run_status").asText());
            assertEquals(18,issued.at("/workers/0/gross").asInt());
            assertTrue(issued.at("/workers/0/source_changed").asBoolean());
            assertEquals("Original mailing address",issued.at("/workers/0/mailing_address").asText());
            assertEquals(150,issued.at("/workers/1/gross").asInt());
            assertThrows(Problem.class,() -> PayrollRuns.numberChecks(db,CHECK_DATE,null,1001));
            PayrollRuns.numberChecks(db,CHECK_DATE,null,1002);
            assertEquals(1002,PayrollRuns.view(db,CHECK_DATE).at("/workers/1/check_number").asInt());
            assertThrows(Problem.class,() -> PayrollRuns.generate(db,PayrollRuns.latestFriday().plusWeeks(1)));
        }
    }

    @Test void schedulerCatchesMissedFridaysAndRepeatedRunsKeepTheSameStatement() throws Exception {
        try (var db = fixture()) {
            PayrollRuns.runDue(db,CHECK_DATE.minusWeeks(1));
            assertEquals(0,one(db,"SELECT count(*) AS n FROM payroll_statements").path("n").asInt());
            PayrollRuns.runDue(db,CHECK_DATE.plusDays(10));
            PayrollRuns.runDue(db,CHECK_DATE.plusDays(10));
            assertEquals(1,one(db,"SELECT count(*) AS n FROM payroll_statements").path("n").asInt());
            assertEquals("2020-11-27",one(db,"SELECT last_friday FROM payroll_schedule").path("last_friday").asText());
            PayrollRuns.savePrintSettings(db,new BigDecimal("1.2"),new BigDecimal("-2.5"));
            assertEquals(0,new BigDecimal("-2.5").compareTo(PayrollRuns.printSettings(db).path("y_mm").decimalValue()));
            assertThrows(Problem.class,() -> PayrollRuns.savePrintSettings(db,new BigDecimal("1.23"),BigDecimal.ZERO));
        }
    }

    @Test void pendingDayDecisionsRequireAnAdminAndTrackTheReportVersion() throws Exception {
        try (var db = fixture()) {
            change(db,"INSERT INTO people(id,name,username,password_hash,is_admin) VALUES(3,'Office','office','test',1)");
            change(db,"UPDATE jobs SET outcome='pending',outcome_reason='Return visit'");
            var pending=PayrollRuns.view(db,CHECK_DATE);
            String key=pending.at("/workers/0/details/0/review_key").asText();
            assertTrue(key.matches("[0-9a-f]{64}"));
            assertTrue(pending.at("/workers/0/gross").isNull());
            assertEquals(403,assertThrows(Problem.class,() -> PayrollRuns.reviewDay(db,CHECK_DATE,1,key,"none","include",1)).status);
            PayrollRuns.reviewDay(db,CHECK_DATE,1,key,"none","include",3);
            assertEquals(18,PayrollRuns.view(db,CHECK_DATE).at("/workers/0/gross").asInt());
            assertEquals(409,assertThrows(Problem.class,() -> PayrollRuns.reviewDay(db,CHECK_DATE,1,key,"none","exclude",3)).status);
            change(db,"UPDATE jobs SET revision=revision+1,feet_buried=20");
            assertEquals(409,assertThrows(Problem.class,() -> PayrollRuns.reviewDay(db,CHECK_DATE,1,key,"include","exclude",3)).status);
            var updated=PayrollRuns.view(db,CHECK_DATE);
            String newKey=updated.at("/workers/0/details/0/review_key").asText();
            assertNotEquals(key,newKey);
            assertTrue(updated.at("/workers/0/gross").isNull());
            PayrollRuns.reviewDay(db,CHECK_DATE,1,newKey,"none","include",3);
            assertEquals(20,PayrollRuns.generate(db,CHECK_DATE).at("/workers/0/gross").asInt());
            assertEquals(409,assertThrows(Problem.class,() -> PayrollRuns.reviewDay(db,CHECK_DATE,1,newKey,"include","exclude",3)).status);
        }
    }
}
