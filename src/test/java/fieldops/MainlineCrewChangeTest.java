package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

class MainlineCrewChangeTest {
    @Test void crewChangesInSqlite() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) { checkChanges(db); }
    }

    @Test @EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
    void crewChangesInPostgres() throws Exception {
        try (var sandbox = new PostgresTestDatabase(); var db = sandbox.open()) { checkChanges(db); }
    }

    private static JsonNode member(JsonNode report, long id) {
        for (var member : report.path("members")) if (member.path("id").asLong() == id) return member;
        return null;
    }

    private static ObjectNode user(int id) {
        return App.JSON.createObjectNode().put("id",id).put("is_admin",id==1);
    }

    private static void checkChanges(Connection db) throws Exception {
        Db.initialize(db);
        change(db, """
            INSERT INTO people(id,name,username,password_hash,is_admin) VALUES
              (1,'Admin','admin','x',1),(2,'Supervisor','lead','x',0),(3,'Original driver','driver','x',0),
              (4,'Replacement','replacement','x',0),(5,'Other supervisor','other','x',0)
            """);
        var admin = user(1);
        var lead = user(2);
        var project = App.JSON.createObjectNode().put("name","Fiber construction").put("supervisor_person_id",2);
        long pid = Mainline.saveProject(db,admin,null,project).get("id").asLong();
        var plan = App.JSON.createObjectNode().put("project_id",pid).put("date",Db.today());
        plan.putArray("member_ids").add(3);
        long daily = Mainline.createDaily(db,lead,plan).get("id").asLong();
        var edit = App.JSON.createObjectNode().put("revision",0).put("comments","Original note")
            .put("total_footage","126.50").put("priority","HIGH");
        edit.putArray("expenses").addObject().put("type","Gas").put("cost","24.75");
        edit.putArray("driver_ids").add(3);
        var initial = Mainline.saveDaily(db,lead,daily,edit);
        change(db,"INSERT INTO mainline_media(daily_id,category,mime,byte_count,content) VALUES(?,'map','image/png',1,?)",
            daily,new byte[]{1});
        assertEquals(403, assertThrows(Problem.class,()->Mainline.changeMember(db,user(5),daily,3,4L,0)).status);
        assertEquals(422, assertThrows(Problem.class,()->Mainline.changeMember(db,lead,daily,2,4L,0)).status);
        for (long invalid : new long[]{1,2,3,999}) {
            assertEquals(422, assertThrows(Problem.class,()->Mainline.changeMember(db,lead,daily,3,invalid,0)).status);
            assertEquals(0L,Mainline.crewDaily(db,lead,daily).get("crew_revision").asLong());
        }

        var crew = Mainline.changeMember(db,lead,daily,3,4L,0);
        assertNull(member(crew,3));
        assertFalse(member(crew,4).get("is_driver").asBoolean());
        assertEquals(1L,crew.get("crew_revision").asLong());
        assertNull(member(Mainline.project(db,pid).get("latest_daily"),3));
        var after = Mainline.daily(db,lead,daily);
        for (String key : new String[]{"revision","total_footage","comments","expenses","priority","driver_ids"})
            assertEquals(initial.get(key),after.get(key),key);
        assertTrue(member(after,3).get("removed").asBoolean());
        assertEquals(1,one(db,"SELECT count(*) AS n FROM mainline_media WHERE daily_id=?",daily).get("n").asInt());
        // Old crew forms and a retried swap cannot silently reassign someone.
        assertEquals(409,assertThrows(Problem.class,()->Mainline.changeMember(db,lead,daily,3,4L,0)).status);
        assertEquals(409,assertThrows(Problem.class,()->Mainline.addMembers(db,lead,daily,App.JSON.createArrayNode().add(3))).status);
        assertEquals(409,assertThrows(Problem.class,()->Mainline.addMembers(db,lead,daily,App.JSON.createArrayNode().add(3),0L)).status);

        edit.put("revision",1).put("comments","Second note entered");
        edit.putArray("driver_ids");
        after = Mainline.saveDaily(db,lead,daily,edit);
        assertTrue(member(after,3).get("is_driver").asBoolean());
        Mainline.changeMember(db,lead,daily,4,null,1);
        assertNull(member(Mainline.daily(db,lead,daily),4));
        // A checked driver in an already-open draft can still be recorded following a concurrent removal.
        edit.put("revision",2).putArray("driver_ids").add(4);
        var credited = Mainline.saveDaily(db,lead,daily,edit);
        assertTrue(member(credited,4).get("is_driver").asBoolean());
        assertTrue(member(credited,3).get("is_driver").asBoolean());

        // Both drivers were removed when this draft opened, so neither credit had an editable checkbox.
        var openDraft = App.JSON.createObjectNode().put("revision",credited.path("revision").asLong());
        openDraft.putArray("driver_ids");
        openDraft.putArray("initial_driver_ids");
        Mainline.addMembers(db,admin,daily,App.JSON.createArrayNode().add(3),2L);
        assertFalse(member(Mainline.crewDaily(db,admin,daily),3).get("removed").asBoolean());
        var restored = Mainline.saveDaily(db,lead,daily,openDraft);
        assertTrue(member(restored,3).get("is_driver").asBoolean());
        // A fresh report shows the restored driver checked and can deliberately clear that check.
        openDraft.put("revision",restored.path("revision").asLong());
        openDraft.putArray("initial_driver_ids").add(3);
        var cleared = Mainline.saveDaily(db,lead,daily,openDraft);
        assertFalse(member(cleared,3).get("is_driver").asBoolean());
        assertTrue(member(cleared,4).get("is_driver").asBoolean());
        // Old replace form still cannot rotate this re-added member (ABA).
        assertEquals(409,assertThrows(Problem.class,()->Mainline.changeMember(db,lead,daily,3,4L,0)).status);
        // A removed contractor promoted to supervisor is restored as a protected automatic member.
        project.put("revision",0).put("supervisor_person_id",4);
        Mainline.saveProject(db,admin,pid,project);
        var newCrew = Mainline.crewDaily(db,user(4),daily);
        assertTrue(member(newCrew,4).get("supervisor_only").asBoolean());
        assertFalse(member(newCrew,4).get("removed").asBoolean());
        assertEquals(422,assertThrows(Problem.class,()->Mainline.changeMember(db,user(4),daily,4,null,
            newCrew.get("crew_revision").asLong())).status);
        change(db,"UPDATE mainline_dailies SET date=? WHERE id=?",LocalDate.parse(Db.today()).minusDays(1).toString(),daily);
        assertEquals(403,assertThrows(Problem.class,()->Mainline.changeMember(db,user(4),daily,3,null,
            newCrew.get("crew_revision").asLong())).status);
        assertNull(member(Mainline.changeMember(db,admin,daily,3,null,newCrew.get("crew_revision").asLong()),3));
    }
}
