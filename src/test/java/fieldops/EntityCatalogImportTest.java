package fieldops;

import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityCatalogImportTest {
    @Test void bothScreensAreImportedOnceWithoutChangingLocalIdentityOrHistory() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Db.initialize(db);
            StoreSql.change(db, "INSERT INTO entities(id,name) VALUES(21,'Local company'),(88,'COMCAST 2026 - INHOUSE')");
            StoreSql.change(db, "INSERT INTO teams VALUES(1,'Original',21)");
            StoreSql.change(db, """
                INSERT INTO routes(date,team_id,team_name,entity_id,entity_name,address_hash)
                VALUES('2020-01-10',1,'Original',21,'Historic local name','fixture')
                """);
            var first = EntityCatalogImport.run(db);
            assertEquals(22, first.created());
            assertEquals(1, first.matched());
            assertTrue(first.conflictingIds().isEmpty());
            assertEquals(23, EntityCatalogImport.run(db).alreadyLinked());
            assertEquals(24, Store.entities(db).size());
            assertEquals(88, StoreSql.one(db, "SELECT entity_id FROM wgidig_entities WHERE wgidig_id=21").get("entity_id").intValue());
            assertEquals("Historic local name", StoreSql.one(db, "SELECT entity_name FROM routes WHERE entity_id=21").get("entity_name").asText());
            assertEquals(21, StoreSql.one(db, "SELECT entity_id FROM teams WHERE id=1").get("entity_id").intValue());
            var source = StoreSql.rows(db, "SELECT w.wgidig_id,e.name FROM wgidig_entities w JOIN entities e ON e.id=w.entity_id ORDER BY w.wgidig_id");
            var names = new StringBuilder();
            for (var entity : source) names.append(entity.get("wgidig_id").intValue()).append(' ').append(entity.get("name").textValue()).append('\n');
            assertEquals("""
                1 Google Nashville - INHOUSE CREWS 2026
                2 COMCAST - SUBCONTRACTOR 2026
                3 METRONET - INHOUSE 2026
                4 Mtronet EJEMPLO DO NOT USE
                5 FRONTIER WV - INHOUSE 2026
                6 FRONTIER OH - INHOUSE 2026
                7 FRONTIER IL- INHOUSE 2026
                9 FRONTIER WV - SUBCONTRACTORS 2026
                10 FRONTIER OH - SUBCONTRACTORS 2026
                11 FRONTIER IL - SUBCONTRACTORS 2026
                12 GOOGLE - HUNTSVILLE SUBCONTRACTOR 2026
                14 METRONET-SUBCONTRACTOR 2026
                15 SPECTRUM - MO INHOUSE 2026
                16 TRIX - INHOUSE 2026
                17 Spectrum- MO subcontractor 2026
                18 Fidium ITG - Subcontractor 2026
                19 GOOGLE HUNTSVILLE INHOUSE 2026
                20 FRONTIER IA - SUBCONTRACTOR 2026
                21 COMCAST 2026 - INHOUSE
                22 GOOGLE NASHVILLE SUBCONTRACTOR 2026
                23 METRONET _LUCAS
                24 Metronet In House Antonio
                25 CONSOLIDATE
                """, names.toString());
            for (var entity : Store.entities(db)) {
                if (entity.get("id").intValue() == 21) assertTrue(entity.get("wgidig_id").isNull());
                else assertTrue(entity.hasNonNull("wgidig_id"));
            }
        }
    }

    @Test void ambiguousNamesOrSourceIdsAreReportedAndRetainExistingAssociations() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Db.initialize(db);
            StoreSql.change(db, "INSERT INTO entities(id,name) VALUES(1,'consolidate'),(2,'Another company'),(3,'METRONET _LUCAS')");
            StoreSql.change(db, "INSERT INTO wgidig_entities VALUES(2,21),(3,100)");
            var result = EntityCatalogImport.run(db);
            assertEquals(List.of(21,25,23), result.conflictingIds());
            assertEquals(20, result.created());
            assertEquals(2, StoreSql.one(db, "SELECT entity_id FROM wgidig_entities WHERE wgidig_id=21").get("entity_id").intValue());
            assertEquals("consolidate", StoreSql.one(db, "SELECT name FROM entities WHERE id=1").get("name").textValue());
            assertEquals(100, StoreSql.one(db, "SELECT wgidig_id FROM wgidig_entities WHERE entity_id=3").get("wgidig_id").intValue());
            assertEquals(20, EntityCatalogImport.run(db).alreadyLinked());
        }
    }
}
