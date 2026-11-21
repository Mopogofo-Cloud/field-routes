package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static fieldops.StoreSql.*;

final class EntityRateCardEditsTest {
    private Connection db;

    @BeforeEach void setup() throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        change(db,"PRAGMA foreign_keys=ON");
        Db.initialize(db);
        change(db,"INSERT INTO entities(id,name) VALUES(71,'Local only'),(72,'Imported')");
    }

    @AfterEach void close() throws Exception { db.close(); }

    private static ObjectNode obj(String source) throws Exception {
        return (ObjectNode)EntityRateCards.JSON.readTree(source);
    }

    private ObjectNode save(long id, ObjectNode before, String patch) throws Exception {
        return EntityRateCardEdits.save(db,id,EntityRateCards.digest(before),obj(patch));
    }

    @Test void localOnlyCreatesAllKindsOfRatesAndPreservesBlankZeroAndPrecision() throws Exception {
        assertNull(save(71,null,"{\"items\":[],\"roleRates\":[{\"label\":\"\",\"dropRate\":\"\"}]}"));
        ObjectNode current = save(71,null,"""
            {"billingModel":"GOOGLE_BILLING","boreRatePerFoot":"0.00000","googleLaborDropRate":"2.50000",
             "footageTiers":[{"fromFt":"0","toFt":"","rate":"1.234567890123456789012345678900"}],
             "items":[{"label":"Small vault","rate":"2.340000"}],
             "googleLaborItemRates":[{"label":"Vault","rate":"0"}],
             "roleRates":[{"label":"Lead","dropRate":"10","boreRate":""}]}
            """);
        assertEquals("Local only",current.path("companyName").textValue());
        assertFalse(current.has("id"));
        assertNull(one(db,"SELECT * FROM wgidig_entities WHERE entity_id=71"));
        assertTrue(one(db,"SELECT source_document FROM entity_rate_cards WHERE entity_id=71").get("source_document").isNull());
        var stored = EntityRateCards.read(db,71);
        assertEquals("1.234567890123456789012345678900",stored.at("/footageTiers/0/rate").asText());
        assertEquals("0.00000",stored.get("boreRatePerFoot").asText());
        var keys = EntityRateFields.amounts(stored);
        assertEquals(6,keys.size());
        assertEquals("2.340000",keys.get("wg_local_item_small_vault").toPlainString());
        assertFalse(keys.containsKey("wg_local_role_lead_bore"));
        assertEquals("Vault",EntityRateCardEdits.label(stored,"googleLaborItemRates",stored.at("/googleLaborItemRates/0")));
        assertEquals(EntityRateCards.digest(stored),EntityRateCards.digest(save(71,stored,"""
            {"boreRatePerFoot":"0.00000","items":[{"_index":"0","label":"Small vault","rate":"2.340000"}]}
            """)));
        ObjectNode cleared = save(71,stored,"""
            {"boreRatePerFoot":"","items":[{"_index":0,"rate":"2.3400000"}],"googleLaborItemRates":[]}
            """);
        assertEquals("2.3400000",cleared.at("/items/0/rate").asText());
        assertTrue(cleared.get("boreRatePerFoot").isNull());
        assertTrue(cleared.get("googleLaborItemRates").isEmpty());
        assertEquals(409,assertThrows(Problem.class,()->save(71,stored,"{\"items\":[]}")).status);
        assertEquals(cleared,EntityRateCards.read(db,71));
    }

    @Test void editedImportedRowsRetainOriginalMetadataAndReferencesAfterDeletionAndRename() throws Exception {
        var original = obj("""
            {"id":101,"companyName":"Imported","billingModel":"BUNDLE","futureSourceMetadata":{"keep":true},
             "itemNames":{"4":"Vault"},"roleNames":{"3":"Lead"},"items":null,
             "roleRates":[{"employeeRoleId":3,"employeeRoleName":"Lead","id":14,"dropRate":1,"boreRate":2},
                {"employeeRoleId":3,"employeeRoleName":"Lead","id":15,"extra":"keep","dropRate":3,"boreRate":4}],
             "footageTiers":[{"fromFt":0,"toFt":100,"rate":4.500}]}
            """);
        String json = EntityRateCards.canonical(original);
        change(db,"INSERT INTO wgidig_entities(entity_id,wgidig_id) VALUES(72,101)");
        change(db,"INSERT INTO entity_rate_cards(entity_id,document,source_document) VALUES(72,?,?)",json,json);
        change(db,"INSERT INTO entity_payroll_config VALUES(72,1)");
        change(db,"""
            INSERT INTO entity_payroll_formulas(entity_id,position,label,basis,expression)
            VALUES(72,0,'Pay','production','wg_role_3_2_drop + wg_tier_0_100')
            """);
        var changed = save(72,original,"""
            {"footageTiers":[{"_index":0,"toFt":"250","rate":"4.75"}],
             "roleRates":[{"_index":1,"label":"New role label","dropRate":"9","boreRate":"0"}]}
            """);
        assertTrue(changed.at("/futureSourceMetadata/keep").asBoolean());
        assertEquals(15,changed.at("/roleRates/0/id").intValue());
        assertEquals("keep",changed.at("/roleRates/0/extra").asText());
        assertEquals("Lead",changed.at("/roleRates/0/employeeRoleName").asText());
        assertEquals("New role label",EntityRateCardEdits.label(changed,"roleRates",changed.at("/roleRates/0")));
        assertEquals("9",EntityRateFields.amounts(changed).get("wg_role_3_2_drop").toPlainString());
        assertEquals("4.75",EntityRateFields.amounts(changed).get("wg_tier_0_100").toPlainString());
        assertEquals(json,one(db,"SELECT source_document FROM entity_rate_cards WHERE entity_id=72").get("source_document").textValue());
        assertEquals(422,assertThrows(Problem.class,()->save(72,changed,"{\"roleRates\":[]}")).status);
        assertEquals(422,assertThrows(Problem.class,()->save(72,changed,"{\"roleRates\":[{\"_index\":0,\"dropRate\":\"\"}]}")).status);
        assertEquals(changed,EntityRateCards.read(db,72));
    }

    @Test void migrationRetainsUneditedImportedSourceAndAllowsFreshUnlinkedCards() throws Exception {
        change(db,"DROP TABLE entity_rate_cards");
        change(db,"CREATE TABLE entity_rate_cards(entity_id INTEGER PRIMARY KEY REFERENCES wgidig_entities(entity_id),document TEXT NOT NULL)");
        change(db,"INSERT INTO wgidig_entities(entity_id,wgidig_id) VALUES(72,101)");
        String old = "{\"id\":101,\"companyName\":\"Imported\",\"driveRate\":3.5000}";
        change(db,"INSERT INTO entity_rate_cards VALUES(72,?)",old);
        Db.initialize(db);
        Db.initialize(db);
        var row = one(db,"SELECT document,source_document FROM entity_rate_cards WHERE entity_id=72");
        assertEquals(old,row.get("document").textValue());
        assertEquals(old,row.get("source_document").textValue());
        assertNull(one(db,"PRAGMA foreign_key_check"));
        assertEquals("1.5000",save(71,null,"{\"driveRate\":\"1.5000\"}").get("driveRate").asText());
    }

    @Test void importingAgainRequiresComparisonBeforeReplacingLocalEdits() throws Exception {
        var captured = obj("""
            {"listed":[{"companyName":"Imported","id":101}],"entities":[{"companyName":"Imported","id":101,
             "driveRate":1.25000}],"itemNames":{},"roleNames":{}}
            """);
        EntityRateCards.importCatalog(db,captured);
        var imported = EntityRateCards.read(db,72);
        var changed = save(72,imported,"{\"driveRate\":\"9.7500\"}");
        assertThrows(IllegalArgumentException.class,()->EntityRateCards.importCatalog(db,captured));
        assertEquals(changed,EntityRateCards.read(db,72));
        captured.putObject("previousDigests").put("101",EntityRateCards.digest(changed));
        assertEquals(1,EntityRateCards.importCatalog(db,captured).replaced());
        assertEquals(EntityRateCards.canonical(imported),
            one(db,"SELECT source_document FROM entity_rate_cards WHERE entity_id=72").get("source_document").textValue());
        assertEquals(imported,EntityRateCards.read(db,72));
    }

    @Test void invalidRowsManualKeyConflictAndStaleFirstWriteRollBackEntireCard() throws Exception {
        change(db,"INSERT INTO entity_payroll_config VALUES(71,1)");
        change(db,"INSERT INTO entity_payroll_rates VALUES(71,0,'wg_drive_rate','Manual drive','per trip',1)");
        assertEquals(422,assertThrows(Problem.class,()->save(71,null,"{\"driveRate\":\"17.00\"}")).status);
        assertEquals(422,assertThrows(Problem.class,()->save(71,null,"{\"items\":[{\"rate\":1}]}")).status);
        assertEquals(422,assertThrows(Problem.class,()->save(71,null,"{\"footageTiers\":[{\"fromFt\":5,\"toFt\":2,\"rate\":3}]}")).status);
        assertEquals(422,assertThrows(Problem.class,()->save(71,null,"{\"roleRates\":[{\"_index\":1}]}")).status);
        assertNull(EntityRateCards.read(db,71));
        var card = save(71,null,"{\"items\":[{\"label\":\"First\",\"rate\":\"1\"}]}");
        assertEquals(422,assertThrows(Problem.class,()->save(71,card,"{\"items\":[{\"_index\":0},{\"_index\":0}]}")).status);
        assertEquals(422,assertThrows(Problem.class,()->save(71,card,"{\"items\":[{\"_index\":0,\"dropItemId\":33}]}")).status);
        assertEquals(409,assertThrows(Problem.class,()->save(71,null,"{\"items\":[]}")).status);
        assertEquals(card,EntityRateCards.read(db,71));
    }
}
