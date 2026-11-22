package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

class FleetImportTest {
    @Test void sqliteFleet() throws Exception {
        try (var db=DriverManager.getConnection("jdbc:sqlite::memory:")) { verify(db); }
    }

    @Test @EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT",matches=".+")
    void postgresFleet() throws Exception {
        try (var fixture=new PostgresTestDatabase(); var db=fixture.open()) { verify(db); }
    }

    static ObjectNode vehicle(String id, String code) {
        var record=App.JSON.createObjectNode();
        for (String key : FleetImport.KEYS) record.put(key,"");
        record.put("record_id",id).put("company_code",code).put("brand","FORD").put("model","F150")
            .put("plate_number","N/A").put("vin_number","a private VIN").put("notes","<source notes>")
            .put("full_profile","Open profile").put("source_profile","Open source");
        record.putObject("links").put("full_profile","vehicles/profile.html")
            .put("source_profile","https://www.example.com/vehicles/" + id);
        record.putArray("source_files").addObject().put("file_type","photo").put("result","Saved")
            .put("open_file","Open file").put("original_filename","truck photo.jpg").put("bytes","806")
            .put("notes","photo description").put("target","fleet/photos/truck.jpg");
        return record;
    }

    private static void verify(Connection db) throws Exception {
        Db.initialize(db);
        var source=App.JSON.createArrayNode().add(vehicle("1","Truck 3")).add(vehicle("2","TRUCK 3")).add(vehicle("3","N/A"));
        assertEquals(new FleetImport.Result(3,0,3),FleetImport.run(db,source,false));
        assertEquals(0,one(db,"SELECT count(*) AS n FROM vehicles").get("n").asInt());
        assertEquals(3,FleetImport.run(db,source,true).newVehicles());
        assertEquals(3,FleetImport.run(db,source,true).matched());
        assertEquals(3,one(db,"SELECT count(*) AS n FROM vehicle_source_profiles").get("n").asInt());
        var names=rows(db,"SELECT name FROM vehicles ORDER BY id");
        assertEquals("Truck 3",names.get(0).get("name").asText());
        assertEquals("TRUCK 3",names.get(1).get("name").asText());
        assertEquals("FORD F150",names.get(2).get("name").asText());
        long id=one(db,"SELECT vehicle_id FROM vehicle_source_profiles WHERE source_record_id='1'").path("vehicle_id").asLong();
        assertFalse(Vehicles.list(db).toString().contains("a private VIN"));
        assertThrows(Problem.class,()->FleetImport.adminProfile(db,App.JSON.createObjectNode().put("is_admin",false),id));
        String page=FleetImport.adminProfile(db,App.JSON.createObjectNode().put("is_admin",true),id);
        assertTrue(page.contains("&lt;source notes&gt;") && page.contains("a private VIN"));
        assertTrue(page.contains("vehicles/profile.html") && !page.contains("href='vehicles/profile.html'"));
        assertTrue(page.contains("href='https://www.example.com/vehicles/1'") && page.contains("truck photo.jpg"));
        assertTrue(page.contains("fleet/photos/truck.jpg") && !page.contains("href='fleet/photos/truck.jpg'"));
        change(db,"UPDATE vehicles SET name='Updated dispatch alias' WHERE id=?",id);
        assertEquals(3,FleetImport.run(db,source,true).matched());
        assertEquals("Updated dispatch alias",one(db,"SELECT name FROM vehicles WHERE id=?",id).get("name").asText());

        var altered=App.JSON.createArrayNode().add(vehicle("4","Truck 4")).add(vehicle("1","Truck 3").put("notes","changed"));
        assertThrows(IllegalStateException.class,()->FleetImport.run(db,altered,true));
        assertEquals(3,one(db,"SELECT count(*) AS n FROM vehicles").get("n").asInt());
        var manual=App.JSON.createObjectNode().put("name","Truck 3").put("brand","").put("plate","");
        assertThrows(Problem.class,()->Vehicles.save(db,manual,null));
        Vehicles.save(db,manual.put("name","New manual unit"),null);
        var local=App.JSON.createArrayNode().add(vehicle("5","New manual unit"));
        assertThrows(IllegalStateException.class,()->FleetImport.run(db,local,true));
        var dispatchAlias=App.JSON.createArrayNode().add(vehicle("7","Updated dispatch alias"));
        assertThrows(IllegalStateException.class,()->FleetImport.run(db,dispatchAlias,true));
        var later=App.JSON.createArrayNode().add(vehicle("8","Truck 3"));
        assertEquals(1,FleetImport.run(db,later,true).newVehicles());
        var duplicate=App.JSON.createArrayNode().add(vehicle("6","Truck 6")).add(vehicle("6","Truck 7"));
        assertThrows(IllegalArgumentException.class,()->FleetImport.run(db,duplicate,true));
    }

    @Test void legacySqliteNamesAndSnapshotsSurviveTableUpgrade(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("legacy.sqlite3"));var sql=db.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            Db.initialize(db);
            sql.execute("INSERT INTO entities(id,name) VALUES(1,'Entity')");
            sql.execute("INSERT INTO teams(id,name,entity_id) VALUES(1,'Crew',1)");
            sql.execute("INSERT INTO routes(id,date,team_id,team_name,entity_id,entity_name,address_hash) VALUES(1,'2026-01-05',1,'Crew',1,'Entity','abc')");
            sql.execute("INSERT INTO mainline_projects(id,name,state) VALUES(1,'Project','Not Started')");
            sql.execute("INSERT INTO mainline_dailies(id,project_id,date) VALUES(1,1,'2026-01-05')");
            seedLegacyNames(db,1,1);
            assertTrue(VehicleNameUpgrade.hasOldUniqueName(db));
            for(int pass=0;pass<2;pass++) {
                Db.initialize(db);
                verifyNormalizedNames(db,1,1);
                assertFalse(VehicleNameUpgrade.hasOldUniqueName(db));
                assertEquals(1,one(db,"PRAGMA foreign_keys").path("foreign_keys").asInt());
                assertNull(one(db,"PRAGMA foreign_key_check"));
            }
        }
    }

    static void seedLegacyNames(Connection db,long route,long daily) throws Exception {
        change(db,"DROP TABLE vehicles");
        change(db,"CREATE TABLE vehicles(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE COLLATE NOCASE,brand TEXT NOT NULL DEFAULT '',plate TEXT NOT NULL DEFAULT '')");
        String[] legacy={"Truck 3 (Record #1)","Truck 3 (Record #2)","FORD F150 #3","Special #4"};
        for(int i=1;i<=4;i++) {
            var source=vehicle(Integer.toString(i),i<=2 ? "Truck 3" : "N/A");
            String name=legacy[i-1];
            change(db,"INSERT INTO vehicles(id,name) VALUES(?,?)",i,name);
            change(db,"INSERT INTO vehicle_source_profiles(vehicle_id,source_record_id,source_record) VALUES(?,?,?)",i,Integer.toString(i),source.toString());
            change(db,"INSERT INTO route_vehicles(route_id,vehicle_id,name,brand,plate) VALUES(?,?,?,'Brand','Plate')",route,i,
                i==4 ? "FORD F150 #4" : name);
            change(db,"INSERT INTO mainline_daily_vehicles(daily_id,vehicle_id,name,brand,plate) VALUES(?,?,?,'Brand','Plate')",daily,i,
                i==3 ? "Separate historic #3" : name);
        }
        change(db,"INSERT INTO vehicles(id,name) VALUES(5,'Local #5')");
    }

    static void verifyNormalizedNames(Connection db,long route,long daily) throws Exception {
        assertEquals(4,one(db,"SELECT count(*) AS n FROM vehicle_source_profiles").get("n").asInt());
        assertEquals(2,one(db,"SELECT count(*) AS n FROM vehicles WHERE name='Truck 3'").get("n").asInt());
        assertEquals("FORD F150",one(db,"SELECT name FROM vehicles WHERE id=3").get("name").asText());
        assertEquals("Special #4",one(db,"SELECT name FROM vehicles WHERE id=4").get("name").asText());
        assertEquals("Local #5",one(db,"SELECT name FROM vehicles WHERE id=5").get("name").asText());
        assertEquals("FORD F150",one(db,"SELECT name FROM route_vehicles WHERE route_id=? AND vehicle_id=4",route).get("name").asText());
        assertEquals("Separate historic #3",one(db,"SELECT name FROM mainline_daily_vehicles WHERE daily_id=? AND vehicle_id=3",daily).get("name").asText());
        assertEquals(2,one(db,"SELECT count(*) AS n FROM mainline_daily_vehicles WHERE daily_id=? AND name='Truck 3'",daily).get("n").asInt());
    }
}
