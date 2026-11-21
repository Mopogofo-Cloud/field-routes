package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.util.List;
import static fieldops.Views.*;

final class MainlineViews {
    private MainlineViews() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       String path, boolean post) throws Exception {
        if (MainlineMedia.handle(req, res, db, user)) return;
        if (path.matches("/mainline/dailies/[0-9]+")) {
            MainlineDailyViews.report(req, res, db, user, id(path.substring(path.lastIndexOf('/') + 1)), post);
            return;
        }
        String[] parts = path.split("/");
        if (path.matches("/mainline/projects/[0-9]+/documents/(redline|fiber|locates)")) {
            document(req, res, db, user, id(parts[3]), parts[5], post);
            return;
        }
        if (!user.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        if (path.matches("/mainline/dailies/[0-9]+/crew")) {
            MainlineDailyViews.crew(req, res, db, user, id(parts[3]), post);
        } else if (path.equals("/mainline/projects/new") || path.matches("/mainline/projects/[0-9]+/edit")) {
            editor(req, res, db, user, parts[3].equals("new") ? null : id(parts[3]), post);
        } else if (path.matches("/mainline/projects/[0-9]+/dailies/new")) {
            MainlineDailyViews.assign(req, res, db, user, id(parts[3]), post);
        } else if (path.matches("/mainline/projects/[0-9]+/export")) {
            if (!post) throw new Problem(405, "Select dailies on the project page to export.");
            MainlineExport.download(req, res, db, user, id(parts[3]));
        } else if (post) {
            throw new Problem(405, "Use the project or daily editor to save changes.");
        } else if (path.equals("/mainline")) {
            String date = param(req, "date").isEmpty() ? value(user, "today") : StoreInput.date(param(req, "date"));
            ArrayNode days = Mainline.dailies(db, user, date, null);
            page(res, user, "Mainline", heading("Mainline", "Routes", "/routes", link("Projects", "/mainline/projects", "btn")) +
                "<form method='get' action='/mainline' class='row'>" + field("Daily reports for", "date", date, "type='date' required") +
                "<button class='secondary'>View date</button></form>" + (days.isEmpty() ? empty("No Mainline dailies for this date") : dailyCards(days)));
        } else if (path.equals("/mainline/projects")) {
            String query = param(req, "q").strip();
            String search = AdminViews.searchText(query);
            var list = new StringBuilder();
            for (var project : Mainline.projects(db)) {
                String name = value(project, "name"), location = value(project, "location");
                if (!AdminViews.searchText(name + " " + location).contains(search)) continue;
                list.append("<a class='panel route-section' href='").append(projectPath(project)).append("'><strong>")
                    .append(h(name)).append("</strong><span>").append(h(value(project, "state")))
                    .append(location.isBlank() ? "" : " · " + h(location)).append("</span></a>");
            }
            page(res, user, "Mainline projects", heading("Projects", "Mainline", "/mainline", link("Create project", "/mainline/projects/new", "btn")) +
                "<form method='get' class='row'>" + field("Find a project", "q", query, "type='search' maxlength='250'") +
                "<button class='secondary'>Search</button></form>" + (list.isEmpty() ? empty(query.isEmpty() ? "No projects yet" : "No matching projects") :
                "<div class='stack'>" + list + "</div>"));
        } else if (path.matches("/mainline/projects/[0-9]+")) {
            projectPage(res, db, user, id(parts[3]));
        } else throw new Problem(404, "Mainline page not found.");
    }

    static String projectPath(JsonNode p) { return "/mainline/projects/" + p.path("id").asLong(); }
    static String heading(String title, String back, String href, String actions) {
        return AdminViews.navForPath("/routes") + link("← " + back, href, "worker-back worker-text-button") +
            "<header class='section-head'><h1>" + h(title) + "</h1><div class='actions'>" + actions + "</div></header>";
    }
    static String empty(String message) { return "<p class='panel empty'>" + h(message) + "</p>"; }

    static String dailyCards(ArrayNode list) {
        StringBuilder out = new StringBuilder("<div class='stack'>");
        for (var day : list) {
            long id = day.path("id").asLong();
            out.append("<a class='panel route-section' href='/mainline/dailies/").append(id)
                .append("'><strong>").append(h(value(day, "project_name"))).append("</strong><span>")
                .append(h(value(day, "date"))).append(" · Daily #").append(id).append(" · ")
                .append(h(value(day, "total_footage"))).append(" ft</span>");
            var crew = new java.util.StringJoiner(", ");
            for (var member : day.path("members")) crew.add(value(member, "name"));
            if (crew.length() > 0) out.append("<span>").append(h(crew.toString())).append("</span>");
            out.append("</a>");
        }
        return out.append("</div>").toString();
    }

    private static void editor(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                               Long projectId, boolean post) throws Exception {
        boolean create = projectId == null;
        var data = App.JSON.createObjectNode().put("state", "Not Started");
        String problem = "";
        if (post) {
            for (var key : List.of("name", "location", "supervisor", "state", "estimated_footage",
                    "driver_cost", "price_per_foot", "due_date", "revision"))
                data.put(key, param(req, key));
            data.put("lunch_provided", "yes".equals(param(req, "lunch_provided")));
            try {
                var saved = Mainline.saveProject(db, user, projectId, data);
                String path = projectPath(saved);
                redirect(res, create && "crew".equals(param(req, "next")) ? path + "/dailies/new" : path);
                return;
            } catch (Problem err) {
                res.setStatus(err.status);
                problem = err.getMessage();
            }
        } else if (!create) data = Mainline.project(db, projectId);
        String title = create ? "Create project" : "Edit project";
        String back = create ? "/mainline/projects" : "/mainline/projects/" + projectId;
        String lunch = data.path("lunch_provided").asBoolean() ? "yes" : "no";
        var form = new StringBuilder("<form class='panel stack editor' method='post'>").append(alert(problem));
        if (!create) form.append(hidden("revision", value(data, "revision")));
        form.append(field("Project name", "name", value(data, "name"), "required maxlength='240'"))
            .append(field("Location of project", "location", value(data, "location"), "maxlength='500'"))
            .append(field("Supervisor name", "supervisor", value(data, "supervisor"),
                "list='supervisor-contractors' placeholder='Type a first or last name' autocomplete='off' maxlength='240'"))
            .append("<datalist id='supervisor-contractors'>");
        for (var contractor : Store.contractorNames(db))
            form.append("<option value='").append(h(value(contractor, "name"))).append("'></option>");
        form.append("</datalist><div class='two'><label>Project state<select name='state'>");
        for (var state : Mainline.STATES) form.append(option(state, state, value(data, "state")));
        form.append("</select></label><label>Lunch provided<select name='lunch_provided'>")
            .append(option("no", "No", lunch)).append(option("yes", "Yes", lunch)).append("</select></label></div>")
            .append("<div class='two'>").append(field("Estimated footage", "estimated_footage", value(data, "estimated_footage"), "type='number' min='0' step='0.01'"))
            .append(field("Due date", "due_date", value(data, "due_date"), "type='date'")).append("</div><div class='two'>")
            .append(field("Driver cost ($)", "driver_cost", value(data, "driver_cost"), "type='number' min='0' step='0.01'"))
            .append(field("Price per foot ($)", "price_per_foot", value(data, "price_per_foot"), "type='number' min='0' step='0.0001'"))
            .append("</div><div class='actions'>").append(link("Cancel", back, "btn secondary"));
        if (create) form.append("<button class='secondary'>Save project</button>")
            .append("<button name='next' value='crew'>Save and assign daily crew</button>");
        else form.append("<button>Save project</button>");
        form.append("</div></form>");
        page(res, user, title, heading(title, create ? "Projects" : "Project", back, "") + form);
    }

    private static void projectPage(HttpServletResponse res, Connection db, ObjectNode user, long id) throws Exception {
        var project = Mainline.project(db, id);
        String path = projectPath(project);
        var days = Mainline.dailies(db, user, null, id);
        String name = value(project, "name");
        var info = new StringBuilder(heading(name, "Projects", "/mainline/projects",
            link("Edit project", path + "/edit", "btn secondary") + link("Create daily", path + "/dailies/new", "btn")));
        info.append("<section class='panel stack'><h2>Project details</h2><dl class='project-facts'>");
        String[][] facts = {
            {"State", "state"}, {"Location", "location"}, {"Supervisor", "supervisor"}, {"Due date", "due_date"},
            {"Estimated footage (ft)", "estimated_footage"}, {"Footage reported (ft)", "total_footage"},
            {"Driver cost ($)", "driver_cost"}, {"Price per foot ($)", "price_per_foot"}
        };
        for (String[] fact : facts) {
            String display = value(project, fact[1]);
            if (!display.isBlank())
                info.append("<div><dt>").append(fact[0]).append("</dt><dd>").append(h(display)).append("</dd></div>");
        }
        info.append("<div><dt>Lunch provided</dt><dd>").append(project.path("lunch_provided").asBoolean() ? "Yes" : "No")
            .append("</dd></div></dl>");
        double estimate = project.path("estimated_footage").asDouble();
        if (estimate > 0) {
            double total = project.path("total_footage").asDouble();
            info.append("<label>Project progress · ").append(Math.round(100 * total / estimate)).append("%")
                .append("<progress max='").append(estimate).append("' value='").append(Math.min(total, estimate))
                .append("'></progress></label>");
        }
        info.append("</section><section class='stack'><h2>Dailies</h2>")
            .append(days.isEmpty() ? empty("No dailies yet") : dailyCards(days)).append("</section>")
            .append(documents(db, user, id, true));
        if (!days.isEmpty()) info.append(MainlineExport.form(path, days));
        page(res, user, name, "<div class='stack'>" + info + "</div>");
    }

    static String documents(Connection db, ObjectNode user, long projectId, boolean editable) throws Exception {
        var files = MainlineDocuments.list(db, user, projectId);
        if (files.isEmpty() && !editable) return "";
        var out = new StringBuilder("<section class='panel stack'><h2>Project documents</h2>");
        for (var kind : MainlineDocuments.KINDS) {
            var file = files.path(kind);
            boolean exists = file.hasNonNull("revision");
            if (!editable && !exists) continue;
            String path = "/mainline/projects/" + projectId + "/documents/" + kind;
            String label = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
            out.append("<div class='project-document'><strong>").append(label).append("</strong>");
            if (exists) out.append(link("Download PDF", path, "text-button"))
                .append("<small>").append(h(value(file, "updated_at"))).append("</small>");
            if (editable) out.append("<form method='post' enctype='multipart/form-data' action='").append(path).append("'>")
                .append(hidden("revision", exists ? value(file, "revision") : "0"))
                .append("<label>").append(exists ? "Replace " : "Upload ").append(label)
                .append(" PDF<input type='file' name='document' accept='application/pdf,.pdf' required></label>")
                .append("<button class='secondary'>Save PDF</button></form>");
            out.append("</div>");
        }
        return out.append("</section>").toString();
    }

    private static void document(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                                 long projectId, String kind, boolean post) throws Exception {
        if (post) {
            if (!user.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
            var file = req.getPart("document");
            if (file == null) throw new Problem(422, "Choose a PDF.");
            MainlineDocuments.upload(db, user, projectId, kind, id(param(req, "revision")), file);
            redirect(res, "/mainline/projects/" + projectId);
        } else {
            byte[] bytes = MainlineDocuments.download(db, user, projectId, kind);
            res.setContentType("application/pdf");
            res.setHeader("Content-Disposition", "attachment; filename=\"" + kind + ".pdf\"");
            res.setContentLength(bytes.length);
            if (!req.getMethod().equals("HEAD")) res.getOutputStream().write(bytes);
        }
    }
}
