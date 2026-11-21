package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

class MainlineSupervisorTest {
    @Test void supervisorHandoffInSqlite() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) { checkHandoff(db); }
    }

    @Test @EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
    void supervisorHandoffInPostgres() throws Exception {
        try (var sandbox = new PostgresTestDatabase(); var db = sandbox.open()) { checkHandoff(db); }
    }

    private static ObjectNode person(long id) {
        return App.JSON.createObjectNode().put("id",id).put("is_admin",id == 1);
    }

    private static ObjectNode schedule(long project, String date, int... crew) {
        var schedule = App.JSON.createObjectNode().put("project_id",project).put("date",date);
        var members = schedule.putArray("member_ids");
        for (int id : crew) members.add(id);
        return schedule;
    }

    private static ObjectNode member(ObjectNode daily, long person) {
        for (var m : daily.path("members")) if (m.get("id").asLong() == person) return (ObjectNode)m;
        return null;
    }

    private static long id(ObjectNode item) { return item.get("id").asLong(); }

    private static void checkHandoff(Connection db) throws Exception {
        Db.initialize(db);
        change(db, """
            INSERT INTO people(id,username,password_hash,name,is_admin) VALUES
              (1,'admin','x','Office',1),(2,'lead','x','Sam Lee',0),(3,'second','x','Sam Lee',0),(4,'crew','x','Crew Member',0)
            """);
        var admin = person(1);
        var lead = person(2);
        var incoming = person(3);
        String today = Db.today();
        String past = LocalDate.parse(today).minusDays(1).toString();
        String future = LocalDate.parse(today).plusDays(1).toString();

        var projectForm = App.JSON.createObjectNode().put("name","River north").put("supervisor","Sam Lee").put("driver_cost","50");
        long project = id(Mainline.saveProject(db,admin,null,projectForm));
        assertTrue(Mainline.supervisedProjects(db,lead).isEmpty());
        long another = id(Mainline.saveProject(db,admin,null,projectForm));
        long yesterday = id(Mainline.createDaily(db,admin,schedule(project,past,4)));
        long current = id(Mainline.createDaily(db,admin,schedule(project,today,3,4)));
        long planned = id(Mainline.createDaily(db,admin,schedule(project,future,4)));

        projectForm.put("revision",0).put("supervisor","Stale label").put("supervisor_person_id",2);
        var linked = Mainline.saveProject(db,admin,project,projectForm);
        assertEquals("Sam Lee",linked.path("supervisor").asText());
        assertEquals(1,Mainline.supervisedProjects(db,lead).size());
        assertThrows(Problem.class,() -> Mainline.dispatchProject(db,incoming,project));
        var dispatch = Mainline.dispatchProject(db,lead,project);
        assertFalse(dispatch.has("driver_cost"));
        assertFalse(dispatch.path("latest_daily").has("expenses"));
        assertNull(member(Mainline.daily(db,admin,yesterday),2));
        assertTrue(member(Mainline.daily(db,lead,current),2).get("supervisor_only").asBoolean());
        assertTrue(Mainline.canManageCrew(db,lead,planned));
        assertFalse(Mainline.canManageCrew(db,lead,yesterday));
        assertThrows(Problem.class,() -> Mainline.daily(db,lead,planned));
        assertThrows(Problem.class,() -> Mainline.createDaily(db,lead,schedule(another,today,4)));
        assertThrows(Problem.class,() -> Mainline.createDaily(db,lead,schedule(project,past,4)));
        assertThrows(Problem.class,() -> Mainline.createDaily(db,lead,schedule(project,future,4).put("total_footage",42)));
        assertThrows(Problem.class,() -> Mainline.saveProject(db,lead,project,projectForm));

        // A self-submission by the supervisor can never turn a temporary grant into permanent crew access.
        var self = Mainline.createDaily(db,lead,schedule(project,future,2));
        assertEquals(1,self.path("members").size());
        assertFalse(self.has("total_footage"));
        Mainline.addMembers(db,lead,current,App.JSON.createArrayNode().add(2));
        var day = App.JSON.createObjectNode().put("revision",0).put("total_footage","126");
        day.putArray("driver_ids").add(2);
        Mainline.saveDaily(db,lead,current,day);

        // Same display name, distinct accounts. An existing explicit member remains explicit after promotion.
        projectForm.put("revision",1).put("supervisor_person_id",3);
        Mainline.saveProject(db,admin,project,projectForm);
        var report = Mainline.daily(db,incoming,current);
        assertFalse(member(report,3).get("supervisor_only").asBoolean());
        assertTrue(member(report,2).get("is_driver").asBoolean());
        assertTrue(member(report,2).get("former_supervisor").asBoolean());
        assertTrue(Mainline.dailies(db,lead,today,null).isEmpty());
        assertThrows(Problem.class,() -> Mainline.requireDaily(db,lead,current));
        assertThrows(Problem.class,() -> Mainline.daily(db,lead,current));
        assertFalse(Mainline.canSeeProject(db,lead,project));
        assertFalse(Mainline.canManageCrew(db,lead,current));
        assertNull(member(Mainline.crewDaily(db,incoming,current),2));
        assertNull(member(Mainline.crewDaily(db,incoming,id(self)),2));

        Mainline.addMembers(db,incoming,current,App.JSON.createArrayNode().add(2));
        assertEquals(404,assertThrows(Problem.class,() -> Mainline.daily(db,lead,current)).status);
        assertEquals(current,Mainline.workerAssignments(db,lead).at("/0/id").asLong());
        assertEquals(2,Mainline.daily(db,incoming,current).path("driver_ids").get(0).asLong());
        assertFalse(Mainline.canManageCrew(db,lead,current));
        change(db,"UPDATE people SET name='Renamed contractor' WHERE id=3");
        assertEquals(1,Mainline.supervisedProjects(db,incoming).size());
        projectForm.put("revision",2).putNull("supervisor_person_id").put("supervisor","");
        Mainline.saveProject(db,admin,project,projectForm);
        assertTrue(Mainline.supervisedProjects(db,incoming).isEmpty());
        assertNotNull(member(Mainline.daily(db,admin,current),3));
        assertEquals(404,assertThrows(Problem.class,() -> Mainline.daily(db,incoming,current)).status);
        assertFalse(Mainline.workerAssignments(db,incoming).at("/0/can_report").asBoolean());
        assertNull(member(Mainline.daily(db,admin,planned),3));
    }
}
