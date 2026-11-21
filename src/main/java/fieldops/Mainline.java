package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static fieldops.StoreSql.*;

final class Mainline {
    private Mainline() {}
    static final List<String> STATES = List.of("Not Started", "In Progress", "Completed");
    static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");
    static final List<String> EXPENSE_TYPES = List.of("Gas", "Supplies", "Maintenance", "Hotel", "Other");
    static final List<String> CREW_ROLES = List.of("Machine Operator", "Locator", "Trencher", "Missile Man");

    private static final String PROJECTS = """
        SELECT p.*, (SELECT coalesce(sum(d.footage_hundredths),0) FROM mainline_dailies d WHERE d.project_id=p.id) AS total_hundredths
        FROM mainline_projects p
        """;

    static ArrayNode projects(Connection db) throws SQLException {
        var projects = rows(db, PROJECTS + " ORDER BY lower(p.name),p.id");
        for (var row : projects) projectFields((ObjectNode)row);
        return projects;
    }

    private static void projectFields(ObjectNode project) {
        scaled(project, "estimated_hundredths", "estimated_footage", 2);
        scaled(project, "total_hundredths", "total_footage", 2);
        scaled(project, "driver_cents", "driver_cost", 2);
        scaled(project, "price_ten_thousandths", "price_per_foot", 4);
        project.put("lunch_provided", project.path("lunch_provided").asInt() == 1);
    }

    private static void scaled(ObjectNode record, String storageKey, String displayKey, int scale) {
        var number = record.remove(storageKey);
        if (number.isNull()) record.putNull(displayKey);
        else {
            var value = number.decimalValue().movePointLeft(scale).stripTrailingZeros();
            record.put(displayKey, value.scale() < 0 ? value.setScale(0) : value);
        }
    }

    static ObjectNode project(Connection db, long id) throws SQLException {
        return transaction(db, false, () -> readProject(db,id));
    }

    static ArrayNode supervisedProjects(Connection db, ObjectNode user) throws SQLException {
        return rows(db, """
            SELECT id,name,location,state FROM mainline_projects
            WHERE supervisor_person_id=? ORDER BY lower(name),id
            """, user.path("id").asLong());
    }

    private static ObjectNode dispatchDetails(Connection db, ObjectNode user, long projectId) throws SQLException {
        String scope = " WHERE id=?";
        var args = new ArrayList<Object>();
        args.add(projectId);
        if (!admin(user)) { scope += " AND supervisor_person_id=?"; args.add(user.path("id").asLong()); }
        var project = one(db, "SELECT id,name,location,state,supervisor,supervisor_person_id FROM mainline_projects" + scope, args.toArray());
        if (project == null) throw new Problem(404, "Mainline project not found.");
        return project;
    }

    static ObjectNode dispatchProject(Connection db, ObjectNode user, long projectId) throws SQLException {
        return transaction(db, false, () -> {
            if (admin(user)) return readProject(db, projectId);
            var project = dispatchDetails(db, user, projectId);
            var latest = loadDailies(db, " WHERE d.id=(SELECT id FROM mainline_dailies WHERE project_id=? ORDER BY date DESC,id DESC LIMIT 1)",
                List.of(projectId), true);
            project.set("latest_daily", latest.isEmpty() ? null : latest.get(0));
            return project;
        });
    }

    static ArrayNode scheduledDailies(Connection db, ObjectNode user, long projectId) throws SQLException {
        return transaction(db, false, () -> {
            dispatchDetails(db, user, projectId);
            return loadDailies(db, " WHERE d.project_id=? AND d.date>=?", List.of(projectId, Db.today()), true);
        });
    }

    static ObjectNode readProject(Connection db, long id) throws SQLException {
        var result = one(db, PROJECTS + " WHERE p.id=?", id);
        if (result == null) throw new Problem(404,"Mainline project not found.");
        projectFields(result);
        var latest = one(db,"SELECT id FROM mainline_dailies WHERE project_id=? ORDER BY date DESC,id DESC LIMIT 1",id);
        if (latest == null) result.putNull("latest_daily");
        else result.set("latest_daily",loadDailies(db," WHERE d.id=?",List.of(latest.path("id").asLong()),true).get(0));
        return result;
    }

    static boolean canSeeProject(Connection db, ObjectNode user, long id) throws SQLException {
        if (user==null) return false;
        if (admin(user)) return one(db,"SELECT id FROM mainline_projects WHERE id=?",id) != null;
        return one(db,"""
            SELECT p.id FROM mainline_projects p WHERE p.id=? AND (p.supervisor_person_id=? OR EXISTS(
                SELECT 1 FROM mainline_dailies d JOIN mainline_daily_members m ON m.daily_id=d.id
                WHERE d.project_id=p.id AND d.date=? AND m.person_id=? AND m.removed=0
                  AND (m.supervisor_only=0 OR p.supervisor_person_id=m.person_id)))
            """,id,user.path("id").asLong(),Db.today(),user.path("id").asLong()) != null;
    }

    private static boolean admin(ObjectNode user) { return user.path("is_admin").asBoolean(); }

    private static void requireAdmin(ObjectNode user) {
        if (!admin(user)) throw new Problem(403,"Administrator access required.");
    }

    private static String optionalString(ObjectNode input, String key, int limit) {
        return input.hasNonNull(key) ? StoreInput.text(input,key,0,limit) : "";
    }

    // Persist fixed point integers on both engines so fractional feet and unit prices round-trip exactly.
    private static Long centsOrUnits(ObjectNode input, String key, int scale) {
        if (!input.hasNonNull(key)) return null;
        var value=input.get(key);
        if (!value.isNumber() && !value.isTextual()) throw StoreInput.invalid(key);
        if (value.asText().isBlank()) return null;
        try {
            var number = new BigDecimal(value.asText().strip());
            if (number.signum()<0 || number.compareTo(BigDecimal.valueOf(100_000_000))>0)
                throw StoreInput.invalid(key);
            return number.movePointRight(scale).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw StoreInput.invalid(key);
        }
    }

    static ObjectNode saveProject(Connection db, ObjectNode user, Long id, ObjectNode input) throws SQLException {
        requireAdmin(user);
        StoreInput.fields(input,"name","location","supervisor","supervisor_person_id","state","lunch_provided","estimated_footage",
            "driver_cost","price_per_foot","due_date","revision");
        String name=StoreInput.text(input,"name",1,240), location=optionalString(input,"location",500),
            supervisor=optionalString(input,"supervisor",240), state=input.path("state").asText("Not Started");
        if (!STATES.contains(state)) throw StoreInput.invalid("state");
        String lunch = input.path("lunch_provided").asText("false").toLowerCase(java.util.Locale.ROOT);
        int provided = switch (lunch) {
            case "yes","1","true","on" -> 1;
            case "no","0","false","" -> 0;
            default -> throw StoreInput.invalid("lunch_provided");
        };
        var estimated=centsOrUnits(input,"estimated_footage",2);
        var driver=centsOrUnits(input,"driver_cost",2);
        var price=centsOrUnits(input,"price_per_foot",4);
        String due=optionalString(input,"due_date",10);
        String dueDate=due.isEmpty()?null:StoreInput.date(due);
        return transaction(db,true,()->{
            ObjectNode previous = id == null ? null : one(db, "SELECT supervisor,supervisor_person_id FROM mainline_projects WHERE id=?", id);
            if (id != null && previous == null) throw new Problem(404,"Mainline project not found.");
            Long oldSupervisor = previous != null && previous.hasNonNull("supervisor_person_id")
                ? previous.get("supervisor_person_id").asLong() : null;
            Long assigned = null;
            if (input.hasNonNull("supervisor_person_id") && !input.get("supervisor_person_id").asText().isBlank())
                assigned = StoreInput.integer(input.get("supervisor_person_id"), "supervisor_person_id");
            else if (!input.has("supervisor_person_id") && previous != null && supervisor.equals(previous.path("supervisor").asText()))
                assigned = oldSupervisor;
            String supervisorName = supervisor;
            if (assigned != null) {
                var person = one(db, "SELECT name FROM people WHERE id=? AND is_admin=0", assigned);
                if (person == null) throw new Problem(422, "Select a contractor account for the project supervisor.");
                supervisorName = person.get("name").asText();
            }
            long projectId;
            if (id==null) {
                projectId=insertId(db,"""
                    INSERT INTO mainline_projects(name,location,supervisor,supervisor_person_id,state,lunch_provided,estimated_hundredths,
                      driver_cents,price_ten_thousandths,due_date) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,name,location,supervisorName,assigned,state,provided,estimated,driver,price,dueDate);
            } else {
                projectId=id;
                long revision=StoreInput.integer(input.get("revision"),"revision");
                if (change(db,"""
                    UPDATE mainline_projects SET name=?,location=?,supervisor=?,supervisor_person_id=?,state=?,lunch_provided=?,estimated_hundredths=?,
                      driver_cents=?,price_ten_thousandths=?,due_date=?,revision=revision+1 WHERE id=? AND revision=?
                    """,name,location,supervisorName,assigned,state,provided,estimated,driver,price,dueDate,id,revision)!=1) {
                    throw new Problem(409,"This project changed. Reopen it before saving.");
                }
                if (!java.util.Objects.equals(oldSupervisor, assigned)) {
                    String today = Db.today();
                    if (oldSupervisor != null) {
                        // Keep the outgoing supervisor's role in the roster for completed work dates.
                        change(db, """
                            UPDATE mainline_daily_members SET supervisor_only=0
                            WHERE person_id=? AND supervisor_only=1 AND removed=0 AND crew_role<>'' AND daily_id IN
                                (SELECT id FROM mainline_dailies WHERE project_id=? AND date<?)
                            """,oldSupervisor,projectId,today);
                        // Preserve the earlier assignment on open schedules without restoring it on
                        // a past day if the same supervisor is later put back on the project.
                        change(db, """
                            UPDATE mainline_daily_members SET removed=1
                            WHERE person_id=? AND supervisor_only=1 AND removed=0 AND crew_role<>'' AND daily_id IN
                                (SELECT id FROM mainline_dailies WHERE project_id=? AND date>=?)
                            """,oldSupervisor,projectId,today);
                    }
                    change(db, """
                        DELETE FROM mainline_daily_members WHERE supervisor_only=1 AND is_driver=0 AND crew_role='' AND daily_id IN
                            (SELECT id FROM mainline_dailies WHERE project_id=? AND date>=?)
                        """, projectId, today);
                    if (assigned != null) change(db, """
                        INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver,supervisor_only)
                        SELECT id,?,?,0,1 FROM mainline_dailies WHERE project_id=? AND date>=?
                        ON CONFLICT (daily_id,person_id) DO UPDATE SET removed=0,supervisor_only=1
                        WHERE mainline_daily_members.removed=1
                        """, assigned, supervisorName, projectId, today);
                    change(db, "UPDATE mainline_dailies SET crew_revision=crew_revision+1 WHERE project_id=? AND date>=?", projectId, today);
                }
            }
            return readProject(db,projectId);
        });
    }

    static ArrayNode dailies(Connection db, ObjectNode user, String day, Long projectId) throws SQLException {
        String today = Db.today();
        String date = day == null ? null : StoreInput.date(day);
        if (!admin(user) && date!=null && !today.equals(date)) return App.JSON.createArrayNode();
        var condition = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (!admin(user)) date=today;
        if (date!=null) { condition.append(" AND d.date=?"); args.add(date); }
        if (projectId!=null) { condition.append(" AND d.project_id=?"); args.add(projectId); }
        if (!admin(user)) { condition.append(REPORT_SCOPE); args.add(user.path("id").asLong()); }
        return transaction(db,false,()->loadDailies(db,condition.toString(),args));
    }

    private static final String REPORT_SCOPE =
        " AND EXISTS(SELECT 1 FROM mainline_projects owned WHERE owned.id=d.project_id AND owned.supervisor_person_id=?)";

    // Workers need their schedule and planning PDFs on the home screen, including when they cannot open a report.
    static ArrayNode workerAssignments(Connection db, ObjectNode user) throws SQLException {
        var records = rows(db, """
            WITH mine AS (
                SELECT d.id,d.project_id,d.date,p.name AS project_name,p.supervisor_person_id
                FROM mainline_dailies d JOIN mainline_projects p ON p.id=d.project_id
                JOIN mainline_daily_members m ON m.daily_id=d.id
                WHERE d.date=? AND m.person_id=? AND m.removed=0
                    AND (m.supervisor_only=0 OR p.supervisor_person_id=m.person_id)
            ), details AS (
                SELECT a.*, 'crew_names' AS category,m.name AS label FROM mine a
                JOIN mainline_daily_members m ON m.daily_id=a.id
                WHERE m.removed=0 AND (m.supervisor_only=0 OR a.supervisor_person_id=m.person_id)
                UNION ALL
                SELECT a.*, 'documents' AS category,doc.kind AS label FROM mine a
                JOIN mainline_project_documents doc ON doc.project_id=a.project_id
            )
            SELECT * FROM details ORDER BY id DESC,category,lower(label),label
            """, Db.today(), user.path("id").asLong());
        var assignments = App.JSON.createArrayNode();
        ObjectNode current = null;
        for (var record : records) {
            if (current == null || current.path("id").asLong() != record.path("id").asLong()) {
                boolean ownsReport = record.hasNonNull("supervisor_person_id") &&
                    record.path("supervisor_person_id").asLong() == user.path("id").asLong();
                current = assignments.addObject().put("id", record.path("id").asLong())
                    .put("project_id", record.path("project_id").asLong())
                    .put("project_name", record.path("project_name").asText())
                    .put("date", record.path("date").asText()).put("can_report", ownsReport);
                current.putArray("crew_names");
                current.putArray("documents");
            }
            current.withArray(record.path("category").asText()).add(record.path("label").asText());
        }
        return assignments;
    }

    static ObjectNode daily(Connection db,ObjectNode user,long id) throws SQLException {
        return transaction(db,false,()->readDaily(db,user,id));
    }

    static boolean canManageCrew(Connection db, ObjectNode user, long dailyId) throws SQLException {
        if (admin(user)) return one(db, "SELECT id FROM mainline_dailies WHERE id=?", dailyId) != null;
        return one(db, """
            SELECT d.id FROM mainline_dailies d JOIN mainline_projects p ON p.id=d.project_id
            WHERE d.id=? AND d.date>=? AND p.supervisor_person_id=?
            """, dailyId, Db.today(), user.path("id").asLong()) != null;
    }

    static ObjectNode crewDaily(Connection db, ObjectNode user, long dailyId) throws SQLException {
        return transaction(db, false, () -> readCrewDaily(db,user,dailyId));
    }

    private static void requireCrewManagement(Connection db, ObjectNode user, long dailyId) throws SQLException {
        if (!canManageCrew(db,user,dailyId)) {
            if (admin(user)) throw new Problem(404, "Mainline daily not found.");
            throw new Problem(403, "You can manage crews on your assigned projects for today and upcoming work dates.");
        }
    }

    private static ObjectNode readCrewDaily(Connection db, ObjectNode user, long dailyId) throws SQLException {
        requireCrewManagement(db,user,dailyId);
        return (ObjectNode) loadDailies(db, " WHERE d.id=?", List.of(dailyId), true).get(0);
    }

    private static String dailyScope(ObjectNode user) {
        return admin(user) ? " WHERE d.id=?" : " WHERE d.id=? AND d.date=?"+REPORT_SCOPE;
    }

    private static List<?> dailyArgs(ObjectNode user,long id) {
        return admin(user) ? List.of(id) : List.of(id,Db.today(),user.path("id").asLong());
    }

    static void requireDaily(Connection db,ObjectNode user,long id) throws SQLException {
        if (one(db,"SELECT 1 FROM mainline_dailies d"+dailyScope(user),dailyArgs(user,id).toArray())==null)
            throw new Problem(404,"Mainline daily not found.");
    }

    static ObjectNode readDaily(Connection db,ObjectNode user,long id) throws SQLException {
        ArrayNode result=loadDailies(db,dailyScope(user),dailyArgs(user,id));
        if (result.isEmpty()) throw new Problem(404,"Mainline daily not found.");
        return (ObjectNode)result.get(0);
    }

    static ArrayNode readProjectDailies(Connection db,long projectId) throws SQLException {
        return loadDailies(db," WHERE d.project_id=?",List.of(projectId));
    }

    private static ArrayNode loadDailies(Connection db,String scope,List<?> arguments) throws SQLException {
        return loadDailies(db,scope,arguments,false);
    }

    private static ArrayNode loadDailies(Connection db,String scope,List<?> arguments,boolean scheduleOnly) throws SQLException {
        Object[] args=arguments.toArray();
        String fields = scheduleOnly ? "d.id,d.project_id,d.date,d.crew_revision,p.supervisor_person_id" : "d.*";
        var list=rows(db,"SELECT " + fields + ", p.name AS project_name FROM mainline_dailies d " + """
            JOIN mainline_projects p ON p.id=d.project_id
            """+scope+" ORDER BY d.date DESC,d.id DESC",args);
        if (list.isEmpty()) return list;
        var byId=new HashMap<Long,ObjectNode>();
        for (var node:list) {
            var row=(ObjectNode)node;
            if (!scheduleOnly) {
                scaled(row,"footage_hundredths","total_footage",2);
                try { row.set("expenses",App.JSON.readTree(row.path("expenses").asText())); }
                catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new SQLException("Invalid Mainline daily expenses.",ex); }
            }
            row.putArray("members"); row.putArray("driver_ids"); row.putArray("vehicles");
            byId.put(row.path("id").asLong(),row);
        }
        for (var node: rows(db,"""
                SELECT m.daily_id,m.person_id AS id,m.name,m.crew_role,m.is_driver,m.supervisor_only,m.removed,
                       CASE WHEN m.supervisor_only=1 AND (m.removed=1 OR p.supervisor_person_id IS NULL OR p.supervisor_person_id<>m.person_id)
                            THEN 1 ELSE 0 END AS former_supervisor
                FROM mainline_dailies d JOIN mainline_projects p ON p.id=d.project_id
                JOIN mainline_daily_members m ON m.daily_id=d.id
                """+scope+" ORDER BY m.daily_id,lower(m.name),m.person_id",args)) {
            var member=(ObjectNode)node;
            var daily=byId.get(member.remove("daily_id").asLong());
            boolean driver=member.path("is_driver").asInt()==1;
            member.put("is_driver",driver);
            member.put("supervisor_only",member.path("supervisor_only").asInt()==1);
            boolean former = member.path("former_supervisor").asInt()==1;
            member.put("former_supervisor",former);
            boolean removed = member.path("removed").asInt()==1;
            member.put("removed",removed);
            if (scheduleOnly && (removed || former)) continue;
            if (removed && !driver && !(former && !member.path("crew_role").asText().isEmpty())) continue;
            daily.withArray("members").add(member);
            if(driver) daily.withArray("driver_ids").add(member.path("id").asLong());
        }
        for (var node:rows(db,"""
                SELECT v.daily_id,v.vehicle_id AS id,v.name,v.brand,v.plate FROM mainline_dailies d
                JOIN mainline_daily_vehicles v ON v.daily_id=d.id
                """+scope+" ORDER BY v.daily_id,lower(v.name),v.vehicle_id",args)) {
            var vehicle=(ObjectNode)node;
            byId.get(vehicle.remove("daily_id").asLong()).withArray("vehicles").add(vehicle);
        }
        return list;
    }

    static ObjectNode createDaily(Connection db,ObjectNode user,ObjectNode input) throws SQLException {
        if (admin(user)) StoreInput.fields(input,"project_id","date","member_ids","member_roles","vehicle_ids","driver_ids","expenses","priority","comments","total_footage");
        else StoreInput.fields(input,"project_id","date","member_ids","member_roles","vehicle_ids");
        long projectId=StoreInput.integer(input.get("project_id"),"project_id");
        String date=StoreInput.date(StoreInput.text(input,"date",10,10));
        var members=StoreInput.ids(input,"member_ids",0,Integer.MAX_VALUE);
        var vehicles=input.has("vehicle_ids") ? StoreInput.ids(input,"vehicle_ids",0,Integer.MAX_VALUE) : Set.<Long>of();
        var drivers=input.has("driver_ids") ? StoreInput.ids(input,"driver_ids",0,Integer.MAX_VALUE) : Set.<Long>of();
        var values=DailyValues.from(input);
        return transaction(db,true,()->{
            var project = dispatchDetails(db,user,projectId);
            if (!admin(user) && date.compareTo(Db.today()) < 0)
                throw new Problem(403,"You can schedule crews for today and upcoming work dates.");
            Long autoMember = null;
            if (project.hasNonNull("supervisor_person_id")) {
                long supervisor = project.get("supervisor_person_id").asLong();
                if (!members.contains(supervisor) || !admin(user)) autoMember = supervisor;
                members.add(supervisor);
            }
            if (members.isEmpty()) throw new Problem(422,"Select at least one contractor for this daily crew.");
            if (!members.containsAll(drivers)) throw new Problem(422,"Select drivers from the daily crew.");
            var roles = crewRoles(input.get("member_roles"), members);
            long id=insertId(db,"""
                INSERT INTO mainline_dailies(project_id,date,footage_hundredths,expenses,priority,comments)
                VALUES(?,?,?,?,?,?)
                """,projectId,date,values.footage,values.expenses,values.priority,values.comments);
            insertDailyMembers(db, id, members, drivers, autoMember, roles);
            if (!vehicles.isEmpty()) {
                var available=new HashMap<Long,JsonNode>();
                for (var vehicle:Vehicles.list(db,date)) available.put(vehicle.path("id").asLong(),vehicle);
                for (long vehicleId:vehicles) {
                    var vehicle=available.get(vehicleId);
                    if (vehicle==null) throw new Problem(422,"Select a vehicle in the vehicle list.");
                    if (!vehicle.path("available").asBoolean())
                        throw new Problem(409,vehicle.path("name").asText()+" is already assigned on "+date+".");
                    change(db,"INSERT INTO mainline_daily_vehicles(daily_id,vehicle_id,name,brand,plate) VALUES(?,?,?,?,?)",
                           id,vehicleId,vehicle.get("name").asText(),vehicle.get("brand").asText(),vehicle.get("plate").asText());
                }
            }
            return !admin(user) && !date.equals(Db.today()) ? readCrewDaily(db,user,id) : readDaily(db,user,id);
        });
    }

    private static String crewRole(String role) {
        if (!role.isEmpty() && !CREW_ROLES.contains(role)) throw new Problem(422, "Choose a role from the team member's role list.");
        return role;
    }

    // Only keys for the selected people (including the project's automatic supervisor) may be written.
    private static Map<Long,String> crewRoles(JsonNode submitted, Set<Long> allowed) {
        if (submitted == null) return Map.of();
        if (!(submitted instanceof ObjectNode roles)) throw StoreInput.invalid("member_roles");
        var result = new HashMap<Long,String>();
        for (var entry : roles.properties()) {
            long person;
            try { person = Long.parseLong(entry.getKey()); }
            catch (NumberFormatException err) { throw StoreInput.invalid("member_roles"); }
            if (!Long.toString(person).equals(entry.getKey()) || !allowed.contains(person) || !entry.getValue().isTextual())
                throw new Problem(422,"Assign roles only to the selected team members.");
            result.put(person, crewRole(entry.getValue().textValue()));
        }
        return result;
    }

    private static void insertDailyMembers(Connection db, long dailyId, Set<Long> members, Set<Long> drivers, Long autoMember,
                                           Map<Long,String> roles) throws SQLException {
        try (var stmt = db.prepareStatement("""
            INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver,supervisor_only,crew_role)
            SELECT ?,id,name,?,?,? FROM people WHERE id=? AND is_admin=0
            """)) {
            for (long person : members) {
                bind(stmt, dailyId, drivers.contains(person) ? 1 : 0, java.util.Objects.equals(person, autoMember) ? 1 : 0,
                     roles.getOrDefault(person,""), person);
                stmt.addBatch();
            }
            for (int result : stmt.executeBatch())
                if (result == 0) throw new Problem(422, "Select valid contractors for the daily crew.");
        }
    }

    static ObjectNode addMembers(Connection db, ObjectNode user, long dailyId, ArrayNode memberIds) throws SQLException {
        return addMembers(db, user, dailyId, memberIds, null);
    }

    static ObjectNode addMembers(Connection db, ObjectNode user, long dailyId, ArrayNode memberIds, Long crewRevision) throws SQLException {
        return addMembers(db,user,dailyId,memberIds,null,crewRevision);
    }

    static ObjectNode addMembers(Connection db, ObjectNode user, long dailyId, ArrayNode memberIds,
                                 ObjectNode submittedRoles, Long crewRevision) throws SQLException {
        return transaction(db, true, () -> {
            requireCrewManagement(db,user,dailyId);
            if (submittedRoles != null && !submittedRoles.isEmpty() && crewRevision == null)
                throw new Problem(409,"Reopen the daily crew to assign roles.");
            if (crewRevision != null)
                checkCrewRevision(one(db,"SELECT crew_revision FROM mainline_dailies WHERE id=?",dailyId),crewRevision);
            if (memberIds == null || memberIds.isEmpty()) throw new Problem(422, "Select contractors to add to this daily.");
            var additions = new LinkedHashSet<Long>();
            for (JsonNode id : memberIds) additions.add(StoreInput.integer(id, "member_ids"));
            var roles = crewRoles(submittedRoles,additions);
            if (addToRoster(db,user,dailyId,additions,crewRevision != null,roles)) advanceCrewRevision(db,dailyId);
            return admin(user) ? readDaily(db, user, dailyId) : readCrewDaily(db, user, dailyId);
        });
    }

    // Called with the writer lock held. A new role selection overrides the role saved before removal.
    private static boolean addToRoster(Connection db, ObjectNode user, long dailyId, Set<Long> additions,
                                       boolean canRestoreRemoved, Map<Long,String> roles) throws SQLException {
        boolean restored = false;
        for (var member : rows(db, "SELECT person_id,supervisor_only,removed,crew_role FROM mainline_daily_members WHERE daily_id=?", dailyId)) {
            long person = member.path("person_id").asLong();
            if (!additions.remove(person)) continue;
            if (member.path("removed").asInt()==1 && !canRestoreRemoved)
                throw new Problem(409,"The crew changed. Reopen the crew before adding a removed member again.");
            boolean explicit = member.path("supervisor_only").asInt()==1 && (admin(user) || person!=user.path("id").asLong());
            boolean restore = member.path("removed").asInt()==1;
            String originalRole = member.get("crew_role").asText();
            String role = roles.getOrDefault(person,originalRole);
            if (restore || explicit || !role.equals(originalRole)) {
                int updated = change(db, """
                    UPDATE mainline_daily_members SET removed=0,supervisor_only=?,crew_role=? WHERE daily_id=? AND person_id=?
                      AND EXISTS(SELECT 1 FROM people WHERE id=? AND is_admin=0)
                    """,restore || explicit ? 0 : member.path("supervisor_only").asInt(),role,dailyId,person,person);
                if (updated!=1) throw new Problem(422,"Select a valid contractor for the daily crew.");
                restored = true;
            }
        }
        if (!additions.isEmpty()) {
            insertDailyMembers(db,dailyId,additions,Set.of(),null,roles);
            return true;
        }
        return restored;
    }

    private static void checkCrewRevision(ObjectNode daily, long expected) {
        if (daily.path("crew_revision").asLong()!=expected)
            throw new Problem(409,"The crew changed. Review the current crew and try again.");
    }

    private static void advanceCrewRevision(Connection db, long dailyId) throws SQLException {
        change(db,"UPDATE mainline_dailies SET crew_revision=crew_revision+1 WHERE id=?",dailyId);
    }

    static ObjectNode changeMember(Connection db, ObjectNode user, long dailyId, long departingId,
                                   Long replacementId, long crewRevision) throws SQLException {
        return changeMember(db,user,dailyId,departingId,replacementId,null,crewRevision);
    }

    static ObjectNode changeMember(Connection db, ObjectNode user, long dailyId, long departingId,
                                   Long replacementId, String replacementRole, long crewRevision) throws SQLException {
        return transaction(db,true,()->{
            var daily = readCrewDaily(db,user,dailyId);
            checkCrewRevision(daily,crewRevision);
            if (daily.hasNonNull("supervisor_person_id") && daily.get("supervisor_person_id").asLong()==departingId)
                throw new Problem(422,"Change the project supervisor in the project editor.");
            var active = new java.util.HashSet<Long>();
            for (var member : daily.path("members")) active.add(member.path("id").asLong());
            if (!active.contains(departingId)) throw new Problem(409,"This contractor is no longer on this daily crew.");
            if (replacementId!=null) {
                if (active.contains(replacementId)) throw new Problem(422,"Choose a replacement who is not already on the crew.");
                var roles = replacementRole == null ? Map.<Long,String>of() : Map.of(replacementId,crewRole(replacementRole));
                addToRoster(db,user,dailyId,new LinkedHashSet<>(Set.of(replacementId)),true,roles);
            } else if (replacementRole != null && !replacementRole.isEmpty())
                throw new Problem(422,"Select a replacement before assigning a replacement role.");
            change(db,"UPDATE mainline_daily_members SET removed=1 WHERE daily_id=? AND person_id=?",dailyId,departingId);
            advanceCrewRevision(db,dailyId);
            return readCrewDaily(db,user,dailyId);
        });
    }

    static ObjectNode updateCrewRoles(Connection db, ObjectNode user, long dailyId, ObjectNode submittedRoles,
                                      long crewRevision) throws SQLException {
        return transaction(db,true,()->{
            var daily = readCrewDaily(db,user,dailyId);
            checkCrewRevision(daily,crewRevision);
            var current = new HashMap<Long,ObjectNode>();
            for (var member : daily.path("members")) current.put(member.get("id").asLong(),(ObjectNode)member);
            var roles = crewRoles(submittedRoles,current.keySet());
            boolean changed = false;
            try (var update = db.prepareStatement("UPDATE mainline_daily_members SET crew_role=? WHERE daily_id=? AND person_id=?")) {
                for (var entry : roles.entrySet()) {
                    var member = current.get(entry.getKey());
                    if (!entry.getValue().equals(member.get("crew_role").asText())) {
                        bind(update,entry.getValue(),dailyId,entry.getKey());
                        update.addBatch();
                        member.put("crew_role",entry.getValue());
                        changed = true;
                    }
                }
                if (changed) update.executeBatch();
            }
            if (changed) {
                advanceCrewRevision(db,dailyId);
                daily.put("crew_revision",crewRevision+1);
            }
            return daily;
        });
    }

    record DailyValues(long footage,String expenses,String priority,String comments) {
        static DailyValues from(ObjectNode input) {
            Long feet=centsOrUnits(input,"total_footage",2);
            String priority=optionalString(input,"priority",10);
            if (!priority.isEmpty() && !PRIORITIES.contains(priority)) throw StoreInput.invalid("priority");
            var expenses=App.JSON.createArrayNode();
            if (input.has("expenses")) {
                for (JsonNode entry:StoreInput.array(input,"expenses",0,500)) {
                    if (!(entry instanceof ObjectNode expense)) throw StoreInput.invalid("expenses");
                    StoreInput.fields(expense,"type","cost");
                    String type=StoreInput.text(expense,"type",1,30);
                    Long cost=centsOrUnits(expense,"cost",2);
                    if (!EXPENSE_TYPES.contains(type) || cost==null) throw StoreInput.invalid("expenses");
                    expenses.addObject().put("type",type).put("cost",BigDecimal.valueOf(cost,2));
                }
            }
            return new DailyValues(feet==null?0:feet,expenses.toString(),priority.isEmpty()?null:priority,optionalString(input,"comments",500));
        }
    }

    static ObjectNode saveFootage(Connection db,ObjectNode user,long id,long revision,BigDecimal feet) throws SQLException {
        var input=App.JSON.createObjectNode().put("revision",revision).put("total_footage",feet);
        return saveDaily(db,user,id,input);
    }

    static ObjectNode saveDaily(Connection db,ObjectNode user,long id,ObjectNode input) throws SQLException {
        return transaction(db,true,()->updateDaily(db,user,id,input));
    }

    static ObjectNode updateDaily(Connection db,ObjectNode user,long id,ObjectNode input) throws SQLException {
        StoreInput.fields(input,"revision","total_footage","expenses","priority","comments","driver_ids","initial_driver_ids");
        ObjectNode current=readDaily(db,user,id);
        long revision=StoreInput.integer(input.get("revision"),"revision");
        if (revision!=current.path("revision").asLong()) throw new Problem(409,"This daily changed. Reopen it before saving.");
        var draft=App.JSON.createObjectNode();
        for (String field:List.of("total_footage","expenses","priority","comments"))
            draft.set(field,input.has(field)?input.get(field):current.get(field));
        var values=DailyValues.from(draft);
        if (input.has("driver_ids")) {
            var drivers=StoreInput.ids(input,"driver_ids",0,Integer.MAX_VALUE);
            var members=new java.util.HashSet<Long>();
            // A report draft may still contain a driver who was removed from the crew while the form was open.
            for (var member:rows(db,"SELECT person_id FROM mainline_daily_members WHERE daily_id=?",id))
                members.add(member.path("person_id").asLong());
            if (!members.containsAll(drivers)) throw new Problem(422,"Select drivers from the daily crew.");
            // Only clear checkboxes the editor originally saw checked. Someone restored to the crew
            // while the report was open may already have recorded driver credit.
            var previouslyChecked = input.has("initial_driver_ids")
                ? StoreInput.ids(input,"initial_driver_ids",0,Integer.MAX_VALUE)
                : StoreInput.ids(current,"driver_ids",0,Integer.MAX_VALUE);
            previouslyChecked.removeAll(drivers);
            try (var stmt=db.prepareStatement("UPDATE mainline_daily_members SET is_driver=0 WHERE daily_id=? AND person_id=? AND removed=0")) {
                for (long person : previouslyChecked) { bind(stmt,id,person); stmt.addBatch(); }
                stmt.executeBatch();
            }
            try (var stmt=db.prepareStatement("UPDATE mainline_daily_members SET is_driver=1 WHERE daily_id=? AND person_id=?")) {
                for (long person:drivers) { bind(stmt,id,person); stmt.addBatch(); }
                stmt.executeBatch();
            }
        }
        if (change(db,"""
            UPDATE mainline_dailies SET footage_hundredths=?,expenses=?,priority=?,comments=?,revision=revision+1
            WHERE id=? AND revision=?
            """,values.footage,values.expenses,values.priority,values.comments,id,revision)!=1)
            throw new Problem(409,"This daily changed. Reopen it before saving.");
        return readDaily(db,user,id);
    }
}
