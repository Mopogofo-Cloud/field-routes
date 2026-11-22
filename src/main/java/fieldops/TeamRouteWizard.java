package fieldops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static fieldops.Views.*;

/** The draft travels with the form. Only Confirm writes the team and its dated assignment. */
final class TeamRouteWizard {
    static final String PATH = "/routes/plan";
    private TeamRouteWizard() {}

    private enum Stage {
        CREW("Select team members"), ENTITY("Contracting entity"), STOPS("Route stops"), REVIEW("Review route");
        final String title;
        Stage(String title) { this.title = title; }
        static Stage read(String key) {
            for (Stage stage : values()) if (stage.name().equals(key)) return stage;
            throw new Problem(422, "This route step is invalid. Reopen Create team and route.");
        }
    }

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user,
                       String date, boolean post, String nav) throws Exception {
        Stage stage = post ? Stage.read(param(req, "stage")) : Stage.CREW;
        ObjectNode plan = post ? readPlan(req) : App.JSON.createObjectNode().put("date", StoreInput.date(date));
        ObjectNode entry = !post ? RouteEntryForm.blank() : stage == Stage.STOPS ? RouteEntryForm.read(req) : RouteEntryForm.restore(req);
        String problem = "", info = "";
        if (post) {
            String step = param(req, "step");
            try {
                switch (stage) {
                    case CREW -> {
                        readCrew(req, plan);
                        String picker = param(req, "picker");
                        if (step.equals("previous")) {
                            long sourceRoute = id(param(req, "previous_route"));
                            String day = StoreInput.date(value(plan, "date"));
                            if (find(PreviousCrews.list(db, day), "route_id", Long.toString(sourceRoute)) == null)
                                throw new Problem(422, "Select a previous crew for this date.");
                            ObjectNode previous = PreviousCrews.get(db, sourceRoute);
                            plan.set("source_team_id", previous.path("team_id"));
                            plan.put("source_name", value(previous, "name"));
                            for (String key : List.of("name","member_ids","entity_id","vehicle_ids")) plan.set(key, previous.path(key).deepCopy());
                            for (String key : List.of("crew_search","vehicle_search","entity_search")) plan.remove(key);
                            entry = RouteEntryForm.blank();
                            info = "Previous crew loaded.";
                        } else if (picker.equals("all")) plan.put("crew_search", "");
                        else if (step.equals("all-vehicles")) plan.put("vehicle_search", "");
                        else if (!picker.equals("search") && !step.equals("search-vehicles") && !step.equals("next"))
                            throw new Problem(422, "Choose a team action.");
                        String removed = unselectUnavailable(plan, vehicles(db, plan, value(user, "today")));
                        if (!removed.isEmpty()) {
                            info = (info.isEmpty() ? "" : info + " ") + "Unavailable for this date and removed from the selection: " + removed + ".";
                        } else if (step.equals("next")) {
                            checkCrew(plan);
                            stage = Stage.ENTITY;
                        }
                    }
                    case ENTITY -> {
                        plan.put("entity_id", param(req, "entity_id")).put("entity_search", param(req, "entity_search"));
                        if (step.equals("back")) stage = Stage.CREW;
                        else if (step.equals("next")) {
                            selectedEntity(db, plan);
                            stage = Stage.STOPS;
                        } else if (step.equals("all-entities")) plan.put("entity_search", "");
                        else if (!step.equals("search-entities")) throw new Problem(422, "Choose a contracting entity action.");
                    }
                    case STOPS -> {
                        if (step.equals("back")) stage = Stage.ENTITY;
                        else if (step.equals("review")) {
                            RouteEntryForm.stops(entry);
                            stage = Stage.REVIEW;
                        } else if (step.startsWith("entry-")) RouteEntryForm.edit(entry, step);
                        else throw new Problem(422, "Choose a route action.");
                    }
                    case REVIEW -> {
                        if (step.equals("back")) stage = Stage.STOPS;
                        else if (step.equals("confirm")) {
                            checkCrew(plan);
                            selectedEntity(db, plan);
                            ObjectNode team = App.JSON.createObjectNode();
                            for (String key : List.of("name","entity_id","member_ids","source_team_id"))
                                if (plan.has(key)) team.set(key, plan.get(key));
                            ObjectNode route = App.JSON.createObjectNode().put("date", value(plan, "date"))
                                .put("stop_label", value(entry, "stop_label")).put("urgent", entry.path("urgent").asBoolean());
                            route.set("addresses", RouteEntryForm.stops(entry));
                            route.set("vehicle_ids", plan.path("vehicle_ids"));
                            ObjectNode saved = Store.createTeamRoute(db, team, route);
                            redirect(res, teamRoutesPath(saved.path("team_id").asLong(), value(saved, "date")) +
                                "&saved=planned#route-" + saved.path("id").asLong());
                            return;
                        } else throw new Problem(422, "Choose a review action.");
                    }
                }
            } catch (Problem error) {
                res.setStatus(error.status);
                problem = error.getMessage();
            }
        }
        StringBuilder form = new StringBuilder("<form class='panel stack' method='post' action='").append(PATH).append("'>")
            .append(hidden("stage", stage.name())).append(hidden("plan", plan.toString()));
        if (stage != Stage.STOPS) form.append(RouteEntryForm.hiddenDraft(entry));
        form.append(alert(problem)).append(notice(info));
        switch (stage) {
            case CREW -> {
                String fallback = value(user, "today"), day = dateOr(value(plan, "date"), fallback);
                form.append(previousPicker(PreviousCrews.list(db, day)))
                    .append("<div class='two'>").append(field("Route date", "date", value(plan, "date"), "type='date' required"))
                    .append(field("Team name", "name", value(plan, "name"), "maxlength='120' required")).append("</div>")
                    .append(AdminViews.contractorPicker(Store.profiles(db), plan, value(plan, "crew_search")))
                    .append(vehiclePicker(vehicles(db, plan, fallback), plan));
            }
            case ENTITY -> form.append(entityPicker(Store.entities(db), plan));
            case STOPS -> form.append(RouteEntryForm.render(entry));
            case REVIEW -> form.append(review(db, plan, entry, value(user, "today")));
        }
        form.append("<div class='actions'>");
        if (stage == Stage.CREW) form.append(link("Cancel", "/routes?date=" + url(dateOr(value(plan, "date"), value(user, "today"))), "btn secondary"));
        else form.append("<button class='secondary' name='step' value='back' formnovalidate>Back</button>");
        String next = switch (stage) {
            case CREW, ENTITY -> "<button name='step' value='next'>Next</button>";
            case STOPS -> "<button name='step' value='review'>Review route</button>";
            case REVIEW -> "<button name='step' value='confirm'>Confirm and assign route</button>";
        };
        form.append(next).append("</div></form>");
        page(res, user, "Create team and route", nav + "<div class='route-wizard'><header class='section-head'><div><p class='eyebrow'>Create team and route · Step " +
            (stage.ordinal() + 1) + " of 4</p><h1>" + stage.title + "</h1></div></header>" + form + "</div>");
    }

    private static ObjectNode readPlan(HttpServletRequest req) {
        try {
            if (App.JSON.readTree(param(req, "plan")) instanceof ObjectNode plan) return plan;
        } catch (JsonProcessingException ignored) {}
        throw new Problem(422, "Reopen Create team and route to start a new draft.");
    }

    private static void readCrew(HttpServletRequest req, ObjectNode plan) {
        plan.put("date", param(req, "date")).put("name", param(req, "name"));
        plan.put("crew_search", param(req, "contractor_search")).put("vehicle_search", param(req, "vehicle_search"));
        ArrayNode members = plan.putArray("member_ids"), vehicles = plan.putArray("vehicle_ids");
        for (String member : params(req, "member")) members.add(id(member));
        for (String vehicle : params(req, "vehicle")) vehicles.add(id(vehicle));
    }

    private static void checkCrew(ObjectNode plan) {
        StoreInput.date(value(plan, "date"));
        if (value(plan, "name").isBlank()) throw new Problem(422, "Enter a name for this team.");
        StoreInput.text(plan, "name", 1, 120);
        if (plan.path("member_ids").isEmpty()) throw new Problem(422, "Select at least one subcontractor for this team.");
    }

    private static JsonNode find(ArrayNode rows, String key, String target) {
        for (JsonNode row : rows) if (value(row, key).equals(target)) return row;
        return null;
    }

    private static String dateOr(String date, String fallback) {
        try { return StoreInput.date(date); }
        catch (Problem ignored) { return fallback; }
    }

    private static ArrayNode vehicles(Connection db, ObjectNode plan, String today) throws Exception {
        long ownTeam = value(plan, "name").strip().equals(value(plan, "source_name").strip())
            ? plan.path("source_team_id").asLong(0) : 0;
        return Vehicles.list(db, dateOr(value(plan, "date"), today), ownTeam);
    }

    private static String unselectUnavailable(ObjectNode plan, ArrayNode vehicles) {
        List<String> removed = new ArrayList<>();
        ArrayNode keep = App.JSON.createArrayNode();
        for (JsonNode vehicleId : plan.path("vehicle_ids")) {
            JsonNode vehicle = find(vehicles, "id", vehicleId.asText());
            if (vehicle != null && vehicle.path("available").asBoolean()) keep.add(vehicleId);
            else removed.add(vehicle == null ? "Unavailable vehicle" : value(vehicle, "name"));
        }
        plan.set("vehicle_ids", keep);
        return String.join(", ", removed);
    }

    private static String previousPicker(ArrayNode previous) {
        if (previous.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<details><summary>Use a previous crew</summary><div class='contractor-search'>")
            .append("<label>Previous crew<select name='previous_route'><option value=''>Select crew</option>");
        for (JsonNode row : previous) out.append(option(value(row, "route_id"), value(row, "team_name") + " · " +
            value(row, "entity_name") + " · " + value(row, "date"), ""));
        return out.append("</select></label><button class='secondary' name='step' value='previous' formnovalidate>Use this crew</button></div></details>").toString();
    }

    private static String vehiclePicker(ArrayNode vehicles, ObjectNode plan) {
        StringBuilder out = new StringBuilder("<fieldset class='stack' id='vehicles'><legend>Vehicles (optional)</legend>");
        if (vehicles.isEmpty())
            return out.append("<p class='helper'>No vehicles registered.</p><a href='/vehicles/new' target='_blank' rel='noopener'>Register a vehicle</a></fieldset>").toString();
        String query = value(plan, "vehicle_search");
        out.append(search("Find vehicle", "vehicle_search", query, "Unit, record ID, brand, plate or VIN", "vehicles"));
        Set<String> selected = selected(plan.path("vehicle_ids"));
        int count = 0;
        out.append("<div class='choices'>");
        for (JsonNode vehicle : vehicles) {
            String id = value(vehicle, "id"), name = value(vehicle, "name"), brand = value(vehicle, "brand"), plate = value(vehicle, "plate");
            boolean checked = selected.contains(id), available = vehicle.path("available").asBoolean();
            if (!checked && !matches(query, name + " " + value(vehicle, "source_record_id") + " " + brand + " " + plate)) continue;
            count++;
            out.append("<label><input type='checkbox' name='vehicle' value='").append(h(id)).append("'")
                .append(checked && available ? " checked" : "").append(available ? "" : " disabled").append("><span>").append(h(name));
            if (!brand.isBlank()) out.append(" · ").append(h(brand));
            if (!plate.isBlank()) out.append(" · ").append(h(plate));
            if (!available) out.append(" · Unavailable");
            out.append(vehicleRecordId(vehicle)).append("</span></label>");
        }
        if (count == 0) out.append("<p class='helper'>No vehicles match this search.</p>");
        return out.append("</div></fieldset>").toString();
    }

    private static JsonNode selectedEntity(Connection db, ObjectNode plan) throws Exception {
        JsonNode entity = find(Store.entities(db), "id", value(plan, "entity_id"));
        if (entity == null) throw new Problem(422, "Select one contracting entity.");
        return entity;
    }

    private static String entityLabel(JsonNode row) {
        String external = value(row, "wgidig_id");
        return value(row, "name") + (external.isEmpty() ? "" : " · WG iDig ID " + external);
    }

    private static String entityPicker(ArrayNode entities, ObjectNode plan) {
        String query = value(plan, "entity_search"), id = value(plan, "entity_id");
        StringBuilder out = new StringBuilder(search("Find contracting entity", "entity_search", query, "Name or WG iDig ID", "entities"))
            .append("<fieldset class='entity-picker'><legend>Select one contracting entity</legend><div class='choices'>");
        int count = 0;
        for (JsonNode row : entities) {
            boolean checked = value(row, "id").equals(id);
            String label = entityLabel(row);
            if (!checked && !matches(query, label)) continue;
            count++;
            out.append("<label><input type='radio' name='entity_id' value='").append(h(value(row, "id"))).append("'")
                .append(checked ? " checked" : "").append("><span>").append(h(label)).append("</span></label>");
        }
        if (count == 0) out.append("<p class='helper'>No contracting entities match this search.</p>");
        return out.append("</div></fieldset>").toString();
    }

    private static String search(String label, String name, String value, String hint, String group) {
        String clear = value.isBlank() ? "" : "<button class='text-button' name='step' value='all-" + group + "' formnovalidate>Show all</button>";
        return "<div class='contractor-search'>" + field(label, name, value, "type='search' maxlength='250' placeholder='" + h(hint) + "'") +
            "<button class='secondary' name='step' value='search-" + group + "' formnovalidate>Search</button>" + clear + "</div>";
    }

    private static boolean matches(String query, String text) {
        String haystack = AdminViews.searchText(text);
        for (String term : AdminViews.searchText(query).strip().split("\\s+")) if (!haystack.contains(term)) return false;
        return true;
    }

    private static Set<String> selected(JsonNode ids) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode id : ids) values.add(id.asText());
        return values;
    }

    private static String names(ArrayNode list, JsonNode ids) {
        List<String> labels = new ArrayList<>();
        for (JsonNode id : ids) {
            JsonNode found = find(list, "id", id.asText());
            labels.add(found == null ? "Unavailable (#" + id.asText() + ")" : value(found, "name"));
        }
        return String.join(", ", labels);
    }

    private static String review(Connection db, ObjectNode plan, ObjectNode entry, String today) throws Exception {
        JsonNode entity = find(Store.entities(db), "id", value(plan, "entity_id"));
        StringBuilder out = new StringBuilder("<dl class='report-summary'>");
        detail(out, "Date", value(plan, "date"));
        detail(out, "Team", value(plan, "name"));
        detail(out, "Crew", names(Store.profiles(db), plan.path("member_ids")));
        detail(out, "Contracting entity", entity == null ? "Select an entity" : entityLabel(entity));
        if (!plan.path("vehicle_ids").isEmpty()) {
            var choices = vehicles(db, plan, today);
            var assigned = App.JSON.createArrayNode();
            for (var id : plan.path("vehicle_ids")) {
                var vehicle = find(choices, "id", id.asText());
                if (vehicle == null) assigned.addObject().put("name", "Unavailable vehicle");
                else assigned.add(vehicle);
            }
            out.append("<dt>Vehicles</dt><dd>").append(vehicleList(assigned)).append("</dd>");
        }
        ArrayNode stops = RouteEntryForm.stops(entry);
        String label = stopLabelTitle(value(entry, "stop_label"));
        if (!label.isEmpty()) detail(out, "Address label", label);
        if (entry.path("urgent").asBoolean()) detail(out, "Priority", "Urgent");
        out.append("</dl><section><h2>Route stops (").append(stops.size()).append(")</h2><ol class='plan-stops'>");
        for (JsonNode stop : stops)
            out.append("<li>").append(h(value(stop, "address"))).append(jobIdLine(stop)).append("</li>");
        return out.append("</ol></section>").toString();
    }

    private static void detail(StringBuilder out, String label, String val) {
        out.append("<dt>").append(h(label)).append("</dt><dd>").append(h(val)).append("</dd>");
    }
}
