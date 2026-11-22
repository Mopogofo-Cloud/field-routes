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

    private static ObjectNode vehicle(String id, String code) {
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
        assertEquals("Truck 3 (Record #1)",names.get(0).get("name").asText());
        assertEquals("TRUCK 3 (Record #2)",names.get(1).get("name").asText());
        assertEquals("FORD F150 #3",names.get(2).get("name").asText());
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
        var local=App.JSON.createArrayNode().add(vehicle("5","Updated dispatch alias"));
        assertThrows(IllegalStateException.class,()->FleetImport.run(db,local,true));
        var duplicate=App.JSON.createArrayNode().add(vehicle("6","Truck 6")).add(vehicle("6","Truck 7"));
        assertThrows(IllegalArgumentException.class,()->FleetImport.run(db,duplicate,true));
    }
}
