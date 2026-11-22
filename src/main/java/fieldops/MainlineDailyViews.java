package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static fieldops.Views.*;

final class MainlineDailyViews {
    private MainlineDailyViews() {}

    static void assign(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       long projectId, boolean post) throws Exception {
        var project = Mainline.dispatchProject(db, user, projectId);
        boolean admin = user.path("is_admin").asBoolean();
        String today = value(user, "today");
        long supervisorId = project.path("supervisor_person_id").asLong();
        String projectPath = MainlineViews.projectPath(project);
        String date = param(req, "date");
        if (!post && date.isEmpty()) date = today;
        var draft = App.JSON.createObjectNode().put("project_id", projectId).put("date", date);
        var members = draft.putArray("member_ids");
        for (String member : params(req, "member")) {
            if (!member.equals(Long.toString(supervisorId))) members.add(member);
        }
        var roles = draft.putObject("member_roles");
        for (var member : members) submittedRole(req, roles, member.asText());
        if (supervisorId != 0) submittedRole(req, roles, Long.toString(supervisorId));
        collectIds(req, "vehicle", draft.putArray("vehicle_ids"));
        String picker = param(req, "picker");
        String action = param(req, "action");
        String problem = "";
        if (post && action.equals("previous")) {
            var latest = project.path("latest_daily");
            if (latest.isObject()) {
                members.removeAll();
                roles.removeAll();
                for (var member : latest.path("members")) {
                    boolean supervisor = member.path("id").asLong() == supervisorId;
                    if (!supervisor && member.path("supervisor_only").asBoolean()) continue;
                    if (!supervisor) members.add(member.path("id"));
                    roles.put(value(member, "id"), value(member, "crew_role"));
                }
                copyIds(latest.path("vehicles"), draft.putArray("vehicle_ids"));
            }
        } else if (post && picker.isEmpty() && !action.equals("availability")) {
            try {
                if (supervisorId == 0 && members.isEmpty()) throw new Problem(422, "Select at least one contractor for this daily crew.");
                var daily = Mainline.createDaily(db, user, draft);
                redirect(res, admin || today.equals(value(daily, "date")) ? "/mainline/dailies/" + daily.path("id").asLong() :
                    projectPath + "?scheduled=1");
                return;
            } catch (Problem err) {
                if (err.status == 403 || err.status == 404) throw err;
                res.setStatus(err.status);
                problem = err.getMessage();
            }
        }

        String vehicleDate;
        try { vehicleDate = StoreInput.date(date); }
        catch (Problem err) { vehicleDate = today; }
        if (!admin && vehicleDate.compareTo(today) < 0) {
            vehicleDate = today;
            res.setStatus(422);
            problem = "Choose today or a future work date.";
        }
        var form = new StringBuilder("<form class='panel stack editor' method='post'>").append(alert(problem))
            .append("<p class='helper'>Select the contractors working on this project for the work date.</p>")
            .append(field("Work date", "date", date, "type='date' required" + (admin ? "" : " min='" + h(today) + "'")));
        if (supervisorId != 0) {
            String person = Long.toString(supervisorId), name = value(project, "supervisor");
            form.append("<div class='crew-role-row'><p><strong>Supervisor on this crew</strong><br>")
                .append(h(name)).append(" (added automatically)</p>")
                .append(roleSelect("role_" + person, name, value(roles, person))).append("</div>");
        }
        form.append("<div class='actions'>");
        if (project.path("latest_daily").isObject())
            form.append("<button class='secondary' name='action' value='previous' formnovalidate>Reuse crew and vehicles from ")
                .append(h(value(project.path("latest_daily"), "date"))).append("</button>");
        var candidates = candidates(db, admin, Set.of(supervisorId));
        form.append("</div>");
        if (!candidates.isEmpty() || supervisorId == 0) form.append(contractorPicker(candidates, draft,
            picker.equals("all") ? "" : param(req, "contractor_search"), admin));
        form.append("<fieldset class='stack'><legend>Vehicles</legend><div class='actions'><button class='secondary' name='action' value='availability' formnovalidate>Check availability for date</button></div>");
        form.append(vehicleChoices(db,vehicleDate,java.util.List.of(),idSet(draft.path("vehicle_ids")),admin));
        form.append("</fieldset><div class='actions'>").append(link("Cancel", projectPath, "btn secondary"))
            .append("<button name='action' value='create'>Create daily report</button></div></form>");
        String heading = admin ? MainlineViews.heading("Assign daily crew", value(project, "name"), projectPath, "") :
            MainlineViews.workerHeading("Assign daily crew", value(project, "name"), projectPath, "");
        page(res, user, "Assign daily crew", heading + form);
    }

    private static String vehicleChoices(Connection db, String date, Iterable<JsonNode> assigned, Set<String> selected,
                                         boolean admin) throws java.sql.SQLException {
        var choices = new StringBuilder();
        var assignedIds = new HashSet<String>();
        for (var vehicle : assigned) {
            assignedIds.add(value(vehicle,"id"));
            vehicleChoice(choices,vehicle,admin,true,true);
        }
        for (var vehicle : Vehicles.list(db,date)) {
            String key = value(vehicle,"id");
            boolean available = vehicle.path("available").asBoolean();
            if (assignedIds.contains(key) || (!available && !admin)) continue;
            vehicleChoice(choices,vehicle,admin,available,available && selected.contains(key));
        }
        if (choices.isEmpty()) return admin ? link("Add a vehicle", "/vehicles/new", "text-button") :
            "<p>No vehicles available for this date.</p>";
        return "<div class='choices'>" + choices + "</div>";
    }

    private static void vehicleChoice(StringBuilder out, JsonNode vehicle, boolean admin, boolean available, boolean checked) {
        out.append("<label><input type='checkbox' name='vehicle' value='").append(h(value(vehicle,"id"))).append("'")
            .append(checked ? " checked" : "").append(available ? "" : " disabled")
            .append("><span>").append(h(value(vehicle,"name")));
        if (admin) {
            for (String attr : new String[]{"brand","plate"}) {
                String detail = value(vehicle,attr);
                if (!detail.isBlank()) out.append(" · ").append(h(detail));
            }
        }
        if (!available) out.append(" · Unavailable");
        out.append(vehicleRecordId(vehicle)).append("</span></label>");
    }

    private static void collectIds(HttpServletRequest req, String field, ArrayNode target) {
        for (String id : params(req, field)) target.add(id);
    }

    private static void copyIds(JsonNode records, ArrayNode target) {
        for (var record : records) target.add(record.path("id"));
    }

    private static void submittedRole(HttpServletRequest req, ObjectNode roles, String person) {
        String role = req.getParameter("role_" + person);
        if (role != null) roles.put(person, role);
    }

    private static String contractorPicker(ArrayNode candidates, ObjectNode draft, String query, boolean admin) {
        return AdminViews.contractorPicker(candidates, draft, query, admin,
            (person, name) -> roleSelect("role_" + person, name, value(draft.path("member_roles"), person)));
    }

    private static String roleSelect(String fieldName, String contractor, String selected) {
        var out = new StringBuilder("<select name='").append(h(fieldName))
            .append("' aria-label='Daily role for ").append(h(contractor)).append("'>")
            .append(option("", "Choose role", selected));
        for (String role : Mainline.CREW_ROLES) out.append(option(role, role, selected));
        return out.append("</select>").toString();
    }

    private static Set<String> idSet(JsonNode list) {
        var result = new HashSet<String>();
        for (var id : list) result.add(id.asText());
        return result;
    }

    private static ArrayNode candidates(Connection db, boolean admin, Set<Long> excluded) throws java.sql.SQLException {
        var candidates = App.JSON.createArrayNode();
        for (var person : admin ? Store.profiles(db) : Store.contractorAccounts(db))
            if (!excluded.contains(person.path("id").asLong())) candidates.add(person);
        return candidates;
    }

    static void crew(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                     long dailyId, boolean post) throws Exception {
        var daily = Mainline.crewDaily(db, user, dailyId);
        boolean admin = user.path("is_admin").asBoolean();
        String path = "/mainline/dailies/" + dailyId;
        boolean reportAvailable = admin || value(user, "today").equals(value(daily, "date"));
        String backPath = reportAvailable ? path : "/mainline/projects/" + daily.path("project_id").asLong();
        String backLabel = reportAvailable ? "Daily report" : value(daily, "project_name");
        var draft = App.JSON.createObjectNode();
        var selected = draft.putArray("member_ids");
        collectIds(req, "member", selected);
        var addedRoles = draft.putObject("member_roles");
        for (var member : selected) submittedRole(req, addedRoles, member.asText());
        var editedRoles = App.JSON.createObjectNode();
        String picker = param(req, "picker");
        String departing = param(req, "departing_id");
        String replacement = param(req, "replacement").strip();
        String replacementRole = param(req, "replacement_role");
        String problem = "";
        var assigned = new HashSet<Long>();
        for (var member : daily.path("members")) assigned.add(member.path("id").asLong());
        var candidates = candidates(db, admin, assigned);
        var replacements = replacementChoices(candidates);
        if (post && picker.isEmpty()) {
            try {
                String action = param(req, "action");
                String version = param(req, "crew_revision");
                if (action.isEmpty() || action.equals("add")) {
                    Mainline.addMembers(db, user, dailyId, selected, addedRoles, version.isEmpty() ? null : id(version));
                } else if (action.equals("roles")) {
                    if (version.isEmpty()) throw new Problem(409, "Reopen the crew to update its roles.");
                    for (var member : daily.path("members")) submittedRole(req, editedRoles, value(member, "id"));
                    Mainline.updateCrewRoles(db, user, dailyId, editedRoles, id(version));
                } else if (action.equals("vehicles")) {
                    if (version.isEmpty()) throw new Problem(409, "Reopen the daily crew to update its vehicles.");
                    var vehicles = new HashSet<Long>();
                    for (String vehicle : params(req, "vehicle")) vehicles.add(id(vehicle));
                    Mainline.updateVehicles(db,user,dailyId,vehicles,id(version));
                } else if (action.equals("remove") || action.equals("replace")) {
                    if (version.isEmpty() || id(version) != daily.path("crew_revision").asLong())
                        throw new Problem(409, "The crew changed. Review the current crew and choose the member again.");
                    if (departing.isBlank()) throw new Problem(422, "Choose the current team member.");
                    Long incoming = replacements.get(replacement);
                    if (action.equals("replace") && incoming == null)
                        throw new Problem(422, "Choose a replacement from the contractor suggestions.");
                    if (action.equals("remove") && !replacement.isEmpty())
                        throw new Problem(422, "Choose Replace member to use the selected replacement, or clear the replacement field.");
                    Mainline.changeMember(db, user, dailyId, id(departing), incoming,
                        incoming == null ? null : req.getParameter("replacement_role"), id(version));
                } else throw new Problem(422, "Choose a crew action.");
                redirect(res, backPath + "?crew=1");
                return;
            } catch (Problem err) {
                if (err.status == 403 || err.status == 404) throw err;
                res.setStatus(err.status);
                problem = err.getMessage();
                if (err.status == 409) {
                    daily = Mainline.crewDaily(db, user, dailyId);
                    assigned.clear();
                    for (var member : daily.path("members")) assigned.add(member.path("id").asLong());
                    candidates = candidates(db, admin, assigned);
                    replacements = replacementChoices(candidates);
                    selected.removeAll();
                    editedRoles.removeAll();
                    departing = "";
                    replacement = "";
                    replacementRole = "";
                }
            }
        }

        String title = "Manage crew";
        var form = new StringBuilder("<div class='stack editor'>").append(alert(problem))
            .append("<section class='panel stack'><h2>Current crew (").append(daily.path("members").size()).append(")</h2><p>")
            .append(h(value(daily, "project_name"))).append(" · ").append(h(value(daily, "date"))).append("</p>")
            .append(currentRoleForm(daily, editedRoles)).append("</section>")
            .append(changeMemberForm(daily, replacements, departing, replacement, replacementRole));
        form.append("<section class='panel stack'><h2>Add team members</h2>");
        if (candidates.isEmpty()) form.append("<p>All contractors are on this daily crew.</p>");
        else form.append("<form class='stack' method='post'>").append(hidden("crew_revision", value(daily, "crew_revision")))
            .append(contractorPicker(candidates, draft, picker.equals("all") ? "" : param(req, "contractor_search"), admin))
            .append("<div class='actions'><button name='action' value='add'>Add to daily crew</button></div></form>");
        form.append("</section><section class='panel stack' id='vehicles'><h2>Vehicles</h2><form class='stack' method='post'>")
            .append(hidden("crew_revision",value(daily,"crew_revision")))
            .append(vehicleChoices(db,value(daily,"date"),daily.path("vehicles"),Set.of(),admin))
            .append("<div class='actions'><button name='action' value='vehicles'>Save vehicles</button></div></form></section></div>");
        String heading = admin ? MainlineViews.heading(title, backLabel, backPath, "") :
            MainlineViews.workerHeading(title, backLabel, backPath, "");
        page(res, user, title, heading + form);
    }

    private static String currentRoleForm(ObjectNode daily, ObjectNode edits) {
        if (daily.path("members").isEmpty()) return "<p>No team members assigned.</p>";
        var out = new StringBuilder("<form class='stack' method='post'>")
            .append(hidden("crew_revision", value(daily, "crew_revision")));
        for (var member : daily.path("members")) {
            String key = value(member, "id"), name = value(member, "name");
            String role = edits.has(key) ? value(edits, key) : value(member, "crew_role");
            out.append("<label class='crew-role-row'><span>").append(h(name));
            if (member.path("id").asLong() == daily.path("supervisor_person_id").asLong()) out.append(" · Project supervisor");
            out.append("</span>").append(roleSelect("role_" + key, name, role)).append("</label>");
        }
        return out.append("<div class='actions'><button name='action' value='roles'>Save crew roles</button></div></form>").toString();
    }

    private static Map<String, Long> replacementChoices(ArrayNode candidates) {
        var names = new HashSet<String>();
        var duplicates = new HashSet<String>();
        for (var person : candidates) {
            String name = AdminViews.searchText(value(person, "name"));
            if (!names.add(name)) duplicates.add(name);
        }
        var choices = new LinkedHashMap<String, Long>();
        for (var person : candidates) {
            String name = value(person, "name");
            long personId = person.path("id").asLong();
            String account = duplicates.contains(AdminViews.searchText(name)) ? "@" + value(person, "username") + ", " : "";
            choices.put(name + " (" + account + "#" + personId + ")", personId);
        }
        return choices;
    }

    private static String changeMemberForm(ObjectNode daily, Map<String, Long> replacements,
                                           String departing, String replacement, String replacementRole) {
        long supervisor = daily.path("supervisor_person_id").asLong();
        var options = new StringBuilder();
        for (var member : daily.path("members")) {
            if (member.path("id").asLong() == supervisor) continue;
            String key = value(member, "id");
            options.append(option(key, value(member, "name") + " (#" + key + ")", departing));
        }
        if (options.isEmpty()) return "";
        var form = new StringBuilder("<section class='panel stack'><h2>Remove or replace a member</h2>")
            .append("<form class='stack' method='post'>").append(hidden("crew_revision", value(daily, "crew_revision")))
            .append("<label>Current team member<select name='departing_id' required>")
            .append(option("", "Select member", departing)).append(options).append("</select></label>");
        if (!replacements.isEmpty()) {
            form.append(field("Replacement contractor", "replacement", replacement,
                "list='crew-replacements' maxlength='512' placeholder='Type a first or last name' autocomplete='off'"))
                .append("<datalist id='crew-replacements'>");
            for (String choice : replacements.keySet()) form.append("<option value='").append(h(choice)).append("'></option>");
            form.append("</datalist><label>Replacement role")
                .append(roleSelect("replacement_role", "replacement contractor", replacementRole)).append("</label>");
        }
        form.append("<div class='actions'>");
        if (!replacements.isEmpty()) form.append("<button name='action' value='replace'>Replace member</button>");
        return form.append("<button class='secondary' name='action' value='remove'>Remove member</button></div></form></section>").toString();
    }

    static void report(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       long dailyId, boolean post) throws Exception {
        var daily = Mainline.daily(db, user, dailyId);
        String path = "/mainline/dailies/" + dailyId;
        var report = new MainlineReportForm(req, daily, post);
        var draft = report.draft;
        String problem = "";
        if (post) {
            String action = param(req, "action");
            if (action.equals("save")) {
                try {
                    var files = MainlineReportForm.uploads(req);
                    MainlineMedia.saveReport(db, user, dailyId, report.changes(), files, report.removals(),
                        files.isEmpty() ? null : PhotoStorage.active());
                    redirect(res, path + "?saved=1");
                    return;
                } catch (Problem err) {
                    if (err.status == 403 || err.status == 404) throw err;
                    res.setStatus(err.status);
                    problem = err.getMessage();
                } catch (IOException err) {
                    res.setStatus(503);
                    problem = "The uploaded images could not be read.";
                }
                if (MainlineReportForm.multipart(req))
                    problem += " If you selected photos, choose them again before saving.";
            } else if (!action.equals("add-expense") && !action.matches("remove-expense-[0-9]+"))
                throw new Problem(422, "Choose a daily report action.");
        }
        long projectId = daily.path("project_id").asLong();
        boolean admin = user.path("is_admin").asBoolean();
        String projectName = value(daily,"project_name");
        String title = projectName + " · " + value(daily,"date");
        String projectPath = "/mainline/projects/" + projectId;
        String crewLink = "<a class='btn secondary' href='" + path + "/crew' target='_blank' rel='noopener'>Manage crew and vehicles (opens in a new tab)</a>";
        String heading = admin ? MainlineViews.heading(title, "Project", projectPath, crewLink) :
            MainlineViews.workerHeading(title, "Project", projectPath, crewLink);
        var form = new StringBuilder("<div class='stack'>").append(heading);
        if (param(req,"saved").equals("1") && !post) form.append(notice("Daily report saved."));
        if (param(req, "crew").equals("1") && !post) form.append(notice("Daily crew updated."));
        form.append("<form method='post' enctype='multipart/form-data' class='panel stack editor'>").append(alert(problem))
            .append(hidden("revision", value(draft,"revision"))).append("<h2>Daily report</h2>")
            .append(field("Total footage (ft)", "total_footage", value(draft,"total_footage"), "type='number' min='0' step='0.01'"));
        var activeDrivers = new StringBuilder();
        var previousDrivers = new StringBuilder();
        var roster = new StringBuilder();
        var formerRoster = new StringBuilder();
        int activeCount = 0;
        form.append(hidden("driver_baseline", "1"));
        if (draft.has("initial_driver_ids")) {
            for (var entry : draft.path("initial_driver_ids")) form.append(hidden("initial_driver", entry.asText()));
        } else {
            for (var member : daily.path("members"))
                if (member.path("is_driver").asBoolean() && !member.path("removed").asBoolean())
                    form.append(hidden("initial_driver", value(member,"id")));
        }
        var drivers = idSet(draft.has("driver_ids") ? draft.path("driver_ids") : daily.path("driver_ids"));
        for (var member : daily.path("members")) {
            String key = value(member,"id");
            String name = h(value(member,"name"));
            String role = value(member, "crew_role");
            boolean removed = member.path("removed").asBoolean();
            boolean former = member.path("former_supervisor").asBoolean();
            String status = removed ? " · Removed from crew" : former ? " · Former project supervisor" : "";
            var members = status.isEmpty() ? roster : formerRoster;
            members.append("<li><span>").append(name).append(status).append("</span><span>")
                .append(role.isEmpty() ? "Role unassigned" : h(role)).append("</span></li>");
            if (status.isEmpty()) activeCount++;
            if (removed) {
                if (member.path("is_driver").asBoolean()) previousDrivers.append("<p>").append(name).append(status).append("</p>");
                continue;
            }
            if (former && !member.path("is_driver").asBoolean()) continue;
            var list = former ? previousDrivers : activeDrivers;
            list.append("<label><input type='checkbox' name='driver' value='").append(h(key))
                .append(drivers.contains(key) ? "' checked>" : "'>").append(name).append(status).append("</label>");
        }
        form.append("<details open><summary>Team members (").append(activeCount).append(")</summary>")
            .append("<ul class='daily-team-members'>").append(roster).append("</ul>");
        if (!formerRoster.isEmpty()) form.append("<h3>Previously assigned</h3><ul class='daily-team-members'>")
            .append(formerRoster).append("</ul>");
        form.append("</details><details><summary>Driving credit</summary><div class='choices'>").append(activeDrivers).append("</div>");
        if (!previousDrivers.isEmpty()) form.append("<fieldset><legend>Previously recorded credit</legend>")
            .append(previousDrivers).append("</fieldset>");
        form.append("</details>").append(vehicleLine(daily));
        form.append(report.expenses())
            .append("<label>Priority<select name='priority'>").append(option("","Select priority",value(draft,"priority")));
        for (var priority : Mainline.PRIORITIES) form.append(option(priority,priority,value(draft,"priority")));
        form.append("</select></label><label>Comments<textarea name='comments' maxlength='500' rows='4'>")
            .append(h(value(draft,"comments"))).append("</textarea></label>")
            .append(MainlineViews.documents(db, user, projectId, false))
            .append(MainlineMedia.reportFields(db, user, dailyId, report.removedPhotos))
            .append("<div class='actions'><button name='action' value='save'>Save daily report</button></div></form></div>");
        page(res, user, title, form.toString());
    }
}
