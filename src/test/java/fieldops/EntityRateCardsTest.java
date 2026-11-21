package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EntityRateCardsTest {
    private Connection db;

    @BeforeEach void start() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        Db.initialize(db);
    }

    @AfterEach void close() throws Exception { db.close(); }

    private static ObjectNode manifest() throws Exception {
        var manifest = (ObjectNode)EntityRateCards.JSON.readTree("""
            {
              "listed": [{"id":7,"companyName":"Example A"},{"id":9,"companyName":"Example B"}],
              "entities":[
                {"id":7,"companyName":"Example A","billingModel":"GOOGLE_BILLING","location":"Test",
                  "latitude":34.123456780,"longitude":null,
                  "driveRate":0.0000,"boreDrivewayRate":0.1234567890123456789012300,
                  "boreRoadBoreRate":null,"baseFeeWithBore":"",
                  "footageTiers":[{"fromFt":0,"toFt":10,"rate":2.25},{"fromFt":11,"toFt":null,"rate":0}],
                  "items":[{"dropItemId":3,"name":"Alias","rate":"1.250000"},{"type":"Legacy item","rate":0}],
                  "googleLaborItemRates":"[]",
                  "roleRates":[{"id":300,"employeeRoleId":"5","employeeRoleName":"Operator","dropRate":0,"boreRate":null}]},
                {"id":9,"companyName":"Example B","billingModel":"BUNDLE","roleRates":null}
              ],
              "itemNames":{"3":"Example item","7":"Unreferenced catalog item"},
              "roleNames":{"5":"Catalog role","6":"Unreferenced role"}
            }
            """);
        var first = (ObjectNode)manifest.at("/entities/0");
        first.put("footageTiers",first.get("footageTiers").toString());
        return manifest;
    }

    private long local(long source) throws Exception {
        return StoreSql.one(db, "SELECT entity_id FROM wgidig_entities WHERE wgidig_id=?", source).get("entity_id").longValue();
    }

    @Test void linkedEntityKeepsItsLocalDisplayNameAndTheExactSourceName() throws Exception {
        var source = manifest();
        long id = StoreSql.insertId(db,"INSERT INTO entities(name) VALUES('Example A')");
        StoreSql.change(db,"INSERT INTO wgidig_entities(entity_id,wgidig_id) VALUES(?,7)",id);
        for (String rows : java.util.List.of("entities", "listed"))
            ((ObjectNode)source.get(rows).get(0)).put("companyName","Example A ");
        EntityRateCards.importCatalog(db,source);
        assertEquals(id,local(7));
        assertEquals("Example A ",EntityRateCards.read(db,id).get("companyName").textValue());
        assertEquals("Example A",StoreSql.one(db,"SELECT name FROM entities WHERE id=?",id).get("name").textValue());
    }

    @Test void faithfulImportWithIndependentEmptySectionsAndNoPayrollFormulaChanges() throws Exception {
        long localOnly = StoreSql.insertId(db,"INSERT INTO entities(name) VALUES('Local only')");
        assertNull(EntityRateCards.read(db,localOnly));
        var fixture = manifest();
        assertEquals(new EntityRateCards.Result(2,0,0),EntityRateCards.importCatalog(db,fixture));
        var saved = EntityRateCards.read(db,local(7));
        assertEquals("0.0000", saved.get("driveRate").asText());
        assertEquals("0.1234567890123456789012300", saved.get("boreDrivewayRate").asText());
        assertFalse(saved.has("boreRatePerFoot"));
        assertTrue(saved.get("boreRoadBoreRate").isNull());
        assertEquals("",saved.get("baseFeeWithBore").asText());
        assertEquals(2,saved.get("footageTiers").size());
        assertEquals(11,saved.at("/footageTiers/1/fromFt").asInt());
        assertTrue(saved.at("/footageTiers/1/toFt").isNull());
        assertEquals("1.250000",saved.at("/items/0/rate").textValue());
        assertEquals("3",saved.at("/items/0/dropItemId").asText());
        assertEquals("Alias",saved.at("/items/0/name").textValue());
        assertEquals("Legacy item",saved.at("/items/1/type").textValue());
        assertFalse(saved.at("/items/1").has("dropItemId"));
        assertEquals(1,saved.get("itemNames").size());
        assertEquals("Example item",saved.at("/itemNames/3").asText());
        assertEquals("Catalog role",saved.at("/roleNames/5").asText());
        assertEquals("Operator",saved.at("/roleRates/0/employeeRoleName").textValue());
        assertEquals(300,saved.at("/roleRates/0/id").asInt());
        assertEquals("34.123456780",saved.get("latitude").asText());
        assertTrue(saved.get("longitude").isNull());
        assertFalse(EntityRateFields.amounts(saved).containsKey("wg_latitude"));
        assertTrue(saved.at("/roleRates/0/boreRate").isNull());
        assertTrue(saved.get("googleLaborItemRates").isArray() && saved.get("googleLaborItemRates").isEmpty());
        assertTrue(EntityRateCards.read(db,local(9)).get("roleRates").isNull());
        assertNull(EntityRateCards.read(db,localOnly));
        assertTrue(EntityPayroll.load(db,local(7)).get("formulas").isEmpty());
        assertEquals(new EntityRateCards.Result(0,0,2),EntityRateCards.importCatalog(db,fixture));
    }

    @Test void updateRequiresComparedPriorState() throws Exception {
        var fixture = manifest();
        EntityRateCards.importCatalog(db,fixture);
        var before = EntityRateCards.read(db,local(7));
        ((ObjectNode)fixture.at("/entities/0")).put("boreRoadBoreRate","19.2340");
        assertThrows(IllegalArgumentException.class,()-> EntityRateCards.importCatalog(db, fixture));
        assertEquals(before,EntityRateCards.read(db,local(7)));
        fixture.putObject("previousDigests").put("7",EntityRateCards.digest(before));
        assertEquals(new EntityRateCards.Result(0,1,1),EntityRateCards.importCatalog(db,fixture));
        assertEquals("19.2340",EntityRateCards.read(db,local(7)).get("boreRoadBoreRate").textValue());
    }

    @Test void manualRateCollisionAndRemovedFormulaInputsCannotChangeFinancialConfiguration() throws Exception {
        long id = StoreSql.insertId(db,"INSERT INTO entities(name) VALUES('Example A')");
        StoreSql.change(db,"INSERT INTO entity_payroll_config(entity_id,revision) VALUES(?,1)",id);
        StoreSql.change(db,"INSERT INTO entity_payroll_rates VALUES(?,0,'wg_drive_rate','Manual rate','per job',10000)",id);
        var input=manifest();
        assertThrows(IllegalArgumentException.class,()->EntityRateCards.importCatalog(db,input));
        assertNull(EntityRateCards.read(db,id));
        assertEquals(1,StoreSql.one(db,"SELECT COUNT(*) AS n FROM entity_payroll_rates WHERE entity_id=?",id).get("n").asInt());
        StoreSql.change(db,"UPDATE entity_payroll_rates SET rate_key='manual_rate' WHERE entity_id=?",id);
        EntityRateCards.importCatalog(db,input);
        var prior=EntityRateCards.read(db,id);
        StoreSql.change(db,"INSERT INTO entity_payroll_formulas(entity_id,position,label,basis,expression) VALUES(?,0,'Fixture','daily','wg_drive_rate+manual_rate')",id);
        ((ObjectNode)input.at("/entities/0")).putNull("driveRate");
        input.putObject("previousDigests").put("7",EntityRateCards.digest(prior));
        assertThrows(Problem.class,()->EntityRateCards.importCatalog(db,input));
        assertEquals(prior,EntityRateCards.read(db,id));
    }

    @Test void incompleteOrUnrecognizedDataRollsBackWholeImport() throws Exception {
        var fixture = manifest();
        fixture.withArray("entities").remove(0);
        assertThrows(IllegalArgumentException.class,()-> EntityRateCards.importCatalog(db,fixture));
        var extra=manifest();
        ((ObjectNode)extra.at("/entities/1")).put("futureContractRate",13);
        assertThrows(IllegalArgumentException.class,()-> EntityRateCards.importCatalog(db,extra));
        var badReference=manifest();
        ((ObjectNode)badReference.get("roleNames")).remove("5");
        assertThrows(IllegalArgumentException.class,()-> EntityRateCards.importCatalog(db,badReference));
        assertEquals(0, StoreSql.one(db,"SELECT COUNT(*) AS n FROM entity_rate_cards").get("n").asInt());
        assertEquals(0, StoreSql.one(db,"SELECT COUNT(*) AS n FROM wgidig_entities WHERE wgidig_id IN (7,9)").get("n").asInt());
    }
}
