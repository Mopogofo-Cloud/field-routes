package fieldops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import static fieldops.Views.*;

final class ReportForm {
    static final List<String> FIELDS = List.of("revision", "feet_buried", "fiber_type", "tech_needed",
        "bores", "conduits", "items", "comments", "outcome", "outcome_reason");
    private static final List<String> NEW_FIELDS = List.of("tech_needed", "conduits", "items", "comments", "outcome", "outcome_reason");
    private static final String OTHER = "__other__";
    private static final int MAX_ROWS = 500;
    private static final String NUMBER = "type='number' inputmode='decimal' min='0' max='10000000' step='0.01'";
    private static final List<String> BORE = List.of("driveway", "sidewalk", "road");
    private static final List<String> CONDUIT = List.of("driveway", "sidewalk", "road", "existing");
    private ReportForm() {}

    static ObjectNode readDraft(HttpServletRequest req, JsonNode job) {
        String raw = param(req, "draft");
        if (raw.length() > 1_048_576) throw new Problem(413, "Report draft is too large.");
        try {
            if (App.JSON.readTree(raw) instanceof ObjectNode draft) {
                // A photo review left open before the extended report form was available.
                restoreLegacyFields(draft, job);
                return draft;
            }
        } catch (JsonProcessingException ignored) {}
        throw new Problem(422, "Invalid report draft. Reopen the job to continue.");
    }

    private static void restoreLegacyFields(ObjectNode draft, JsonNode job) {
        for (String key : NEW_FIELDS) if (!draft.has(key)) draft.set(key, job.path(key).deepCopy());
        if (!draft.hasNonNull("outcome")) draft.put("outcome", "done");
    }

    static void readPhotoSelection(HttpServletRequest req, ObjectNode draft) {
        ArrayNode kept = draft.putArray("keep_photo_ids");
        List<String> ids = params(req, "keep");
        if (ids.size() > 10) throw new Problem(422, "Each job can have up to 10 photos.");
        for (String key : ids) kept.add(id(key));
    }

    static ObjectNode read(HttpServletRequest req, JsonNode job) {
        ObjectNode draft = App.JSON.createObjectNode();
        if ("1".equals(param(req, "photo_selection"))) readPhotoSelection(req, draft);
        draft.put("revision", param(req, "revision")).put("feet_buried", param(req, "feet_buried"));
        draft.set("bores", readRuns(req, "bore"));
        if (!"extended".equals(param(req, "report_fields"))) {
            draft.put("fiber_type", param(req, "fiber_type"));
            restoreLegacyFields(draft, job);
            return draft;
        }
        draft.put("outcome", param(req, "outcome")).put("outcome_reason", param(req, "outcome_reason"));
        String fiber = param(req, "fiber_type");
        if (OTHER.equals(fiber)) {
            fiber = param(req, "other_fiber");
            if (fiber.isEmpty()) draft.put("empty_other_fiber", true);
        }
        draft.put("fiber_type", fiber);
        String tech = param(req, "tech_needed");
        switch (tech) {
            case "" -> draft.putNull("tech_needed");
            case "yes", "no" -> draft.put("tech_needed", tech.equals("yes"));
            default -> throw new Problem(422, "Choose Yes or No for Tech needed.");
        }
        draft.put("comments", param(req, "comments")).set("conduits", readRuns(req, "conduit"));
        List<String> choices = params(req, "item_choice"), names = params(req, "item_name");
        List<String> units = params(req, "item_unit"), quantities = params(req, "item_quantity");
        if (choices.size() > MAX_ROWS || choices.size() != names.size() ||
                choices.size() != units.size() || choices.size() != quantities.size())
            throw new Problem(422, "Invalid item rows. Reopen the report and try again.");
        ArrayNode items = draft.putArray("items");
        for (int i = 0; i < choices.size(); i++) {
            ObjectNode row = items.addObject().put("quantity", quantities.get(i));
            String choice = choices.get(i);
            if (OTHER.equals(choice)) {
                row.put("item", names.get(i)).put("unit", units.get(i));
                if (names.get(i).isEmpty() && units.get(i).isEmpty()) row.put("empty_other_item", true);
            } else if (choice.isEmpty()) {
                row.put("item", "").put("unit", "");
            } else {
                try {
                    JsonNode pair = App.JSON.readTree(choice);
                    if (pair == null || !pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).isTextual())
                        throw new Problem(422, "Invalid item selection.");
                    String unit = pair.get(1).textValue();
                    row.put("item", pair.get(0).textValue()).put("unit", unit.isEmpty() ? units.get(i) : unit);
                } catch (JsonProcessingException error) { throw new Problem(422, "Invalid item selection."); }
            }
        }
        return draft;
    }

    private static ArrayNode readRuns(HttpServletRequest req, String group) {
        List<String> types = params(req, group + "_type"), feet = params(req, group + "_feet");
        if (types.size() != feet.size() || types.size() > MAX_ROWS) throw new Problem(422, "Invalid " + group + " rows.");
        ArrayNode runs = App.JSON.createArrayNode();
        for (int i = 0; i < types.size(); i++) runs.addObject().put("type", types.get(i)).put("feet", feet.get(i));
        return runs;
    }

    static void changeRows(ObjectNode draft, String step) {
        // Accept the original bore form's add/remove button values as well.
        if (step.equals("add")) step = "add-bore";
        if (step.matches("remove-[0-9]+")) step = step.replace("remove-", "remove-bore-");
        for (String group : List.of("bore", "conduit", "item")) {
            ArrayNode rows = draft.withArray(group + "s");
            if (step.equals("add-" + group)) {
                if (rows.size() >= MAX_ROWS) throw new Problem(422, "Each report can have up to " + MAX_ROWS + " " + group + "s.");
                rows.addObject();
                return;
            }
            String prefix = "remove-" + group + "-";
            if (step.startsWith(prefix)) {
                long index = id(step.substring(prefix.length()));
                if (index < 0 || index >= rows.size()) throw new Problem(422, "Choose a row on this report.");
                rows.remove((int)index);
                return;
            }
        }
        throw new Problem(422, "Unknown report action.");
    }

    static void validate(ObjectNode draft) {
        String outcome = value(draft, "outcome");
        boolean done = outcome.equals("done");
        if (!done && !List.of("pending", "cancel").contains(outcome)) throw new Problem(422, "Choose a job outcome.");
        if (!done && value(draft, "outcome_reason").isBlank()) throw new Problem(422, "Enter a reason for this job outcome.");
        if (!done) StoreInput.text(draft, "outcome_reason", 1, 4000);
        if (done || !value(draft, "feet_buried").isBlank()) number(draft, "feet_buried", "Feet buried", false);
        if (done && value(draft, "fiber_type").isBlank()) throw new Problem(422, "Choose or enter a cable / fiber type.");
        if (!value(draft, "fiber_type").isBlank()) StoreInput.text(draft, "fiber_type", 1, 120);
        for (String group : List.of("bore", "conduit")) {
            List<String> allowed = group.equals("bore") ? BORE : CONDUIT;
            int index = 0;
            for (JsonNode row : draft.path(group + "s")) {
                String label = title(group) + " " + ++index;
                if (!allowed.contains(value(row, "type"))) throw new Problem(422, "Choose the type for " + label + " or remove the row.");
                number((ObjectNode) row, "feet", label + " feet", false);
            }
        }
        int index = 0;
        for (JsonNode row : draft.path("items")) {
            String label = "Item " + ++index;
            if (value(row, "item").isBlank()) throw new Problem(422, "Choose or enter a name for " + label + " or remove the row.");
            StoreInput.text((ObjectNode)row, "item", 1, 250);
            StoreInput.text((ObjectNode)row, "unit", 0, 80);
            number((ObjectNode)row, "quantity", label + " quantity", true);
        }
        StoreInput.text(draft, "comments", 0, 4000);
        if (done) draft.putNull("outcome_reason");
        if (value(draft, "fiber_type").isBlank()) draft.putNull("fiber_type");
        if (value(draft, "feet_buried").isBlank()) draft.putNull("feet_buried");
        draft.remove("empty_other_fiber");
        for (JsonNode row : draft.path("items")) ((ObjectNode)row).remove("empty_other_item");
    }

    private static void number(ObjectNode row, String field, String label, boolean positive) {
        try {
            var number = StoreInput.decimal(row, field);
            if (positive && number.signum() == 0) throw StoreInput.invalid(field);
        } catch (Problem error) {
            throw new Problem(422, label + " must be between " + (positive ? "0.01" : "0") +
                " and 10,000,000, with up to two decimal places.");
        }
    }

    static String render(String path, ObjectNode draft, List<String> fibers, List<ReportCatalog.Item> catalog) {
        String fiber = value(draft, "fiber_type");
        boolean custom = draft.path("empty_other_fiber").asBoolean() || (!fiber.isEmpty() && !fibers.contains(fiber));
        String selected = custom ? OTHER : fiber;
        String tech = tech(draft);
        String outcome = draft.hasNonNull("outcome") ? value(draft, "outcome") : "done";
        StringBuilder out = new StringBuilder("<form method='post' class='worker-form panel' action='").append(h(path)).append("'>")
            .append(hidden("report_fields", "extended")).append(hidden("revision", value(draft, "revision")));
        if (draft.has("keep_photo_ids")) {
            out.append(hidden("photo_selection", "1"));
            for (JsonNode photoId : draft.path("keep_photo_ids")) out.append(hidden("keep", photoId.asText()));
        }
        out.append("<section class='worker-form-section stack outcome-choice'><label>Job outcome<select name='outcome'>")
            .append(option("done", "Done", outcome)).append(option("pending", "Pending", outcome)).append(option("cancel", "Cancel", outcome))
            .append("</select></label><label class='outcome-reason'>Reason<textarea rows='2' name='outcome_reason' maxlength='4000'>")
            .append(h(value(draft, "outcome_reason"))).append("</textarea></label></section>")
            .append("<section class='worker-form-section stack'><h2>Measurements</h2><div class='worker-measurements'>")
            .append(field("Feet buried", "feet_buried", value(draft, "feet_buried"), NUMBER))
            .append("<div class='catalog-choice'><label>Cable / fiber type<select name='fiber_type'>")
            .append(option("", "Choose cable / fiber type", selected));
        for (String type : fibers) out.append(option(type, type, selected));
        out.append(option(OTHER, "Other / enter fiber type", selected)).append("</select></label><div class='custom-fields'>")
            .append(field("Other fiber type", "other_fiber", custom ? fiber : "", "maxlength='120'"))
            .append("</div></div><label>Tech needed<select name='tech_needed'>")
            .append(option("", "Unanswered", tech)).append(option("yes", "Yes", tech)).append(option("no", "No", tech))
            .append("</select></label></div></section>");
        runs(out, draft, "bore", BORE);
        runs(out, draft, "conduit", CONDUIT);
        startGroup(out, "item");
        int i = 0;
        for (JsonNode row : draft.path("items")) {
            String name = value(row, "item"), unit = value(row, "unit");
            ReportCatalog.Item match = null;
            for (ReportCatalog.Item entry : catalog) {
                if (!name.equals(entry.name())) continue;
                if (unit.equals(entry.unit())) { match = entry; break; }
                if (entry.unit().isEmpty()) match = entry;
            }
            boolean other = row.path("empty_other_item").asBoolean() || (match == null && (!name.isEmpty() || !unit.isEmpty()));
            selected = match != null ? itemPair(name, match.unit()) : (other ? OTHER : "");
            out.append("<div class='worker-item-row'><div class='worker-bore-row'><label>Item<select name='item_choice'>")
                .append(option("", "Choose item", selected));
            for (ReportCatalog.Item entry : catalog) {
                String key = itemPair(entry.name(), entry.unit());
                out.append("<option value='").append(h(key)).append("'");
                if (key.equals(selected)) out.append(" selected");
                if (entry.unit().isEmpty()) out.append(" data-enter-unit");
                out.append(">").append(h(entry.name()));
                if (!entry.unit().isEmpty()) out.append(" · ").append(h(entry.unit()));
                out.append("</option>");
            }
            out.append(option(OTHER, "Other / enter item", selected)).append("</select></label>")
                .append(field("Quantity", "item_quantity", value(row, "quantity"), "type='number' inputmode='decimal' min='0.01' max='10000000' step='0.01'"))
                .append(remove("item", i++)).append("</div><div class='item-custom-fields worker-measurements'>")
                .append(field("Other item name", "item_name", other ? name : "", "maxlength='250'"))
                .append(field("Unit", "item_unit", other || (match != null && match.unit().isEmpty()) ? unit : "", "maxlength='80'"))
                .append("</div></div>");
        }
        return out.append("</div></section><section class='worker-form-section'><label>Comments<textarea name='comments' rows='4' maxlength='4000'>")
            .append(h(value(draft, "comments"))).append("</textarea></label></section><div class='worker-submit'>")
            .append("<button name='step' value='photos'>Continue to photos</button></div></form>").toString();
    }

    private static void startGroup(StringBuilder out, String group) {
        out.append("<section class='worker-form-section'><div class='worker-section-title'><h2>").append(title(group))
            .append("s</h2><button type='submit' formnovalidate class='secondary' name='step' value='add-")
            .append(group).append("'>+ Add ").append(group).append("</button></div><div class='worker-bores'>");
    }

    private static void runs(StringBuilder out, ObjectNode draft, String group, List<String> types) {
        startGroup(out, group);
        int i = 0;
        for (JsonNode row : draft.path(group + "s")) {
            String type = value(row, "type");
            out.append("<div class='worker-bore-row'><label>").append(title(group)).append(" type<select name='")
                .append(group).append("_type'>").append(option("", "Choose type", type));
            for (String allowed : types) out.append(option(allowed, surface(group, allowed), type));
            out.append("</select></label>").append(field(title(group) + " feet", group + "_feet", value(row, "feet"), NUMBER))
                .append(remove(group, i++)).append("</div>");
        }
        out.append("</div></section>");
    }

    private static String remove(String group, int i) {
        return "<button type='submit' formnovalidate class='secondary' name='step' value='remove-" + group + "-" + i +
            "' aria-label='Remove " + group + " " + (i + 1) + "'>Remove</button>";
    }

    private static String itemPair(String name, String unit) {
        return App.JSON.createArrayNode().add(name).add(unit).toString();
    }

    private static String tech(JsonNode draft) {
        if (!draft.hasNonNull("tech_needed")) return "";
        return draft.path("tech_needed").asBoolean() ? "yes" : "no";
    }

    private static String surface(String group, String type) {
        if (!group.equals("conduit")) return title(type);
        return switch (type) {
            case "driveway" -> "Driveway (DW)";
            case "sidewalk" -> "Sidewalk (SW)";
            case "existing" -> "Existing conduit (CE)";
            default -> title(type);
        };
    }

    private static String title(String text) {
        return text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String summary(ObjectNode draft) {
        StringBuilder out = new StringBuilder("<dl class='report-summary'>");
        detail(out, "Job outcome", title(value(draft, "outcome")));
        if (!value(draft, "outcome_reason").isBlank()) detail(out, "Reason", value(draft, "outcome_reason"));
        if (!value(draft, "feet_buried").isBlank()) detail(out, "Feet buried", value(draft, "feet_buried"));
        if (!value(draft, "fiber_type").isBlank()) detail(out, "Cable / fiber type", value(draft, "fiber_type"));
        String tech = tech(draft);
        detail(out, "Tech needed", tech.isEmpty() ? "Unanswered" : title(tech));
        for (String group : List.of("bore", "conduit")) {
            for (JsonNode row : draft.path(group + "s"))
                detail(out, title(group), surface(group, value(row, "type")) + " · " + value(row, "feet") + " ft");
        }
        for (JsonNode row : draft.path("items"))
            detail(out, "Item", value(row, "item") + " · " + (value(row, "quantity") + " " + value(row, "unit")).stripTrailing());
        if (!value(draft, "comments").isBlank()) detail(out, "Comments", value(draft, "comments"));
        return out.append("</dl>").toString();
    }

    private static void detail(StringBuilder out, String heading, String content) {
        out.append("<dt>").append(h(heading)).append("</dt><dd>").append(h(content)).append("</dd>");
    }
}
