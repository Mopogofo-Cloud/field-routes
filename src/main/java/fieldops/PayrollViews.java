package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import static fieldops.Views.*;

final class PayrollViews {
    private static final List<String> RATE_FIELDS = List.of("label", "key", "amount", "unit");
    private static final List<String> FORMULA_FIELDS = List.of("label", "basis", "expression", "footage_credit");
    private static final int MAX_ROWS = 100;
    private PayrollViews() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode admin,
                       long entityId, boolean post, String nav) throws Exception {
        if (!admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        String entity = value(item(Store.entities(db), entityId), "name");
        String path = "/entities/" + entityId + "/payroll";
        Map<String,String> inputs = PayrollInputs.labels(db);
        ObjectNode config = post ? read(req) : EntityPayroll.load(db, entityId);
        ObjectNode cards = post ? EntityRateCards.read(db, entityId) : (ObjectNode) config.get("rate_cards");
        ArrayNode rates = (ArrayNode)config.get("rates"), formulas = (ArrayNode)config.get("formulas");
        String action = param(req, "action"), problem = "", preview = "";
        int chosen = -1;
        boolean stale = false;
        if (post) {
            try {
                String previous = param(req, "preview_index");
                if (!previous.isEmpty()) chosen = rowIndex(previous, formulas);
                if (action.equals("save")) {
                    ObjectNode clean = config.deepCopy();
                    removeBlankRows((ArrayNode)clean.get("rates"), RATE_FIELDS);
                    removeBlankRows((ArrayNode)clean.get("formulas"), FORMULA_FIELDS);
                    EntityPayroll.save(db, entityId, clean);
                    redirect(res, path + "?saved=1");
                    return;
                } else if (action.equals("add-rate")) addRow(rates, "rates");
                else if (action.equals("add-formula")) addRow(formulas, "formulas");
                else if (action.startsWith("remove-rate-")) rates.remove(rowIndex(action.substring("remove-rate-".length()), rates));
                else if (action.startsWith("remove-formula-")) {
                    int removed = rowIndex(action.substring("remove-formula-".length()), formulas);
                    formulas.remove(removed);
                    if (removed == chosen) chosen = -1;
                    else if (removed < chosen) chosen--;
                } else if (action.startsWith("preview-")) chosen = rowIndex(action.substring("preview-".length()), formulas);
                else if (!action.equals("calculate") || chosen < 0) throw new Problem(422, "Choose a payroll settings action.");
            } catch (Problem e) {
                res.setStatus(e.status);
                stale = e.status == 409;
                problem = e.getMessage();
            }
        }
        if (chosen >= 0) {
            try {
                Preview sample = preview(req, formulas.get(chosen), rates, inputs, cards, action.equals("calculate"));
                preview = sample.html();
                if (sample.invalid() && problem.isEmpty()) res.setStatus(422);
            } catch (Problem e) {
                if (problem.isEmpty()) res.setStatus(e.status);
                preview = "<section class='panel stack' id='sample-preview'><h2>Sample calculation</h2>" + alert(e.getMessage()) + "</section>";
            }
        }
        StringBuilder body = new StringBuilder(nav).append("<div class='payroll-editor'>")
            .append(link("← " + entity, "/entities/" + entityId, "worker-back worker-text-button"))
            .append("<header class='section-head'><div><p class='eyebrow'>").append(h(entity))
            .append("</p><h1>Payroll settings</h1></div></header>")
            .append(!post && param(req, "saved").equals("1") ? notice("Payroll settings saved.") : "").append(alert(problem));
        if (stale) body.append(link("Reload saved settings", path, "btn secondary"));
        if (cards != null) body.append("<details class='panel'><summary>Rate card and labor rates</summary>")
            .append(link("Edit rate card and labor rates", "/entities/" + entityId + "/rates", "btn secondary"))
            .append(EntityRateCardView.render(cards)).append("</details>");
        body.append("<form class='stack' method='post' action='").append(path).append("'>")
            .append(hidden("revision", value(config, "revision")));
        if (chosen >= 0) body.append(hidden("preview_index", Integer.toString(chosen)));
        body.append("<section class='panel stack' id='entity-rates'><div class='worker-section-title'><h2>")
            .append(cards == null ? "Rates" : "Custom rates").append("</h2>")
            .append(button("Add rate", "add-rate", "entity-rates")).append("</div>")
            .append("<p class='helper'>Give each rate a key to use in formulas. Start with a lowercase letter. You can also use digits and underscores.</p>");
        if (rates.isEmpty()) body.append(cards == null ? "<p>No rates configured.</p>" : "<p>No custom rates configured.</p>");
        int i = 0;
        for (JsonNode rate : rates) {
            body.append("<fieldset class='stack'><legend>Rate ").append(i + 1).append("</legend><div class='two'>")
                .append(field("Rate name", "rate_label", value(rate, "label"), "maxlength='120'"))
                .append(field("Formula key", "rate_key", value(rate, "key"), "maxlength='64' autocapitalize='none' spellcheck='false'"))
                .append(field("Amount ($)", "rate_amount", value(rate, "amount"), "type='number' step='0.0001' min='0' max='1000000000'"))
                .append(field("Unit (optional)", "rate_unit", value(rate, "unit"), "maxlength='80'"))
                .append("</div><div>").append(button("Remove rate " + (i + 1), "remove-rate-" + i, "entity-rates")).append("</div></fieldset>");
            i++;
        }
        body.append("</section><section class='panel stack' id='entity-formulas'><div class='worker-section-title'><h2>Formulas</h2>")
            .append(button("Add formula", "add-formula", "entity-formulas")).append("</div>")
            .append("<p class='helper'>Combine field names and rate keys using <code>+ - * /</code> and parentheses. ")
            .append("Footage credit applies to feet buried, bore feet and conduit feet. Split credit divides those measurements by the assigned crew size.</p>")
            .append(reference(inputs,cards));
        if (formulas.isEmpty()) body.append("<p>No formulas configured.</p>");
        i = 0;
        for (JsonNode formula : formulas) {
            String basis = value(formula, "basis"), credit = footageMode(formula);
            body.append("<fieldset class='stack'><legend>Formula ").append(i + 1).append("</legend><div class='two'>")
                .append(field("Formula name", "formula_label", value(formula, "label"), "maxlength='120'"))
                .append("<label>Pay basis<select name='formula_basis'>").append(option("", "Choose pay basis", basis))
                .append(option("daily", "Daily", basis)).append(option("production", "Production", basis)).append("</select></label></div>")
                .append("<label for='footage-").append(i).append("'>Footage credit</label><select id='footage-").append(i)
                .append("' name='formula_footage_credit'>").append(option("full", "Full footage for each contractor", credit))
                .append(option("split", "Split equally among the assigned crew", credit)).append("</select>")
                .append("<label for='expression-").append(i).append("'>Expression</label><textarea id='expression-").append(i)
                .append("' class='formula-expression' name='formula_expression' rows='2' maxlength='2000' autocapitalize='none' spellcheck='false'>")
                .append(h(value(formula, "expression"))).append("</textarea><div class='row'>")
                .append(button("Preview formula " + (i + 1), "preview-" + i, "sample-preview"))
                .append(button("Remove formula " + (i + 1), "remove-formula-" + i, "entity-formulas"))
                .append("</div></fieldset>");
            i++;
        }
        body.append("</section>").append(preview)
            .append("<div class='actions'><button name='action' value='save'>Save payroll settings</button></div></form></div>");
        page(res, admin, "Payroll settings · " + entity, body.toString());
    }

    private static ObjectNode read(HttpServletRequest req) {
        ObjectNode config = App.JSON.createObjectNode().put("revision", id(param(req, "revision")));
        config.set("rates", rows(req, "rate", RATE_FIELDS));
        config.set("formulas", rows(req, "formula", FORMULA_FIELDS));
        return config;
    }

    private static ArrayNode rows(HttpServletRequest req, String prefix, List<String> fields) {
        ArrayNode rows = App.JSON.createArrayNode();
        int count = params(req, prefix + "_" + fields.getFirst()).size();
        if (count > MAX_ROWS) throw new Problem(422, "An entity can have up to 100 " + prefix + "s.");
        for (int i = 0; i < count; i++) rows.addObject();
        for (String field : fields) {
            List<String> submitted = params(req, prefix + "_" + field);
            if (field.equals("footage_credit") && submitted.isEmpty()) {
                // An editor opened before the footage choice existed used full credit.
                for (JsonNode row : rows) ((ObjectNode)row).put(field, "full");
                continue;
            }
            if (submitted.size() != count) throw new Problem(422, "Incomplete " + prefix + " rows. Reload the editor to continue.");
            for (int i = 0; i < count; i++) ((ObjectNode)rows.get(i)).put(field, submitted.get(i));
        }
        return rows;
    }

    private static void removeBlankRows(ArrayNode rows, List<String> fields) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            JsonNode row = rows.get(i);
            if (fields.stream().allMatch(field -> value(row, field).isBlank() ||
                (field.equals("footage_credit") && value(row, field).equals("full")))) rows.remove(i);
        }
    }

    private static void addRow(ArrayNode rows, String title) {
        if (rows.size() >= MAX_ROWS) throw new Problem(422, "An entity can have up to 100 " + title + ".");
        rows.addObject();
    }

    private static int rowIndex(String raw, ArrayNode rows) {
        long index = id(raw);
        if (index < 0 || index >= rows.size()) throw new Problem(422, "Choose a row in this form.");
        return (int)index;
    }

    private static String button(String title, String action, String target) {
        return "<button type='submit' class='secondary' name='action' value='" + action + "' formaction='#" + target +
            "' formnovalidate>" + h(title) + "</button>";
    }

    static String reference(Map<String,String> inputs, ObjectNode cards) {
        StringBuilder out = new StringBuilder("<details class='formula-reference'><summary>Formula fields and example</summary>")
            .append("<p>Example for daily pay</p><code>days_worked * profile_rate</code>");
        var roadMeasures = RoadBoreRules.forCard(cards);
        if ("GOOGLE_BILLING".equals(value(cards,"billingModel"))) {
            roadExample(out,"Road Bore · billed per ft","wg_bore_road_bore_rate",roadMeasures);
            roadExample(out,"Bore Road Bore · labor per Q","wg_google_labor_bore_road_bore_rate",roadMeasures);
        } else {
            roadExample(out,"Road bore amount · " + EntityRateFields.ROAD_ONLY_MEASURE,EntityRateFields.GENERIC_BORE_KEY,roadMeasures);
        }
        out.append("<table><thead><tr><th scope='col'>Field</th><th scope='col'>Name to use</th></tr></thead><tbody>");
        inputs.forEach((key, label) -> out.append("<tr><td>").append(h(label)).append("</td><td><code>")
            .append(h(key)).append("</code></td></tr>"));
        return out.append("</tbody></table></details>").toString();
    }

    private static void roadExample(StringBuilder out, String label, String rateKey, Map<String,String> measures) {
        String quantity = measures.get(rateKey);
        if (quantity != null) out.append("<p>").append(h(label)).append("</p><code>")
            .append(h(quantity)).append(" * ").append(h(rateKey)).append("</code>");
    }

    private static String footageMode(JsonNode formula) {
        return value(formula, "footage_credit").isBlank() ? "full" : value(formula, "footage_credit");
    }

    private record Preview(String html, boolean invalid) {}

    private static Preview preview(HttpServletRequest req, JsonNode formula, ArrayNode rates,
                                  Map<String,String> inputs, ObjectNode card, boolean calculate) {
        String expression = value(formula, "expression").strip();
        if (expression.isEmpty()) throw new Problem(422, "Enter an expression to preview this formula.");
        var amounts = new LinkedHashMap<String, JsonNode>();
        var duplicates = new LinkedHashSet<String>();
        for (JsonNode row : rates) {
            String key = value(row, "key").strip();
            if (!key.isEmpty() && amounts.put(key, row.path("amount")) != null) duplicates.add(key);
        }
        var cardAmounts = EntityRateFields.amounts(card);
        var allowed = new LinkedHashSet<>(inputs.keySet());
        allowed.addAll(amounts.keySet());
        allowed.addAll(cardAmounts.keySet());
        PayrollExpression.validate(expression, allowed, RoadBoreRules.forCard(card));
        var variables = new LinkedHashSet<>(PayrollExpression.variables(expression));
        String credit = footageMode(formula);
        if (!List.of("full", "split").contains(credit)) throw new Problem(422, "Choose a footage credit option.");
        boolean splitFootage = credit.equals("split") && variables.stream().anyMatch(PayrollInputs::isFootage);
        if (splitFootage) variables.add("crew_size");
        var values = new LinkedHashMap<String, BigDecimal>();
        String label = value(formula, "label").strip();
        StringBuilder out = new StringBuilder("<section class='panel stack' id='sample-preview'><h2>Sample calculation</h2>");
        if (!label.isBlank()) out.append("<h3>").append(h(label)).append("</h3>");
        out.append("<code class='preview-expression'>").append(h(expression)).append("</code>")
            .append("<p class='helper'>Calculated from the amounts in this form and the sample values you enter. ")
            .append(splitFootage ? "Enter total footage before splitting it among the crew." : "").append("</p><div class='two'>");
        String problem = "";
        for (String key : variables) {
            if (inputs.containsKey(key)) {
                if (amounts.containsKey(key)) throw new Problem(422, "The rate key " + key + " is already a report field. Choose a different rate key.");
                String sample = param(req, "sample_" + key);
                out.append(field(inputs.get(key), "sample_" + key, sample, "inputmode='decimal' maxlength='80'"));
                if (calculate) {
                    try {
                        BigDecimal numeric = sampleNumber(sample, inputs.get(key));
                        if (key.equals("crew_size") && (numeric.signum() <= 0 || numeric.stripTrailingZeros().scale() > 0))
                            throw new Problem(422, "Enter a whole crew size of at least 1.");
                        if (key.endsWith("_bore_count") && (numeric.signum() < 0 || numeric.stripTrailingZeros().scale() > 0))
                            throw new Problem(422, "Enter zero or a positive whole number of recorded bore entries.");
                        values.put(key, numeric);
                    } catch (Problem e) { if (problem.isEmpty()) problem = e.getMessage(); }
                }
            } else {
                if (duplicates.contains(key) || (amounts.containsKey(key) && cardAmounts.containsKey(key)))
                    throw new Problem(422, "More than one rate uses " + key + ". Give each rate a unique key.");
                if (key.startsWith("item_")) throw new Problem(422, "Rate keys starting with item_ are reserved for catalog items.");
                BigDecimal amount = cardAmounts.get(key);
                if (amount == null) amount = BigDecimal.valueOf(EntityPayroll.amountUnits(amounts.get(key)), 4).stripTrailingZeros();
                values.put(key, amount);
                out.append("<p class='helper'>").append(h(key)).append(" · $").append(h(amount.toPlainString())).append("</p>");
            }
        }
        out.append("</div>");
        if (calculate && problem.isEmpty()) {
            try {
                Map<String, BigDecimal> credited = PayrollInputs.creditFootage(values, credit, values.get("crew_size"));
                BigDecimal answer = PayrollExpression.evaluate(expression, credited);
                out.append("<p class='notice' role='status'>Preview result · <strong>").append(h(answer.stripTrailingZeros().toPlainString())).append("</strong></p>");
            } catch (Problem e) { problem = e.getMessage(); }
        }
        out.append(alert(problem)).append("<div>").append(button("Calculate preview", "calculate", "sample-preview"))
            .append("</div></section>");
        return new Preview(out.toString(), !problem.isEmpty());
    }

    private static BigDecimal sampleNumber(String raw, String label) {
        String s = raw == null ? "" : raw.strip();
        if (s.length() > 80 || !s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))
            throw new Problem(422, "Enter a decimal number for " + label + ".");
        return new BigDecimal(s);
    }
}
