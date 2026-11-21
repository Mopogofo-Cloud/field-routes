package fieldops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import static fieldops.Views.*;

/** Stop entry and review state for a route being prepared. */
final class RouteEntryForm {
    private static final int LIMIT = 1000;
    private static final Pattern PIPE = Pattern.compile("(?:^| +)\\|(?: +|$)");
    private static final String INDIVIDUAL = "individual", MULTIPLE = "multiple";

    private RouteEntryForm() {}

    static ObjectNode blank() {
        var draft = App.JSON.createObjectNode().put("mode", INDIVIDUAL).put("stop_label", "").put("urgent", false);
        row(draft.putArray("rows"), "", "");
        return draft;
    }

    static ObjectNode read(HttpServletRequest req) {
        String mode = param(req, "entry_mode");
        var draft = App.JSON.createObjectNode().put("mode", mode).put("stop_label", param(req, "stop_label"))
            .put("urgent", "1".equals(param(req, "urgent")));
        if (MULTIPLE.equals(mode)) return draft.put("bulk", param(req, "entry_bulk"));
        if (!INDIVIDUAL.equals(mode)) throw new Problem(422, "Choose how to enter your stops.");
        List<String> addresses = params(req, "entry_address"), ids = params(req, "entry_job_id");
        if (addresses.size() != ids.size()) throw new Problem(422, "Each address needs its own Job ID field.");
        if (addresses.size() > LIMIT) throw tooMany();
        ArrayNode rows = draft.putArray("rows");
        for (int i = 0; i < addresses.size(); i++) row(rows, addresses.get(i), ids.get(i));
        if (rows.isEmpty()) row(rows, "", "");
        return draft;
    }

    static String hiddenDraft(ObjectNode draft) {
        return hidden("entry_draft", draft.toString());
    }

    static ObjectNode restore(HttpServletRequest req) {
        try {
            if (App.JSON.readTree(param(req, "entry_draft")) instanceof ObjectNode draft &&
                    draft.path("stop_label").isTextual() && draft.path("urgent").isBoolean()) {
                if (MULTIPLE.equals(value(draft, "mode")) && draft.path("bulk").isTextual()) return draft;
                if (INDIVIDUAL.equals(value(draft, "mode")) && draft.get("rows") instanceof ArrayNode rows && rows.size() <= LIMIT) {
                    for (var item : rows) {
                        if (!item.path("address").isTextual() || !item.path("external_job_id").isTextual())
                            throw new Problem(422, "Reopen stop entry to review each address and Job ID.");
                    }
                    return draft;
                }
            }
        } catch (JsonProcessingException ignored) { /* Invalid or incomplete form state. */ }
        throw new Problem(422, "Reopen stop entry to review your addresses.");
    }

    static void edit(ObjectNode draft, String action) {
        if (MULTIPLE.equals(value(draft, "mode"))) {
            if (!"entry-individual".equals(action)) throw new Problem(422, "Choose individual input to edit rows.");
            ArrayNode rows = bulkRows(value(draft, "bulk"), false);
            if (rows.isEmpty()) row(rows, "", "");
            draft.set("rows", rows);
            draft.remove("bulk");
            draft.put("mode", INDIVIDUAL);
            return;
        }
        ArrayNode rows = (ArrayNode) draft.get("rows");
        if ("entry-multiple".equals(action)) {
            StringJoiner lines = new StringJoiner("\n");
            for (JsonNode item : rows) {
                String address = value(item, "address"), id = value(item, "external_job_id");
                if (address.indexOf('\t') >= 0 || id.indexOf('\t') >= 0)
                    throw new Problem(422, "Remove the tab within the address or Job ID before switching to multiple input.");
                // A spreadsheet pair also represents literal colons, pipes, or commas in an ID.
                if (address.contains(":") || PIPE.matcher(address).find() || id.contains(",") || id.contains(":") || PIPE.matcher(id).find()) {
                    lines.add(address + "\t" + id);
                } else {
                    lines.add(id.isEmpty() ? address : address + ":" + id);
                }
            }
            draft.put("bulk", lines.toString()).put("mode", MULTIPLE);
            draft.remove("rows");
        } else if ("entry-add".equals(action)) {
            if (rows.size() >= LIMIT) throw tooMany();
            row(rows, "", "");
        } else if (action.startsWith("entry-remove-")) {
            int i;
            try { i = Integer.parseInt(action.substring("entry-remove-".length())); }
            catch (NumberFormatException e) { throw new Problem(422, "Choose a stop to remove."); }
            if (i < 0 || i >= rows.size()) throw new Problem(422, "Choose a stop to remove.");
            rows.remove(i);
            if (rows.isEmpty()) row(rows, "", "");
        } else throw new Problem(422, "Choose a stop entry action.");
    }

    static ArrayNode stops(ObjectNode draft) {
        RouteStops.stopLabel(value(draft, "stop_label"));
        ArrayNode result;
        if (MULTIPLE.equals(value(draft, "mode"))) result = bulkRows(value(draft, "bulk"), true);
        else {
            result = App.JSON.createArrayNode();
            int number = 0;
            for (JsonNode item : draft.path("rows")) {
                number++;
                String address = value(item, "address"), id = value(item, "external_job_id");
                if (!address.isBlank() || !id.isBlank()) append(result, address, id, "row " + number, true);
            }
        }
        if (result.isEmpty()) throw new Problem(422, "Enter at least one address for the route.");
        return result;
    }

    private static ArrayNode bulkRows(String bulk, boolean validate) {
        ArrayNode rows = App.JSON.createArrayNode();
        int number = 0;
        var lines = bulk.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            number++;
            if (line.isBlank()) continue;
            String source = "line " + number;
            if (line.indexOf('\t') >= 0 || PIPE.matcher(line).find()) {
                String[] cells = line.indexOf('\t') >= 0 ? line.split("\t", 3) : PIPE.split(line, 3);
                if (cells.length > 2) throw new Problem(422, "Extra columns on " + source + ". Use address and optional Job ID.");
                append(rows, cells[0], cells[1], source, validate);
            } else if (line.indexOf(':') < 0) {
                append(rows, line, "", source, validate);
            } else {
                // Only a comma after the ID separates stops. Commas before the colon belong to the address.
                int start = 0;
                while (start < line.length()) {
                    int separator = line.indexOf(':', start);
                    if (separator < 0) throw colonError(source);
                    String address = line.substring(start, separator).strip();
                    if (address.startsWith(",")) throw colonError(source);
                    int end = line.indexOf(',', separator + 1);
                    String id = line.substring(separator + 1, end < 0 ? line.length() : end);
                    if (id.indexOf(':') >= 0) throw colonError(source);
                    append(rows, address, id, source, validate);
                    if (end < 0) break;
                    start = end + 1;
                    while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
                }
            }
        }
        return rows;
    }

    private static Problem colonError(String source) {
        return new Problem(422, "Check " + source + ". Separate stops with a comma after each Job ID, or put one address on each line. " +
            "For a stop without an ID in a comma-separated list, leave the ID after its colon empty.");
    }

    private static void append(ArrayNode rows, String address, String id, String source, boolean validate) {
        address = address.strip();
        id = id.strip();
        if (validate && (address.isEmpty() || address.codePointCount(0, address.length()) > 500))
            throw new Problem(422, "Enter an address of up to 500 characters on " + source + ".");
        if (validate && id.codePointCount(0, id.length()) > 120)
            throw new Problem(422, "The Job ID on " + source + " exceeds 120 characters.");
        if (rows.size() >= LIMIT) throw tooMany();
        row(rows, address, id);
    }

    private static void row(ArrayNode rows, String address, String id) {
        rows.addObject().put("address", address).put("external_job_id", id);
    }

    private static Problem tooMany() { return new Problem(422, "A route can have up to 1,000 stops."); }

    static String render(ObjectNode draft) {
        boolean multiple = MULTIPLE.equals(value(draft, "mode"));
        var out = new StringBuilder(hidden("entry_mode", value(draft, "mode")));
        out.append("<div class='actions'><button type='submit' class='secondary' name='step' role='switch' aria-label='Multiple input' ")
            .append("aria-checked='").append(multiple).append("' value='").append(multiple ? "entry-individual" : "entry-multiple")
            .append("' formnovalidate>Multiple input ").append(multiple ? "on" : "off").append("</button></div>");
        if (multiple) {
            out.append("<label>Addresses and Job IDs<textarea name='entry_bulk' rows='6' aria-describedby='entry-hint'>")
                .append(h(value(draft, "bulk"))).append("</textarea></label>")
                .append("<p id='entry-hint' class='helper'>Enter one address per line, paste address and Job ID columns, or enter pairs like ")
                .append("<strong>123 Main St:001-A, 45 Oak Ave:002-B</strong>. Leave the ID after the colon empty for a stop without one. ")
                .append("You can also use <strong>123 Main St | 001-A</strong>.</p>");
        } else {
            int i = 0;
            for (var item : draft.path("rows")) {
                out.append("<div class='stack'><div class='two'>").append(field("Address", "entry_address", value(item, "address"), "maxlength='500'"))
                    .append(field("Job ID (optional)", "entry_job_id", value(item, "external_job_id"), "maxlength='120'")).append("</div>");
                if (draft.path("rows").size() > 1) out.append("<div><button type='submit' class='secondary' name='step' value='entry-remove-")
                    .append(i).append("' aria-label='Remove stop ").append(i + 1).append("' formnovalidate>Remove</button></div>");
                out.append("</div>");
                i++;
            }
            if (i < LIMIT) out.append("<div><button type='submit' name='step' class='secondary' value='entry-add' formnovalidate>+ Add address</button></div>");
        }
        return out.append(AdminViews.stopSettings(value(draft, "stop_label"), draft.path("urgent").asBoolean()))
            .append("<p class='helper'>The label and urgency apply to every stop on this route.</p>").toString();
    }
}
