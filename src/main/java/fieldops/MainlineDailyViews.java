package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;
import static fieldops.Views.*;

final class MainlineDailyViews {
    private MainlineDailyViews() {}

    static void assign(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       long projectId, boolean post) throws Exception {
        var project = Mainline.project(db, projectId);
        String projectPath = MainlineViews.projectPath(project);
        String date = param(req, "date");
        if (!post && date.isEmpty()) date = value(user, "today");
        var draft = App.JSON.createObjectNode().put("project_id", projectId).put("date", date);
        collectIds(req, "member", draft.putArray("member_ids"));
        collectIds(req, "vehicle", draft.putArray("vehicle_ids"));
        String picker = param(req, "picker");
        String action = param(req, "action");
        String problem = "";
        if (post && action.equals("previous")) {
            var latest = project.path("latest_daily");
            if (latest.isObject()) {
                copyIds(latest.path("members"), draft.putArray("member_ids"));
                copyIds(latest.path("vehicles"), draft.putArray("vehicle_ids"));
            }
        } else if (post && picker.isEmpty() && !action.equals("availability")) {
            try {
                if (draft.path("member_ids").isEmpty()) throw new Problem(422, "Select at least one contractor for this daily crew.");
                var daily = Mainline.createDaily(db, user, draft);
                redirect(res, "/mainline/dailies/" + daily.path("id").asLong());
                return;
            } catch (Problem err) {
                res.setStatus(err.status);
                problem = err.getMessage();
            }
        }

        String vehicleDate;
        try { vehicleDate = StoreInput.date(date); }
        catch (Problem err) { vehicleDate = value(user, "today"); }
        var form = new StringBuilder("<form class='panel stack editor' method='post'>").append(alert(problem))
            .append("<p class='helper'>Select everyone working on this project for the work date. The crew shares one daily report.</p>")
            .append(field("Work date", "date", date, "type='date' required"))
            .append("<div class='actions'>");
        if (project.path("latest_daily").isObject())
            form.append("<button class='secondary' name='action' value='previous' formnovalidate>Reuse crew and vehicles from ")
                .append(h(value(project.path("latest_daily"), "date"))).append("</button>");
        form.append("</div>").append(AdminViews.contractorPicker(Store.profiles(db), draft,
            picker.equals("all") ? "" : param(req, "contractor_search")))
            .append("<fieldset class='stack'><legend>Vehicles</legend><div class='actions'><button class='secondary' name='action' value='availability' formnovalidate>Check availability for date</button></div>");
        var vehicles = Vehicles.list(db, vehicleDate);
        var selected = idSet(draft.path("vehicle_ids"));
        if (vehicles.isEmpty()) form.append(link("Add a vehicle", "/vehicles/new", "text-button"));
        else form.append("<div class='choices'>");
        for (var vehicle : vehicles) {
            boolean available = vehicle.path("available").asBoolean();
            String key = value(vehicle, "id");
            form.append("<label><input type='checkbox' name='vehicle' value='").append(h(key)).append("'")
                .append(available && selected.contains(key) ? " checked" : "").append(available ? "" : " disabled")
                .append("><span>").append(h(value(vehicle, "name")));
            for (String attr : new String[]{"brand", "plate"}) {
                String detail = value(vehicle, attr);
                if (!detail.isBlank()) form.append(" · ").append(h(detail));
            }
            if (!available) form.append(" · Assigned on ").append(h(vehicleDate));
            form.append("</span></label>");
        }
        if (!vehicles.isEmpty()) form.append("</div>");
        form.append("</fieldset><div class='actions'>").append(link("Cancel", projectPath, "btn secondary"))
            .append("<button name='action' value='create'>Create daily report</button></div></form>");
        page(res, user, "Assign daily crew", MainlineViews.heading("Assign daily crew", value(project,"name"), projectPath, "") + form);
    }

    private static void collectIds(HttpServletRequest req, String field, ArrayNode target) {
        for (String id : params(req, field)) target.add(id);
    }

    private static void copyIds(JsonNode records, ArrayNode target) {
        for (var record : records) target.add(record.path("id"));
    }

    private static Set<String> idSet(JsonNode list) {
        var result = new HashSet<String>();
        for (var id : list) result.add(id.asText());
        return result;
    }

    static void crew(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                     long dailyId, boolean post) throws Exception {
        if (!user.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        var daily = Mainline.daily(db, user, dailyId);
        String path = "/mainline/dailies/" + dailyId;
        var draft = App.JSON.createObjectNode();
        var selected = draft.putArray("member_ids");
        collectIds(req, "member", selected);
        String picker = param(req, "picker");
        String problem = "";
        if (post && picker.isEmpty()) {
            try {
                Mainline.addMembers(db, user, dailyId, selected);
                redirect(res, path + "?crew=1");
                return;
            } catch (Problem err) {
                res.setStatus(err.status);
                problem = err.getMessage();
            }
        }

        String title = "Add team members";
        var form = new StringBuilder("<section class='panel stack editor'>").append(alert(problem))
            .append("<p>").append(h(value(daily, "project_name"))).append(" · ").append(h(value(daily, "date"))).append("</p>")
            .append("<details><summary>Current crew (").append(daily.path("members").size()).append(")</summary><ul>");
        var assigned = new HashSet<Long>();
        for (var member : daily.path("members")) {
            assigned.add(member.path("id").asLong());
            form.append("<li>").append(h(value(member, "name"))).append("</li>");
        }
        form.append("</ul></details>");
        var candidates = App.JSON.createArrayNode();
        for (var person : Store.profiles(db))
            if (!assigned.contains(person.path("id").asLong())) candidates.add(person);
        if (candidates.isEmpty()) form.append("<p>All contractors are on this daily crew.</p>");
        else {
            form.append("<form class='stack' method='post'>")
                .append(AdminViews.contractorPicker(candidates, draft, picker.equals("all") ? "" : param(req, "contractor_search")))
                .append("<div class='actions'>").append(link("Cancel", path, "btn secondary"))
                .append("<button name='action' value='add'>Add to daily crew</button></div></form>");
        }
        form.append("</section>");
        page(res, user, title, MainlineViews.heading(title, "Daily report", path, "") + form);
    }

    static void report(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       long dailyId, boolean post) throws Exception {
        var daily = Mainline.daily(db, user, dailyId);
        String path = "/mainline/dailies/" + dailyId;
        ObjectNode draft = daily;
        String problem = "";
        if (post) {
            String action = param(req, "action");
            draft = App.JSON.createObjectNode().put("revision", param(req,"revision"))
                .put("priority",param(req,"priority")).put("comments",param(req,"comments"))
                .put("total_footage",param(req,"total_footage"));
            collectIds(req, "driver", draft.putArray("driver_ids"));
            var types = params(req, "expense_type");
            var costs = params(req, "expense_cost");
            if (costs.size() != types.size() || types.size() > 500) throw StoreInput.invalid("expenses");
            var expenses = draft.putArray("expenses");
            for (int i = 0; i < types.size(); i++) {
                if (action.equals("remove-expense-" + i)) continue;
                if (action.equals("save") && types.get(i).isBlank() && costs.get(i).isBlank()) continue;
                expenses.addObject().put("type",types.get(i)).put("cost",costs.get(i));
            }
            if (action.equals("add-expense")) expenses.addObject();
            else if (action.equals("save")) {
                try {
                    Mainline.saveDaily(db, user, dailyId, draft);
                    redirect(res, path + "?saved=1");
                    return;
                } catch (Problem err) {
                    res.setStatus(err.status);
                    problem = err.getMessage();
                }
            } else if (!action.matches("remove-expense-[0-9]+")) throw new Problem(422,"Choose a daily report action.");
        }
        long projectId = daily.path("project_id").asLong();
        boolean admin = user.path("is_admin").asBoolean();
        String projectName = value(daily,"project_name");
        String title = projectName + " · " + value(daily,"date");
        String heading = admin ? MainlineViews.heading(title, "Project", "/mainline/projects/" + projectId,
            "<a class='btn secondary' href='" + path + "/crew' target='_blank' rel='noopener'>Add team members (opens in a new tab)</a>") :
            link("← My route", "/", "worker-back worker-text-button") + "<h1>" + h(title) + "</h1>";
        var form = new StringBuilder("<div class='stack'>").append(heading);
        if (param(req,"saved").equals("1") && !post) form.append(notice("Daily report saved."));
        if (admin && param(req, "crew").equals("1") && !post) form.append(notice("Daily crew updated."));
        form.append("<form method='post' class='panel stack editor'>").append(alert(problem))
            .append(hidden("revision", value(draft,"revision"))).append("<h2>Daily report</h2>")
            .append(field("Total footage (ft)", "total_footage", value(draft,"total_footage"), "type='number' min='0' step='0.01'"))
            .append("<details open><summary>Team members (").append(daily.path("members").size()).append(")</summary>")
            .append("<fieldset><legend>Drivers</legend><div class='choices'>");
        var drivers = idSet(draft.path("driver_ids"));
        for (var member : daily.path("members")) {
            String key = value(member,"id");
            form.append("<label><input type='checkbox' name='driver' value='").append(h(key))
                .append(drivers.contains(key) ? "' checked>" : "'>").append(h(value(member,"name"))).append("</label>");
        }
        form.append("</div></fieldset></details>").append(vehicleLine(daily));
        form.append("<fieldset class='stack'><legend>Expenses</legend>");
        int i = 0;
        for (var expense : draft.path("expenses")) {
            String type = value(expense,"type");
            form.append("<div class='two'><label>Expense type<select name='expense_type'>")
                .append(option("","Select type",type));
            for (String choice : Mainline.EXPENSE_TYPES) form.append(option(choice,choice,type));
            form.append("</select></label>").append(field("Cost ($)", "expense_cost", value(expense,"cost"),
                "type='number' min='0' step='0.01'")).append("</div><button name='action' value='remove-expense-")
                .append(i++).append("' class='text-button' formnovalidate>Remove expense</button>");
        }
        form.append("<div><button class='secondary' name='action' value='add-expense' formnovalidate>Add expense</button></div></fieldset>")
            .append("<label>Priority<select name='priority'>").append(option("","Select priority",value(draft,"priority")));
        for (var priority : Mainline.PRIORITIES) form.append(option(priority,priority,value(draft,"priority")));
        form.append("</select></label><label>Comments<textarea name='comments' maxlength='500' rows='4'>")
            .append(h(value(draft,"comments"))).append("</textarea></label><div class='actions'><button name='action' value='save'>Save daily report</button></div></form>")
            .append(MainlineMedia.renderSummary(db,user,dailyId))
            .append(MainlineViews.documents(db,user,projectId,false)).append("</div>");
        page(res, user, title, form.toString());
    }
}
