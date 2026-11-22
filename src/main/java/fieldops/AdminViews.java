package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.text.NumberFormat;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import static fieldops.Views.*;

final class AdminViews {
    private AdminViews() {}

    private enum Section {
        ROUTES("routes", "Routes", "Daily routes", "Assign route"),
        PEOPLE("people", "People", "Subcontractors", "Add subcontractor"),
        ROLES("roles", "Roles", "Roles", "Add role"),
        ITEMS("items", "Items", "Report items", "Add item"),
        TEAMS("teams", "Teams", "Teams", "Create team"),
        VEHICLES("vehicles", "Vehicles", "Vehicles", "Add vehicle"),
        ENTITIES("entities", "Entities", "Contracting entities", "Add entity");
        final String path, tab, title, create;
        Section(String path, String tab, String title, String create) {
            this.path = "/" + path; this.tab = tab; this.title = title; this.create = create;
        }
    }

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       String path, boolean post) throws Exception {
        if (path.equals("/routes") && !post && param(req, "date").isEmpty() && param(req, "team").isEmpty() &&
                param(req, "saved").isEmpty()) {
            page(res, user, "Routes", nav(Section.ROUTES) +
                "<div class='section-head'><h1>Routes</h1></div><div class='route-sections'>" +
                "<a class='panel route-section' href='/routes/drops'><h2>Drops</h2><span>Daily routes by team and address</span></a>" +
                "<a class='panel route-section' href='/mainline'><h2>Mainline</h2><span>Projects and daily work reports</span></a></div>");
            return;
        }
        String[] pieces = (path.equals("/routes/drops") ? "routes" : path.substring(1)).split("/");
        Section section = Section.valueOf(pieces[0].toUpperCase(Locale.ROOT));
        String date = param(req, "date");
        if (date.isEmpty()) date = value(user, "today");
        if (path.equals(TeamRouteWizard.PATH)) {
            TeamRouteWizard.handle(req, res, db, user, date, post, nav(Section.ROUTES));
            return;
        }
        if (section == Section.ENTITIES && pieces.length == 3 && pieces[2].equals("payroll")) {
            PayrollViews.handle(req, res, db, user, id(pieces[1]), post, nav(Section.ENTITIES));
            return;
        }
        if (section == Section.ENTITIES && pieces.length == 3 && pieces[2].equals("rates")) {
            EntityRateCardEditor.handle(req, res, db, user, id(pieces[1]), post, nav(Section.ENTITIES));
            return;
        }
        if (section == Section.ROUTES && pieces.length == 3) {
            teamPage(req, res, db, user, date, id(pieces[2]), post);
            return;
        }
        if (pieces.length == 1) {
            if (post) throw new Problem(405, "Use the editor to save changes.");
            if (section == Section.ROUTES && !param(req, "team").isEmpty()) {
                // Previously shared links selected a team by query parameter.
                String target = teamRoutesPath(id(param(req, "team")), StoreInput.date(date));
                redirect(res, target + ("report".equals(param(req, "saved")) ? "&saved=report" : ""));
                return;
            }
            String body = section == Section.ROUTES ? routes(db, user, date) : directory(db, section);
            String createLink = section.path + "/new" + (section == Section.ROUTES ? "?date=" + url(date) : "");
            String feedback = switch (param(req, "saved")) {
                case "report" -> section == Section.ROUTES ? notice("Report saved.") : "";
                case "1" -> notice(switch (section) {
                    case ROUTES -> "Route assigned."; case PEOPLE -> "Subcontractor saved.";
                    case TEAMS -> "Team saved."; case ENTITIES -> "Contracting entity saved.";
                    case ROLES -> "Role saved.";
                    case ITEMS -> "Item saved.";
                    case VEHICLES -> "Vehicle saved.";
                });
                default -> "";
            };
            String actions = link(section.create, createLink, "btn");
            if (section == Section.ROUTES || section == Section.TEAMS)
                actions = "<div class='actions'>" + link(section.create, createLink, "btn secondary") +
                    link("Create team and route", TeamRouteWizard.PATH + "?date=" + url(date), "btn") + "</div>";
            page(res, user, section.title, nav(section) + "<div class='section-head'><div>" +
                "<div class='eyebrow'>" + (section == Section.ROUTES ? link("Routes", "/routes", "text-button") + " / Drops" : "Admin workspace") + "</div><h1>" + section.title + "</h1></div>" +
                actions + "</div><div class='stack'>" + feedback + body + "</div>");
            return;
        }
        Long record = pieces[1].equals("new") ? null : id(pieces[1]);
        if (record != null && section == Section.ENTITIES && !post) {
            entityPage(res, db, user, record);
            return;
        }
        if (record != null && section != Section.TEAMS && section != Section.PEOPLE && section != Section.VEHICLES)
            throw new Problem(404, "Editor not found.");
        ObjectNode data = App.JSON.createObjectNode();
        String problem = "";
        String picker = section == Section.TEAMS ? param(req, "picker") : "";
        boolean search = picker.equals("search") || picker.equals("all");
        if (post) data = fromForm(req, section);
        if (post && !search) {
            try {
                switch (section) {
                    case PEOPLE -> Store.saveProfile(db, data, record);
                    case TEAMS -> Store.saveTeam(db, data, record);
                    case ROLES -> Store.addRole(db, data);
                    case ITEMS -> ReportCatalog.addItem(db, data);
                    case VEHICLES -> Vehicles.save(db, data, record);
                    case ENTITIES -> Store.addEntity(db, data);
                    case ROUTES -> {
                        ArrayNode addresses = data.putArray("addresses");
                        for (RouteStops.Stop stop : stopEntries(req)) {
                            addresses.addObject().put("address", stop.address()).put("external_job_id", stop.externalJobId());
                        }
                        Store.createRoute(db, data);
                    }
                }
                redirect(res, section.path + "?saved=1" + (section == Section.ROUTES ? "&date=" + url(value(data, "date")) : ""));
                return;
            } catch (Problem e) {
                res.setStatus(e.status);
                problem = e.getMessage();
                if (section == Section.PEOPLE && record != null) {
                    ObjectNode stored = item(Store.profiles(db), record);
                    for (String key : List.of("wgidig_account_type", "wgidig_employee_id", "wgidig_availability"))
                        data.set(key, stored.get(key));
                }
            }
        } else if (!post && record != null) {
            data = item(switch (section) {
                case TEAMS -> Store.teams(db);
                case VEHICLES -> Vehicles.list(db);
                default -> Store.profiles(db);
            }, record);
        } else if (section == Section.ROUTES) data.put("date", date);
        String title = record == null ? section.create : switch (section) {
            case TEAMS -> "Edit team";
            case VEHICLES -> "Edit vehicle";
            default -> "Edit subcontractor";
        };
        String form = switch (section) {
            case PEOPLE -> profileForm(data, record == null, Store.roles(db));
            case ROLES -> field("Role name", "name", value(data, "name"), "required maxlength='120'");
            case VEHICLES -> field("Vehicle name or unit number", "name", value(data, "name"), "required maxlength='120'") +
                field("Brand", "brand", value(data, "brand"), "maxlength='120'") +
                field("Plate or VIN", "plate", value(data, "plate"), "maxlength='80'");
            case ITEMS -> field("Item name", "name", value(data, "name"), "required maxlength='250'") +
                field("Unit (optional)", "unit", value(data, "unit"), "maxlength='80'");
            case TEAMS -> teamForm(db, data, picker.equals("all") ? "" : param(req, "contractor_search"));
            case ENTITIES -> field("Entity name", "name", value(data, "name"), "required maxlength='120'");
            case ROUTES -> routeForm(db, data, param(req, "addresses"));
        };
        page(res, user, title, nav(section) + "<div class='section-head'><h1>" + title + "</h1></div>" +
            "<form method='post' class='stack panel editor' action='" + h(path) + "'>" + alert(problem) + form +
            "<div class='actions'>" + link("Cancel", section.path, "btn secondary") + "<button>" +
            (section == Section.ROUTES ? "Assign route" : "Save") + "</button></div></form>" +
            (section == Section.VEHICLES && record != null ? FleetImport.adminProfile(db,user,record) : ""));
    }

    static String payrollNav() { return navForPath("/payroll"); }

    private static String nav(Section current) {
        return navForPath(current.path);
    }

    static String navForPath(String currentPath) {
        StringBuilder out = new StringBuilder("<nav class='tabs' aria-label='Admin workspace'>");
        for (Section s : Section.values()) {
            out.append("<a class='btn' href='").append(s.path).append("'")
                .append(s.path.equals(currentPath) ? " aria-current='page'" : "").append(">").append(s.tab).append("</a>");
        }
        out.append("<a class='btn' href='/job-reports'").append(currentPath.equals("/job-reports") ? " aria-current='page'" : "")
            .append(">Job reports</a>");
        return out.append("<a class='btn' href='/payroll'").append(currentPath.equals("/payroll") ? " aria-current='page'" : "")
            .append(">Payroll</a></nav>").toString();
    }

    private static String routes(Connection db, ObjectNode user, String date) throws Exception {
        var byTeam = new LinkedHashMap<Long, List<JsonNode>>();
        for (JsonNode route : Store.dailyTeamSummaries(db, user, date)) {
            byTeam.computeIfAbsent(route.path("team_id").asLong(), key -> new ArrayList<>()).add(route);
        }
        StringBuilder out = new StringBuilder("<form method='get' action='/routes/drops' class='row'>");
        out.append(field("Route date", "date", date, "type='date' required"))
            .append("<button class='secondary'>View date</button></form>");
        if (byTeam.isEmpty()) out.append("<section class='panel empty'><h2>No routes assigned</h2>")
            .append("<p class='muted'>Assign a team and its addresses for this date.</p></section>");
        for (var team : byTeam.entrySet()) {
            out.append("<a class='panel route-team' href='").append(h(teamRoutesPath(team.getKey(), date)))
                .append("'><strong>").append(h(labels(team.getValue(), "team_name")))
                .append("</strong><span>").append(h(labels(team.getValue(), "entity_name"))).append("</span></a>");
        }
        return out.toString();
    }

    private static String labels(Iterable<JsonNode> items, String field) {
        var names = new LinkedHashSet<String>();
        for (JsonNode item : items) names.add(value(item, field));
        return String.join(" / ", names);
    }

    private static void editStops(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                                  String date, long teamId, String path) throws Exception {
        long routeId = id(param(req, "route_id"));
        long revision = id(param(req, "route_revision"));
        String action = param(req, "action");
        String label = param(req, "stop_label");
        boolean urgent = "1".equals(param(req, "urgent"));
        switch (action) {
            case "add" -> RouteStops.addEntries(db, user, teamId, date, routeId, revision, stopEntries(req), label, urgent);
            case "remove" -> RouteStops.remove(db, user, teamId, date, routeId, revision, id(param(req, "job_id")));
            case "classify" -> RouteStops.classify(db, user, teamId, date, routeId, revision, id(param(req, "job_id")),
                label, urgent, req.getParameter("external_job_id"));
            default -> throw new Problem(422, "Choose a route action.");
        }
        redirect(res, path + "&saved=" + action + "&route_id=" + routeId + "#route-" + routeId);
    }

    private static void teamPage(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                                 String date, long teamId, boolean post) throws Exception {
        String filter = param(req, "filter");
        if (filter.isEmpty()) filter = "all";
        if (!List.of("all", "urgent", "unlabeled").contains(filter) && !RouteStops.LABELS.contains(filter))
            throw new Problem(422, "Choose one of the available stop filters.");
        String path = teamRoutesPath(teamId, date) + (filter.equals("all") ? "" : "&filter=" + url(filter));
        String error = "";
        if (post) {
            try {
                editStops(req, res, db, user, date, teamId, path);
                return;
            } catch (Problem e) {
                if (e.status != 422 && e.status != 409) throw e;
                res.setStatus(e.status);
                error = e.getMessage();
            }
        }
        var routes = Store.teamRoutes(db, user, teamId, date);
        if (routes.isEmpty()) throw new Problem(404, "This team has no assigned routes on that date.");
        String team = labels(routes, "team_name"), entity = labels(routes, "entity_name");
        var out = new StringBuilder(nav(Section.ROUTES)).append("<section class='team-heading'>")
            .append(link("← Daily routes", "/routes?date=" + url(date), "worker-back worker-text-button"))
            .append("<h1>").append(h(team)).append("</h1><p>").append(h(entity))
            .append(" · <time datetime='").append(h(date)).append("'>").append(h(date)).append("</time></p></section>")
            .append("<div class='stack'>");
        String saved = param(req, "saved");
        if (!post && "planned".equals(saved)) out.append(notice("Route assigned."));
        if (!post && "report".equals(saved)) out.append(notice("Report saved."));
        out.append("<form class='row stop-filter' method='get' action='/routes/team/").append(teamId).append("'>")
            .append(hidden("date", date)).append("<label>Show stops<select name='filter'>")
            .append(option("all", "All stops", filter)).append(option("urgent", "Urgent", filter))
            .append(labelOptions(filter)).append(option("unlabeled", "Unlabeled", filter))
            .append("</select></label><button class='secondary'>Apply filter</button></form>");
        for (JsonNode route : routes) {
            String routeId = value(route, "id");
            boolean target = routeId.equals(param(req, "route_id"));
            List<String> crew = new ArrayList<>();
            for (JsonNode member : route.path("members")) crew.add(value(member, "name"));
            out.append("<section class='panel assigned-route' id='route-").append(routeId).append("'><h2>Route #").append(routeId).append("</h2>");
            if (!team.equals(value(route, "team_name")) || !entity.equals(value(route, "entity_name")))
                out.append("<p>").append(h(value(route, "team_name"))).append(" · ").append(h(value(route, "entity_name"))).append("</p>");
            out.append("<p class='muted'>Crew · ").append(h(String.join(", ", crew))).append("</p>");
            out.append(vehicleLine(route));
            if (target) {
                out.append(alert(error));
                if (!post && saved.equals("add")) out.append(notice("Stops added."));
                if (!post && saved.equals("remove")) out.append(notice("Stop removed."));
                if (!post && saved.equals("classify")) out.append(notice("Stop updated."));
            }
            renderStops(out, route, path, filter, target && post ? req : null, res.getStatus() == 409);
            out.append("</section>");
        }
        page(res, user, team, out.append("</div>").toString());
    }

    private static String labelOptions(String selected) {
        StringBuilder options = new StringBuilder();
        for (String label : RouteStops.LABELS) options.append(option(label, stopLabelTitle(label), selected));
        return options.toString();
    }

    static String stopSettings(String label, boolean urgent) {
        return "<div class='stop-settings'><label>Address label<select name='stop_label'>" + option("", "No label", label) +
            labelOptions(label) + "</select></label><label class='urgent-check'><input type='checkbox' name='urgent' value='1'" +
            (urgent ? " checked" : "") + ">Urgent</label></div>";
    }

    private static boolean matchesFilter(JsonNode job, String filter) {
        return switch (filter) {
            case "all" -> true;
            case "urgent" -> job.path("urgent").asBoolean();
            case "unlabeled" -> value(job, "stop_label").isEmpty();
            default -> filter.equals(value(job, "stop_label"));
        };
    }

    private static void renderStops(StringBuilder out, JsonNode route, String path, String filter,
                                    HttpServletRequest failedEdit, boolean stale) {
        String routeId = value(route, "id");
        String fields = hidden("route_id", routeId) + hidden("route_revision", value(route, "revision"));
        String action = h(path + "#route-" + routeId);
        boolean openAdd = failedEdit != null && param(failedEdit, "action").equals("add");
        out.append("<details class='add-stops'").append(openAdd ? " open" : "").append("><summary>Add addresses</summary>")
            .append("<form class='stack' method='post' action='").append(action).append("'>").append(fields)
            .append(addressInput("Addresses to add", openAdd ? param(failedEdit, "addresses") : "", "addresses-" + routeId))
            .append(stopSettings(openAdd ? param(failedEdit, "stop_label") : "", openAdd && "1".equals(param(failedEdit, "urgent"))))
            .append("<p class='helper'>The label and urgency apply to every address in this batch.</p>")
            .append("<div><button name='action' value='add'>Add stops</button></div></form></details>");
        int visible = 0;
        for (JsonNode job : route.path("jobs")) {
            if (!matchesFilter(job, filter)) continue;
            visible++;
            String jobId = value(job, "id");
            String stopFields = fields + hidden("job_id", jobId);
            boolean openEdit = failedEdit != null && param(failedEdit, "action").equals("classify") && jobId.equals(param(failedEdit, "job_id"));
            boolean showDraft = openEdit && !stale;
            String externalId = showDraft && failedEdit.getParameter("external_job_id") != null
                ? param(failedEdit, "external_job_id") : value(job, "external_job_id");
            String label = showDraft ? param(failedEdit, "stop_label") : value(job, "stop_label");
            boolean urgent = showDraft ? "1".equals(param(failedEdit, "urgent")) : job.path("urgent").asBoolean();
            out.append("<div class='route-stop").append(stopClasses(job)).append("'><span class='position'>").append(h(value(job, "position")))
                .append("</span><div class='addr'><strong id='address-").append(jobId).append("'>").append(mapAddress(value(job, "address")))
                .append("</strong>").append(jobIdLine(job)).append(stopBadges(job)).append("<details class='edit-stop-label'").append(openEdit ? " open" : "")
                .append("><summary>Edit stop details</summary><form class='stack' method='post' action='").append(action).append("'>")
                .append(stopFields).append(field("Job ID (optional)", "external_job_id", externalId, "maxlength='120'"))
                .append(stopSettings(label, urgent))
                .append("<div><button name='action' value='classify'>Save stop details</button></div></form></details></div>")
                .append("<div class='stop-actions'>").append(badge(job)).append(link("Open report", "/jobs/" + jobId, "btn secondary"))
                .append("<form method='post' action='").append(action).append("'>").append(stopFields)
                .append("<button class='remove-stop' name='action' value='remove' aria-describedby='address-").append(jobId)
                .append("'>Remove stop</button></form></div></div>");
        }
        if (visible == 0) out.append("<p class='muted'>").append(route.path("jobs").isEmpty() ? "No active stops." : "No active stops match this filter.").append("</p>");
        var removed = new ArrayList<JsonNode>();
        for (JsonNode item : route.path("removed_jobs")) if (matchesFilter(item, filter)) removed.add(item);
        if (!removed.isEmpty()) {
            out.append("<details class='removed-stops'><summary>Removed stops (").append(removed.size()).append(")</summary>");
            for (JsonNode stop : removed) {
                out.append("<div class='removed-stop").append(stopClasses(stop)).append("'>").append(mapAddress(value(stop, "address"))).append(jobIdLine(stop)).append(stopBadges(stop))
                    .append(link("Open report", "/jobs/" + stop.path("id").asLong(), "btn secondary")).append("</div>");
            }
            out.append("</details>");
        }
    }

    private static String addressInput(String caption, String raw, String id) {
        return hidden("address_format", "with-ids") + "<div><label for='" + id + "'>" + h(caption) +
            "</label><textarea name='addresses' id='" + id + "' aria-describedby='" + id + "-hint' required>" +
            h(raw) + "</textarea><p class='helper' id='" + id + "-hint'>Enter one address per line. " +
            "For an optional Job ID, enter <strong>123 Main St | 000785</strong> or paste two spreadsheet columns in address, Job ID order.</p></div>";
    }

    private static List<RouteStops.Stop> stopEntries(HttpServletRequest req) {
        boolean withIds = "with-ids".equals(param(req, "address_format"));
        List<RouteStops.Stop> stops = new ArrayList<>();
        int number = 0;
        for (String line : param(req, "addresses").lines().toList()) {
            number++;
            if (line.isBlank()) continue;
            String[] cells = {line};
            if (withIds) {
                cells = line.contains("\t") ? line.split("\t", -1) : line.split("(?:^| +)\\|(?: +|$)", 2);
            }
            if (cells.length > 2) throw new Problem(422, "Line " + number + " has extra columns. Paste the address and its optional Job ID.");
            String address = cells[0].strip();
            String externalId = cells.length == 2 ? cells[1].strip() : "";
            if (address.isEmpty() || address.codePointCount(0, address.length()) > 500)
                throw new Problem(422, "Enter an address of up to 500 characters on line " + number + ".");
            if (externalId.codePointCount(0, externalId.length()) > 120)
                throw new Problem(422, "The Job ID on line " + number + " exceeds 120 characters.");
            stops.add(new RouteStops.Stop(address, externalId));
        }
        return stops;
    }

    private static void entityPage(HttpServletResponse res, Connection db, ObjectNode user, long entityId) throws Exception {
        var entity = item(Store.entities(db), entityId);
        String name = value(entity, "name");
        StringBuilder body = new StringBuilder(nav(Section.ENTITIES)).append(link("← Contracting entities", "/entities", "worker-back"))
            .append("<h1>").append(h(name)).append("</h1>");
        if (entity.hasNonNull("wgidig_id")) body.append("<p>WG iDig ID · ").append(entity.path("wgidig_id").asLong()).append("</p>");
        var payroll = EntityPayroll.load(db, entityId);
        ObjectNode cards = (ObjectNode)payroll.get("rate_cards");
        body.append("<div class='actions'>").append(link("Edit rate card and labor rates", "/entities/" + entityId + "/rates", "btn"))
            .append("</div>");
        if (cards != null) body.append(EntityRateCardView.render(cards));
        int rates = payroll.path("rates").size() + EntityRateFields.amounts(cards).size(), formulas = payroll.path("formulas").size();
        body.append("<section class='panel stack'><h2>Payroll settings</h2><p>")
            .append(rates).append(rates == 1 ? " pay rate" : " pay rates").append(" · ")
            .append(formulas).append(formulas == 1 ? " formula" : " formulas").append("</p><div>")
            .append(link("Edit pay rates and formulas", "/entities/" + entityId + "/payroll", "btn"))
            .append("</div></section>");
        var teams = StoreSql.rows(db, "SELECT id,name FROM teams WHERE entity_id=? ORDER BY lower(name)", entityId);
        body.append("<section class='panel stack'><h2>Teams</h2>");
        if (teams.isEmpty()) body.append("<p>No teams assigned.</p>");
        for (var team : teams) body.append(link(value(team, "name"), "/teams/" + team.path("id").asLong(), "worker-back"));
        page(res, user, name, body.append("</section>").toString());
    }

    private static String directory(Connection db, Section section) throws Exception {
        ArrayNode rows = switch (section) {
            case PEOPLE -> Store.profiles(db); case TEAMS -> Store.teams(db); case ENTITIES -> Store.entities(db);
            case ROLES -> Store.roles(db);
            case ITEMS -> ReportCatalog.itemRows(db);
            case VEHICLES -> Vehicles.list(db);
            default -> throw new IllegalArgumentException();
        };
        if (rows.isEmpty()) return "<section class='panel empty'><h2>No " + section.title.toLowerCase(Locale.ROOT) +
            " yet</h2>" + link(section.create, section.path + "/new", "btn secondary") + "</section>";
        ArrayNode related = section == Section.TEAMS ? Store.profiles(db) : section == Section.ENTITIES ? Store.teams(db) : null;
        StringBuilder out = new StringBuilder("<section class='panel'>");
        for (JsonNode row : rows) {
            if (section == Section.ROLES || section == Section.ITEMS) {
                out.append("<div class='list-row'><h3>").append(h(value(row, "name"))).append("</h3>");
                if (section == Section.ITEMS && !value(row, "unit").isBlank())
                    out.append("<span>").append(h(value(row, "unit"))).append("</span>");
                out.append("</div>");
                continue;
            }
            out.append("<div class='list-row'><div><h3>").append(h(value(row, "name"))).append("</h3><p class='muted'>");
            if (section == Section.PEOPLE) {
                String role = value(row, "role");
                String rate = row.path("pay_rate").isNumber() ?
                    NumberFormat.getCurrencyInstance(Locale.US).format(row.path("pay_rate").decimalValue()) : "Rate unset";
                out.append(role.isBlank() ? "Role unassigned" : h(role)).append(" · ")
                    .append(value(row, "pay_basis").equals("daily") ? "Daily" : "Production").append(" · ").append(rate);
                String[][] details = {{"email", "Email"}, {"phone", "Phone"}, {"address", "Address"},
                    {"start_date", "Start date"}, {"status", "Status"}, {"username", "Username"},
                    {"wgidig_account_type", "WG iDig account type"}, {"wgidig_employee_id", "WG iDig employee ID"},
                    {"wgidig_availability", "WG iDig availability at import"}};
                for (String[] d : details) {
                    String v = value(row, d[0]);
                    if (!v.isBlank()) out.append("<br>").append(d[1]).append(" · ").append(d[0].equals("address") ? mapAddress(v) : h(v));
                }
            } else if (section == Section.VEHICLES) {
                String details = (value(row, "brand") + " " + value(row, "plate")).strip();
                if (!details.isEmpty()) out.append(h(details)).append(" · ");
                out.append(row.path("available").asBoolean() ? "Available today" : "Assigned today");
            } else if (section == Section.TEAMS) {
                out.append(h(value(row, "entity_name"))).append("<br>");
                List<String> names = new ArrayList<>();
                for (JsonNode workerId : row.path("member_ids")) names.add(h(value(item(related, workerId.asLong()), "name")));
                out.append(String.join(", ", names));
            } else {
                long count = 0;
                if (row.hasNonNull("wgidig_id")) out.append("WG iDig ID ").append(row.path("wgidig_id").asLong()).append(" · ");
                for (JsonNode team : related) if (value(team, "entity_id").equals(value(row, "id"))) count++;
                out.append(count).append(count == 1 ? " team" : " teams");
            }
            out.append("</p></div>");
            out.append(link(section == Section.ENTITIES ? "Open" : "Edit", section.path + "/" + row.path("id").asLong(), "btn secondary"));
            out.append("</div>");
        }
        return out.append("</section>").toString();
    }

    private static ObjectNode fromForm(HttpServletRequest req, Section section) {
        ObjectNode data = App.JSON.createObjectNode();
        if (section != Section.ROUTES) data.put("name", param(req, "name"));
        switch (section) {
            case ITEMS -> data.put("unit", param(req, "unit"));
            case VEHICLES -> data.put("brand", param(req, "brand")).put("plate", param(req, "plate"));
            case PEOPLE -> {
                for (String key : List.of("username", "role", "pay_basis")) data.put(key, param(req, key));
                for (String key : List.of("email","phone","address","start_date","status","pay_rate")) {
                    String val = param(req, key).strip();
                    if (val.isEmpty()) data.putNull(key);
                    else data.put(key, val);
                }
                String password = param(req, "password");
                if (!password.isEmpty()) data.put("password", password);
            }
            case TEAMS -> {
                data.put("entity_id", param(req, "entity_id"));
                ArrayNode ids = data.putArray("member_ids");
                params(req, "member").forEach(ids::add);
            }
            case ROUTES -> {
                data.put("date", param(req, "date")); data.put("team_id", param(req, "team_id"));
                data.put("stop_label", param(req, "stop_label")); data.put("urgent", "1".equals(param(req, "urgent")));
            }
            default -> {}
        }
        return data;
    }

    private static String profileForm(ObjectNode d, boolean create, ArrayNode roles) {
        String date = value(d, "start_date");
        StringBuilder source = new StringBuilder();
        if (d.hasNonNull("wgidig_account_type")) source.append("<p>WG iDig account type · ")
                .append(h(value(d, "wgidig_account_type"))).append("</p>");
        if (d.hasNonNull("wgidig_employee_id")) source.append("<p>WG iDig employee ID · ")
                .append(h(value(d, "wgidig_employee_id"))).append("</p>");
        if (d.hasNonNull("wgidig_availability")) source.append("<p>WG iDig availability at import · ")
                .append(h(value(d, "wgidig_availability"))).append("</p>");
        StringBuilder roleOptions = new StringBuilder("<datalist id='role-options'>");
        for (JsonNode role : roles) roleOptions.append("<option value='").append(h(value(role, "name"))).append("'></option>");
        roleOptions.append("</datalist>");
        return field("Full name", "name", value(d, "name"), "required maxlength='120'") +
            field("Role", "role", value(d, "role"), "list='role-options' maxlength='120' " + (create ? "required" : "placeholder='Unassigned'")) +
            roleOptions + source.toString() +
            "<div class='two'>" + field("Email", "email", value(d, "email"), "inputmode='email' maxlength='500'") +
            field("Phone", "phone", value(d, "phone"), "type='tel' maxlength='500'") + "</div>" +
            "<div><label for='address'>Address</label><textarea id='address' name='address' maxlength='500'>" + h(value(d, "address")) + "</textarea></div>" +
            "<div class='two'>" + field("Start date", "start_date", date, date.isEmpty() || date.matches("\\d{4}-\\d{2}-\\d{2}") ? "type='date'" : "") +
            field("Status", "status", value(d, "status"), "maxlength='500'") + "</div>" +
            "<div class='two'><label>Pay basis<select name='pay_basis'>" + option("daily", "Daily", value(d, "pay_basis")) +
            option("production", "Production", value(d, "pay_basis")) + "</select></label>" +
            field("Pay rate ($)", "pay_rate", value(d, "pay_rate"), "type='number' min='0' max='10000000' step='0.01' placeholder='Unset'") + "</div>" +
            field("Sign-in username", "username", value(d, "username"), "required maxlength='150' autocapitalize='none' spellcheck='false' autocomplete='off'") +
            field(create ? "Sign-in password" : "New password (optional)", "password", "",
                "type='password' minlength='" + Auth.MIN_PASSWORD_LENGTH + "' maxlength='256' autocomplete='new-password'" + (create ? " required" : "")) +
            "<p class='helper'>Use at least " + Auth.MIN_PASSWORD_LENGTH + " characters. Share the worker’s sign-in details privately.</p>";
    }

    static String searchText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static String teamForm(Connection db, ObjectNode d, String query) throws Exception {
        ArrayNode entities = Store.entities(db);
        StringBuilder out = new StringBuilder(field("Team name", "name", value(d, "name"), "required maxlength='120'"));
        out.append("<label>Contracting entity<select name='entity_id' required>").append(option("", "Select entity", value(d, "entity_id")));
        for (JsonNode entity : entities) out.append(option(value(entity, "id"), value(entity, "name"), value(d, "entity_id")));
        out.append("</select></label>");
        if (entities.isEmpty()) out.append(link("Add a contracting entity", "/entities/new", "text-button"));
        return out.append(contractorPicker(Store.profiles(db), d, query)).toString();
    }

    static String contractorPicker(ArrayNode people, ObjectNode d, String query) {
        return contractorPicker(people, d, query, true);
    }

    static String contractorPicker(ArrayNode people, ObjectNode d, String query, boolean admin) {
        return contractorPicker(people, d, query, admin, null);
    }

    static String contractorPicker(ArrayNode people, ObjectNode d, String query, boolean admin,
                                   java.util.function.BiFunction<String,String,String> memberControl) {
        if (query.length() > 250) throw new Problem(422, "Enter a shorter contractor name.");
        query = query.strip();
        StringBuilder out = new StringBuilder("<fieldset id='contractors' class='stack'><legend>Subcontractors</legend>");
        if (people.isEmpty())
            return out.append(admin ? link("Add a subcontractor", "/people/new", "text-button") :
                "<p>No additional contractors available.</p>").append("</fieldset>").toString();
        out.append("<div class='contractor-search'>")
            .append(field("Search contractors", "contractor_search", query,
                "type='search' list='contractor-names' placeholder='First or last name' maxlength='250' autocomplete='off' enterkeyhint='search'"))
            .append("<button type='submit' class='secondary' name='picker' value='search' formaction='#contractors' formnovalidate>Search</button>");
        if (!query.isEmpty())
            out.append("<button type='submit' class='text-button' name='picker' value='all' formaction='#contractors' formnovalidate>Show all</button>");
        var suggestions = new StringBuilder("<datalist id='contractor-names'>");
        var selected = new LinkedHashSet<String>();
        for (JsonNode member : d.path("member_ids")) selected.add(member.asText());
        var crew = new StringBuilder();
        var matches = new StringBuilder();
        var terms = searchText(query).split("\\s+");
        var duplicateNames = new LinkedHashSet<String>();
        if (!admin) {
            var seen = new LinkedHashSet<String>();
            for (JsonNode person : people) {
                String name = searchText(value(person, "name"));
                if (!seen.add(name)) duplicateNames.add(name);
            }
        }
        for (JsonNode person : people) {
            String key = value(person, "id"), name = value(person, "name"), role = value(person, "role");
            String username = value(person, "username");
            boolean onTeam = selected.contains(key);
            suggestions.append("<option value='").append(h(name)).append("'></option>");
            boolean showUsername = admin || duplicateNames.contains(searchText(name));
            String searchable = searchText(showUsername ? name + " " + username : name);
            if (!onTeam && !Arrays.stream(terms).allMatch(searchable::contains)) continue;
            var group = onTeam ? crew : matches;
            if (memberControl != null) group.append("<div class='crew-role-row'>");
            group.append("<label><input type='checkbox' name='member' value='").append(h(key))
                .append(onTeam ? "' checked>" : "'>").append("<span>").append(h(name));
            if (showUsername) {
                group.append(" <span class='helper'>· ").append(h(username));
                if (admin && memberControl == null) group.append(" · ").append(role.isBlank() ? "Role unassigned" : h(role));
                group.append("</span>");
            }
            group.append("</span></label>");
            if (memberControl != null) group.append(memberControl.apply(key, name)).append("</div>");
        }
        out.append(suggestions).append("</datalist></div>");
        if (!crew.isEmpty()) out.append("<div><h3>Selected contractors</h3><div class='choices'>").append(crew).append("</div></div>");
        if (!matches.isEmpty()) out.append("<div><h3>").append(query.isEmpty() ? "Available contractors" : "Search results")
            .append("</h3><div class='choices'>").append(matches).append("</div></div>");
        else if (!query.isEmpty()) out.append("<p class='muted'>No additional contractors match this search.</p>");
        return out.append("</fieldset>").toString();
    }

    private static String routeForm(Connection db, ObjectNode d, String rawAddresses) throws Exception {
        ArrayNode teams = Store.teams(db);
        StringBuilder out = new StringBuilder(field("Route date", "date", value(d, "date"), "type='date' required"));
        out.append("<label>Team<select name='team_id' required>").append(option("", "Select team", ""));
        for (JsonNode team : teams) out.append(option(value(team, "id"), value(team, "name") + " · " + value(team, "entity_name"), value(d, "team_id")));
        out.append("</select></label>");
        if (teams.isEmpty()) out.append(link("Create a team", "/teams/new", "text-button"));
        return out.append(addressInput("Job addresses", rawAddresses, "addresses")).append(stopSettings(value(d, "stop_label"), d.path("urgent").asBoolean()))
            .append("<p class='helper'>The label and urgency apply to every address in this batch. Stops appear in the order entered.</p>").toString();
    }
}

