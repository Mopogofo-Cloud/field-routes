package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
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
            SELECT d.id FROM mainline_dailies d JOIN mainline_daily_members m ON m.daily_id=d.id
            WHERE d.project_id=? AND d.date=? AND m.person_id=? LIMIT 1
            """,id,Db.today(),user.path("id").asLong()) != null;
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
        StoreInput.fields(input,"name","location","supervisor","state","lunch_provided","estimated_footage",
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
            long projectId;
            if (id==null) {
                projectId=insertId(db,"""
                    INSERT INTO mainline_projects(name,location,supervisor,state,lunch_provided,estimated_hundredths,
                      driver_cents,price_ten_thousandths,due_date) VALUES(?,?,?,?,?,?,?,?,?)
                    """,name,location,supervisor,state,provided,estimated,driver,price,dueDate);
            } else {
                projectId=id;
                long revision=StoreInput.integer(input.get("revision"),"revision");
                if (change(db,"""
                    UPDATE mainline_projects SET name=?,location=?,supervisor=?,state=?,lunch_provided=?,estimated_hundredths=?,
                      driver_cents=?,price_ten_thousandths=?,due_date=?,revision=revision+1 WHERE id=? AND revision=?
                    """,name,location,supervisor,state,provided,estimated,driver,price,dueDate,id,revision)!=1) {
                    if (one(db,"SELECT id FROM mainline_projects WHERE id=?",id)==null) throw new Problem(404,"Mainline project not found.");
                    throw new Problem(409,"This project changed. Reopen it before saving.");
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

    private static final String MEMBER_SCOPE = " AND EXISTS(SELECT 1 FROM mainline_daily_members a WHERE a.daily_id=d.id AND a.person_id=?)";

    static ObjectNode daily(Connection db,ObjectNode user,long id) throws SQLException {
        return transaction(db,false,()->readDaily(db,user,id));
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
        Object[] args=arguments.toArray();
        var list=rows(db,"""
            SELECT d.*,p.name AS project_name FROM mainline_dailies d
            JOIN mainline_projects p ON p.id=d.project_id
            """+scope+" ORDER BY d.date DESC,d.id DESC",args);
        if (list.isEmpty()) return list;
        var byId=new HashMap<Long,ObjectNode>();
        for (var node:list) {
            var row=(ObjectNode)node;
            scaled(row,"footage_hundredths","total_footage",2);
            try { row.set("expenses",App.JSON.readTree(row.path("expenses").asText())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new SQLException("Invalid Mainline daily expenses.",ex); }
            row.putArray("members"); row.putArray("driver_ids"); row.putArray("vehicles");
            byId.put(row.path("id").asLong(),row);
        }
        for (var node: rows(db,"""
                SELECT m.daily_id,m.person_id AS id,m.name,m.is_driver FROM mainline_dailies d
                JOIN mainline_daily_members m ON m.daily_id=d.id
                """+scope+" ORDER BY m.daily_id,lower(m.name),m.person_id",args)) {
            var member=(ObjectNode)node;
            var daily=byId.get(member.remove("daily_id").asLong());
            boolean driver=member.path("is_driver").asInt()==1;
            member.put("is_driver",driver);
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
        requireAdmin(user);
        StoreInput.fields(input,"project_id","date","member_ids","vehicle_ids","driver_ids","expenses","priority","comments","total_footage");
        long projectId=StoreInput.integer(input.get("project_id"),"project_id");
        String date=StoreInput.date(StoreInput.text(input,"date",10,10));
        var members=StoreInput.ids(input,"member_ids",1,Integer.MAX_VALUE);
        var vehicles=input.has("vehicle_ids") ? StoreInput.ids(input,"vehicle_ids",0,Integer.MAX_VALUE) : Set.<Long>of();
        var drivers=input.has("driver_ids") ? StoreInput.ids(input,"driver_ids",0,Integer.MAX_VALUE) : Set.<Long>of();
        if (!members.containsAll(drivers)) throw new Problem(422,"Select drivers from the daily crew.");
        var values=DailyValues.from(input);
        return transaction(db,true,()->{
            if (one(db,"SELECT id FROM mainline_projects WHERE id=?",projectId)==null)
                throw new Problem(404,"Mainline project not found.");
            long id=insertId(db,"""
                INSERT INTO mainline_dailies(project_id,date,footage_hundredths,expenses,priority,comments)
                VALUES(?,?,?,?,?,?)
                """,projectId,date,values.footage,values.expenses,values.priority,values.comments);
            try (var stmt=db.prepareStatement("""
                INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver)
                SELECT ?,id,name,? FROM people WHERE id=? AND is_admin=0
                """)) {
                for (long person:members) {
                    bind(stmt,id,drivers.contains(person)?1:0,person);
                    stmt.addBatch();
                }
                for (int result:stmt.executeBatch()) if (result==0) throw new Problem(422,"Select valid contractors for the daily crew.");
            }
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
            return readDaily(db,user,id);
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
