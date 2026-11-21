package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityPayrollTest {
    private Connection db;

    @BeforeEach void init() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        StoreSql.change(db, "PRAGMA foreign_keys=ON");
        Db.initialize(db);
        StoreSql.change(db, "INSERT INTO entities VALUES(1,'Fixture A'),(2,'Fixture B')");
    }

    @AfterEach void close() throws Exception { db.close(); }

    private ObjectNode settings(long revision) {
        var draft = App.JSON.createObjectNode().put("revision", revision);
        draft.putArray("rates").addObject().put("key", "fixture_rate").put("label", "Fixture").put("amount", "0.0123").put("unit", "ft");
        draft.putArray("formulas").addObject().put("basis", "production").put("label", "Measured work").put("expression", "fixture_rate * feet_buried");
        return draft;
    }

    @Test void onlyExplicitlyConfiguredEntitiesHaveRatesAndHistoryRemainsIntact() throws Exception {
        EntityCatalogImport.run(db);
        for (var entity : Store.entities(db)) {
            var blank = EntityPayroll.load(db, entity.get("id").longValue());
            assertEquals(0, blank.get("revision").longValue());
            assertTrue(blank.get("rates").isEmpty());
            assertTrue(blank.get("formulas").isEmpty());
        }
        StoreSql.change(db, "INSERT INTO people(id,name,username,password_hash,pay_basis,rate_cents) VALUES(1,'Alex','alex','test','daily',567)");
        StoreSql.change(db, "INSERT INTO teams VALUES(1,'East',1)");
        StoreSql.change(db, "INSERT INTO team_members VALUES(1,1)");
        var dispatch = App.JSON.createObjectNode().put("date", "2020-04-07").put("team_id", 1);
        dispatch.putArray("addresses").add("April stop");
        var route = Store.createRoute(db, dispatch);
        var draft = settings(0);
        draft.withArray("rates").addObject().put("key", "fixture_zero").put("label", "Zero").put("amount", 0);
        draft.withArray("formulas").addObject().put("basis", "daily").put("label", "Measured work").put("expression", "days_worked * profile_rate");
        var stored = EntityPayroll.save(db, 1, draft);
        assertEquals(1, stored.get("revision").longValue());
        assertEquals(new BigDecimal("0.0123"), stored.at("/rates/0/amount").decimalValue());
        assertEquals(0, stored.at("/rates/1/amount").decimalValue().signum());
        assertEquals("", stored.at("/rates/1/unit").textValue());
        assertEquals("daily", stored.at("/formulas/1/basis").textValue());
        Db.initialize(db);
        assertEquals(stored, EntityPayroll.load(db, 1));
        assertTrue(EntityPayroll.load(db, 2).get("rates").isEmpty());
        var admin = App.JSON.createObjectNode().put("is_admin", true);
        assertEquals(route, Store.routes(db, admin, "2020-04-07").get(0));
    }

    @Test void staleOrInvalidReplacementCannotErasePreviouslyPublishedSettings() throws Exception {
        var baseline = EntityPayroll.save(db, 1, settings(0));
        assertEquals(409, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, settings(0))).status);
        var draft = settings(1);
        var rate = (ObjectNode) draft.at("/rates/0");
        var formula = (ObjectNode) draft.at("/formulas/0");
        for (String invalid : List.of("-1", "0.00001", "", "1.001e20", "NaN", "1000000000.0001")) {
            rate.put("amount", invalid);
            assertEquals(422, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, draft)).status);
        }
        rate.put("amount", "1000000000.0000");
        for (String invalid : List.of("1fixture", "UPPER", "feet_buried", "item_200")) {
            rate.put("key", invalid);
            assertEquals(422, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, draft)).status);
        }
        rate.put("key", "fixture_rate");
        draft.withArray("rates").add(rate.deepCopy());
        assertEquals(422, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, draft)).status);
        draft.withArray("rates").remove(1);
        draft.withArray("formulas").add(formula.deepCopy().put("label", "MEASURED WORK"));
        assertEquals(422, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, draft)).status);
        draft.withArray("formulas").remove(1);
        formula.put("expression", "foreign_rate * feet_buried");
        assertEquals(422, assertThrows(Problem.class, () -> EntityPayroll.save(db, 1, draft)).status);
        assertEquals(baseline, EntityPayroll.load(db, 1));
        draft.putArray("formulas");
        var ratesOnly = EntityPayroll.save(db, 1, draft);
        assertEquals(2, ratesOnly.get("revision").longValue());
        assertEquals(new BigDecimal("1000000000.0000"), ratesOnly.at("/rates/0/amount").decimalValue());
        draft.put("revision", 2).putArray("rates");
        var emptied = EntityPayroll.save(db, 1, draft);
        assertEquals(3, emptied.get("revision").longValue());
        assertTrue(emptied.get("rates").isEmpty());
        assertTrue(emptied.get("formulas").isEmpty());
        assertEquals(404, assertThrows(Problem.class, () -> EntityPayroll.load(db, 99)).status);
        assertEquals(404, assertThrows(Problem.class, () -> EntityPayroll.save(db, 99, draft)).status);
    }
}
