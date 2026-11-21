package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

class MainlineTest {
    private static ObjectNode person(long id,boolean admin) {
        return App.JSON.createObjectNode().put("id",id).put("is_admin",admin);
    }

    @Test void projectsAndDailiesInSqlite() throws Exception {
        try (var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            change(db,"PRAGMA foreign_keys=ON");
            check(db);
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
    void projectsAndDailiesInPostgres() throws Exception {
        try (var sandbox=new PostgresTestDatabase(); var db=sandbox.open()) { check(db); }
    }

    private static void check(Connection db) throws Exception {
        Db.initialize(db);
        var admin=person(1,true);
        var worker=person(2,false);
        var other=person(3,false);
        change(db,"""
            INSERT INTO people(id,username,password_hash,name,is_admin) VALUES
              (1,'dispatch','x','Dispatch',1),(2,'worker','x','Crew A',0),(3,'other','x','Crew B',0)
            """);
        change(db,"INSERT INTO vehicles(id,name,brand,plate) VALUES(1,'Truck 1','Ford','123')");
        var input=App.JSON.createObjectNode().put("name","Fiber expansion").put("price_per_foot","2.1215")
            .put("estimated_footage","333.33").put("lunch_provided","yes");
        long project=Mainline.saveProject(db,admin,null,input).path("id").asLong();
        var projectView=Mainline.project(db,project);
        assertTrue(projectView.path("lunch_provided").asBoolean());
        assertEquals(0,new BigDecimal("2.1215").compareTo(projectView.path("price_per_foot").decimalValue()));
        assertFalse(Mainline.canSeeProject(db,worker,project));
        assertThrows(Problem.class,()->Mainline.saveProject(db,worker,null,input));
        var create=App.JSON.createObjectNode().put("date",Db.today()).put("project_id",project);
        create.putArray("member_ids").add(2);
        create.putArray("vehicle_ids").add(1);
        create.putArray("driver_ids").add(2);
        var daily=Mainline.createDaily(db,admin,create);
        long id=daily.path("id").asLong();
        assertTrue(Mainline.canSeeProject(db,worker,project));
        assertFalse(Mainline.canSeeProject(db,other,project));
        assertEquals(404,assertThrows(Problem.class,()->Mainline.daily(db,other,id)).status);
        assertEquals(404,assertThrows(Problem.class,()->Mainline.requireDaily(db,other,id)).status);
        assertEquals(1,Mainline.dailies(db,worker,null,null).size());
        assertEquals(0,Mainline.dailies(db,other,null,null).size());
        assertEquals(0,Vehicles.list(db,Db.today()).get(0).get("available").intValue());
        assertEquals(409,assertThrows(Problem.class,()->Mainline.createDaily(db,admin,create)).status);
        assertEquals(1,Mainline.dailies(db,admin,null,project).size());
        change(db,"UPDATE people SET name='New crew name' WHERE id=2");
        change(db,"UPDATE vehicles SET name='Truck 4' WHERE id=1");
        assertEquals("Crew A",Mainline.daily(db,worker,id).at("/members/0/name").asText());
        assertEquals("Truck 1",Mainline.daily(db,worker,id).at("/vehicles/0/name").asText());
        var report=App.JSON.createObjectNode().put("revision",0).put("total_footage","125.34").put("priority","HIGH").put("comments","Fiber complete");
        report.putArray("expenses").addObject().put("type","Gas").put("cost","42.34");
        assertEquals("Gas",Mainline.saveDaily(db,worker,id,report).at("/expenses/0/type").asText());
        assertEquals(409,assertThrows(Problem.class,()->Mainline.saveDaily(db,worker,id,report)).status);
        assertEquals(0,new BigDecimal("125.34").compareTo(Mainline.project(db,project).path("total_footage").decimalValue()));
        assertTrue(Mainline.project(db,project).at("/latest_daily/members/0/is_driver").asBoolean());
        assertEquals(0,one(db,"SELECT count(*) AS n FROM routes").get("n").asInt());
        String past=LocalDate.parse(Db.today()).minusDays(1).toString();
        change(db,"UPDATE mainline_dailies SET date=? WHERE id=?",past,id);
        assertTrue(Mainline.dailies(db,worker,past,project).isEmpty());
        assertFalse(Mainline.canSeeProject(db,worker,project));
        assertEquals(404,assertThrows(Problem.class,()->Mainline.saveFootage(db,worker,id,1,BigDecimal.ONE)).status);
        assertEquals(1,Mainline.dailies(db,admin,past,project).size());
    }
}
