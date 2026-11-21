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
import java.util.Set;
import static fieldops.StoreSql.*;

final class Mainline {
    private Mainline() {}
    static final List<String> STATES = List.of("Not Started", "In Progress", "Completed");
    static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");
    static final List<String> EXPENSE_TYPES = List.of("Gas", "Supplies", "Maintenance", "Hotel", "Other");

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
        else result.set("latest_daily",loadDailies(db," WHERE d.id=?",List.of(latest.path("id").asLong())).get(0));
        return result;
    }

    static boolean canSeeProject(Connection db, ObjectNode user, long id) throws SQLException {
        if (user==null) return false;
        if (admin(user)) return one(db,"SELECT id FROM mainline_projects WHERE id=?",id) != null;
        return one(db,"""
            SELECT p.id FROM mainline_projects p WHERE p.id=? AND (p.supervisor_person_id=? OR EXISTS(
                SELECT 1 FROM mainline_dailies d JOIN mainline_daily_members m ON m.daily_id=d.id
                WHERE d.project_id=p.id AND d.date=? AND m.person_id=?
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
                    change(db, """
                        DELETE FROM mainline_daily_members WHERE supervisor_only=1 AND is_driver=0 AND daily_id IN
                            (SELECT id FROM mainline_dailies WHERE project_id=? AND date>=?)
                        """, projectId, today);
                    if (assigned != null) change(db, """
                        INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver,supervisor_only)
                        SELECT id,?,?,0,1 FROM mainline_dailies WHERE project_id=? AND date>=?
                        ON CONFLICT (daily_id,person_id) DO NOTHING
                        """, assigned, supervisorName, projectId, today);
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
        if (!admin(user)) { condition.append(MEMBER_SCOPE); args.add(user.path("id").asLong()); }
        return transaction(db,false,()->loadDailies(db,condition.toString(),args));
    }

    // A former supervisor can still appear as a reported driver. That history does not confer access.
    private static final String MEMBER_SCOPE = " " + """
        AND EXISTS(SELECT 1 FROM mainline_daily_members a JOIN mainline_projects owned ON owned.id=d.project_id
                   WHERE a.daily_id=d.id AND a.person_id=? AND (a.supervisor_only=0 OR owned.supervisor_person_id=a.person_id))
        """;

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
        return admin(user) ? " WHERE d.id=?" : " WHERE d.id=? AND d.date=?"+MEMBER_SCOPE;
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
        String fields = scheduleOnly ? "d.id,d.project_id,d.date" : "d.*";
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
                SELECT m.daily_id,m.person_id AS id,m.name,m.is_driver,m.supervisor_only,
                       CASE WHEN m.supervisor_only=1 AND (p.supervisor_person_id IS NULL OR p.supervisor_person_id<>m.person_id)
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
            if (scheduleOnly && former) continue;
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
        if (admin(user)) StoreInput.fields(input,"project_id","date","member_ids","vehicle_ids","driver_ids","expenses","priority","comments","total_footage");
        else StoreInput.fields(input,"project_id","date","member_ids","vehicle_ids");
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
            long id=insertId(db,"""
                INSERT INTO mainline_dailies(project_id,date,footage_hundredths,expenses,priority,comments)
                VALUES(?,?,?,?,?,?)
                """,projectId,date,values.footage,values.expenses,values.priority,values.comments);
            insertDailyMembers(db, id, members, drivers, autoMember);
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

    private static void insertDailyMembers(Connection db, long dailyId, Set<Long> members, Set<Long> drivers, Long autoMember) throws SQLException {
        try (var stmt = db.prepareStatement("""
            INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver,supervisor_only)
            SELECT ?,id,name,?,? FROM people WHERE id=? AND is_admin=0
            """)) {
            for (long person : members) {
                bind(stmt, dailyId, drivers.contains(person) ? 1 : 0, java.util.Objects.equals(person, autoMember) ? 1 : 0, person);
                stmt.addBatch();
            }
            for (int result : stmt.executeBatch())
                if (result == 0) throw new Problem(422, "Select valid contractors for the daily crew.");
        }
    }

    static ObjectNode addMembers(Connection db, ObjectNode user, long dailyId, ArrayNode memberIds) throws SQLException {
        return transaction(db, true, () -> {
            requireCrewManagement(db,user,dailyId);
            if (memberIds == null || memberIds.isEmpty()) throw new Problem(422, "Select contractors to add to this daily.");
            var additions = new LinkedHashSet<Long>();
            for (JsonNode id : memberIds) additions.add(StoreInput.integer(id, "member_ids"));
            for (var member : rows(db, "SELECT person_id,supervisor_only FROM mainline_daily_members WHERE daily_id=?", dailyId)) {
                long person = member.path("person_id").asLong();
                if (additions.remove(person) && member.path("supervisor_only").asInt() == 1 &&
                        (admin(user) || person != user.path("id").asLong()))
                    change(db, "UPDATE mainline_daily_members SET supervisor_only=0 WHERE daily_id=? AND person_id=?", dailyId, person);
            }
            // New members start as non-drivers. Existing report drafts remain valid and can still be saved.
            if (!additions.isEmpty()) insertDailyMembers(db, dailyId, additions, Set.of(), null);
            return admin(user) ? readDaily(db, user, dailyId) : readCrewDaily(db, user, dailyId);
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
        StoreInput.fields(input,"revision","total_footage","expenses","priority","comments","driver_ids");
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
            for (var member:current.path("members")) members.add(member.path("id").asLong());
            if (!members.containsAll(drivers)) throw new Problem(422,"Select drivers from the daily crew.");
            change(db,"UPDATE mainline_daily_members SET is_driver=0 WHERE daily_id=? AND is_driver<>0",id);
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
