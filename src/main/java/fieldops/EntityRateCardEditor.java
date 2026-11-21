package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import static fieldops.Views.*;

/** The form draft holds editable cells; the saved card retains the source metadata and original row identities. */
final class EntityRateCardEditor {
    private record Column(String key, String title) {}
    private enum Rows {
        TIERS("footageTiers","Footage tiers","tier",EntityRateFields.Scope.ALL,false,
            new Column("fromFt","From (ft)"),new Column("toFt","To (ft, leave blank for no upper limit)"),new Column("rate","Rate ($)")),
        ITEMS("items","Items & charges","item",EntityRateFields.Scope.ALL,false,
            new Column("label","Item"),new Column("rate","Charge ($)")),
        LABOR_ITEMS("googleLaborItemRates","Labor item rates","labor item",EntityRateFields.Scope.GOOGLE,true,
            new Column("label","Item"),new Column("rate","Labor rate ($)")),
        ROLES("roleRates","Labor rates by role","role",EntityRateFields.Scope.NON_GOOGLE,true,
            new Column("label","Role"),new Column("dropRate","Drop rate ($)"),new Column("boreRate","Bore rate ($)"));

        final String key, title, singular;
        final EntityRateFields.Scope scope;
        final boolean labor;
        final List<Column> columns;
        Rows(String key, String title, String singular, EntityRateFields.Scope scope, boolean labor, Column... columns) {
            this.key = key; this.title = title; this.singular = singular;
            this.scope = scope; this.labor = labor; this.columns = List.of(columns);
        }
    }

    private EntityRateCardEditor() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode admin,
                       long entityId, boolean post, String nav) throws Exception {
        if (!admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        String entity = value(item(Store.entities(db),entityId),"name"), path = "/entities/" + entityId + "/rates";
        ObjectNode saved = EntityRateCards.read(db,entityId);
        ObjectNode baseline = initial(saved), draft = baseline.deepCopy();
        String digest = EntityRateCards.digest(saved), action = "", problem = "";
        boolean stale = false;
        if (post) {
            digest = param(req,"digest");
            try {
                read(req,draft);
                if (!EntityRateCards.digest(saved).equals(digest))
                    throw new Problem(409,"Another administrator changed these rates. Reload the saved rates to continue.");
                action = param(req,"action");
                if ("save".equals(action)) {
                    EntityRateCardEdits.save(db,entityId,digest,patch(baseline,draft));
                    redirect(res,path + "?saved=1");
                    return;
                }
                changeDraft(draft,action);
            } catch (Problem e) {
                res.setStatus(e.status);
                stale = e.status == 409;
                problem = e.getMessage();
            }
        }
        StringBuilder body = new StringBuilder(nav).append("<div class='payroll-editor'>")
            .append(link("← " + entity,"/entities/" + entityId,"worker-back worker-text-button"))
            .append("<header class='section-head'><div><p class='eyebrow'>").append(h(entity))
            .append("</p><h1>Edit rate card and labor rates</h1></div></header>");
        if (!post && "1".equals(param(req,"saved"))) body.append(notice("Rate card and labor rates saved."));
        body.append(alert(problem));
        if (stale) body.append(link("Reload saved rates",path,"btn secondary"));
        body.append(form(path,draft,digest,stale,action,!problem.isEmpty(),EntityRateFields.roadOnlyPerFoot(saved),
            suggestions(ReportCatalog.itemRows(db),saved,"itemNames"), suggestions(Store.roles(db),saved,"roleNames")));
        body.append("</div>");
        page(res,admin,"Rate card and labor rates · " + entity,body.toString());
    }

    static ObjectNode initial(ObjectNode card) {
        ObjectNode draft = App.JSON.createObjectNode().put("billingModel",value(card,"billingModel"));
        for (var scalar : EntityRateFields.SCALARS) draft.put(scalar.name(),value(card,scalar.name()));
        for (Rows group : Rows.values()) {
            var rows = draft.putArray(group.key);
            if (card == null || !card.path(group.key).isArray()) continue;
            for (JsonNode row : card.path(group.key)) {
                int original = rows.size();
                var line = rows.addObject().put("_index",original);
                for (Column col : group.columns) {
                    String cell = col.key.equals("label") ? EntityRateCardEdits.label(card,group.key,row) : value(row,col.key);
                    line.put(col.key,cell);
                }
            }
        }
        return draft;
    }

    private static int index(String value) {
        try {
            int index = Integer.parseInt(value);
            if (index >= 0) return index;
        } catch (NumberFormatException ignored) {}
        throw new Problem(422,"The rate row could not be identified. Reload the editor.");
    }

    private static String submitted(HttpServletRequest req, String key) {
        var values = req.getParameterValues(key);
        if (values == null || values.length != 1)
            throw new Problem(422,"The complete rate form could not be read. Reload the editor.");
        return values[0];
    }

    private static void read(HttpServletRequest req, ObjectNode draft) {
        draft.put("billingModel",submitted(req,"billingModel"));
        for (var scalar : EntityRateFields.SCALARS) draft.put(scalar.name(),submitted(req,scalar.name()));
        for (Rows group : Rows.values()) {
            int count = index(submitted(req,group.key + ".count"));
            if (count > req.getParameterMap().size()) throw new Problem(422,"The rate form is incomplete. Reload the editor.");
            ArrayNode rows = App.JSON.createArrayNode();
            for (int i = 0; i < count; i++) {
                String prefix = group.key + "." + i + ".";
                var line = rows.addObject();
                String original = submitted(req,prefix + "_index");
                if (!original.isEmpty()) line.put("_index",index(original));
                for (Column col : group.columns) line.put(col.key,submitted(req,prefix + col.key));
            }
            draft.set(group.key,rows);
        }
    }

    static ObjectNode patch(ObjectNode baseline, ObjectNode draft) {
        ObjectNode patch = App.JSON.createObjectNode();
        for (var entry : draft.properties()) {
            String key = entry.getKey(); JsonNode modified = entry.getValue();
            if (modified.isArray()) {
                ArrayNode filled = App.JSON.createArrayNode();
                for (JsonNode row : modified) {
                    if (row.has("_index") || row.properties().stream().anyMatch(v -> !v.getValue().asText().isBlank())) filled.add(row);
                }
                modified = filled;
            }
            if (!modified.equals(baseline.get(key))) patch.set(key,modified);
        }
        return patch;
    }

    static void changeDraft(ObjectNode draft, String action) {
        if ("model".equals(action)) return;
        for (Rows group : Rows.values()) {
            var rows = (ArrayNode)draft.get(group.key);
            if (action.equals("add-" + group.key)) {
                var row = rows.addObject();
                for (Column col : group.columns) row.put(col.key,"");
                return;
            }
            String remove = "remove-" + group.key + "-";
            if (action.startsWith(remove)) {
                int i = index(action.substring(remove.length()));
                if (i >= rows.size()) throw new Problem(422,"Choose a row to remove.");
                rows.remove(i);
                return;
            }
        }
        throw new Problem(422,"Choose an action in the rate editor.");
    }

    private static Set<String> suggestions(ArrayNode local, ObjectNode card, String catalog) {
        Set<String> names = new TreeSet<>();
        for (var row : local) names.add(value(row,"name"));
        if (card != null) for (var label : card.path(catalog)) names.add(label.asText(""));
        names.removeIf(String::isBlank);
        return names;
    }

    static String form(String path, ObjectNode draft, String digest, boolean stale, String action, boolean error, boolean roadOnly,
                       Set<String> itemNames, Set<String> roleNames) {
        String model = value(draft,"billingModel");
        var out = new StringBuilder("<form method='post' class='stack' action='").append(h(path)).append("'>")
            .append(hidden("digest",digest));
        out.append("<section class='panel stack' id='billing-model'><h2>Contracting entity</h2>")
            .append("<p class='muted'>Leave an amount blank to clear it. Enter 0 to save a zero rate.</p><div class='two'>")
            .append("<label>Billing model<select name='billingModel'>")
            .append(option("","Choose a billing model",model)).append(option("BASE_PER_FOOT","Base per foot",model))
            .append(option("TIERED_FLAT","Tiered flat",model)).append(option("BUNDLE","Bundle",model))
            .append(option("GOOGLE_BILLING","Google billing",model));
        if (!model.isEmpty() && !Set.of("BASE_PER_FOOT","TIERED_FLAT","BUNDLE","GOOGLE_BILLING").contains(model))
            out.append(option(model,model,model));
        out.append("</select></label>");
        for (var scalar : EntityRateFields.SCALARS)
            if (scalar.group() == EntityRateFields.Group.ENTITY) scalar(out,draft,scalar,roadOnly);
        out.append("</div><div>").append(button("Show fields for model","model","billing-model")).append("</div></section>")
            .append("<section class='panel stack' id='rate-card'><h2>Rate Card</h2>");
        groups(out,draft,model,roadOnly,false,true);
        out.append("</section><section class='panel stack' id='labor-rates'><h2>Labor Rates</h2>");
        groups(out,draft,model,roadOnly,true,true);
        out.append("</section>");
        // Include inactive inputs in every submission so a model switch keeps unsaved edits to the previous model.
        boolean showInactive = error;
        for (Rows group : Rows.values()) if (!group.scope.active(model) && action.contains(group.key)) showInactive = true;
        out.append("<details class='panel'").append(showInactive ? " open" : "").append("><summary>Rates for other billing models</summary><div class='stack'>");
        groups(out,draft,model,roadOnly,false,false);
        groups(out,draft,model,roadOnly,true,false);
        out.append("</div></details>");
        datalist(out,"rate-item-names",itemNames);
        datalist(out,"rate-role-names",roleNames);
        return out.append("<div class='actions'><button type='submit' name='action' value='save'")
            .append(stale ? " disabled" : "").append(">Save rate card and labor rates</button></div></form>").toString();
    }

    private static void groups(StringBuilder out, ObjectNode draft, String model, boolean roadOnly, boolean labor, boolean active) {
        for (var group : EntityRateFields.Group.values()) {
            if (group == EntityRateFields.Group.ENTITY || group.labor != labor) continue;
            var scalars = EntityRateFields.SCALARS.stream()
                .filter(s -> s.group() == group && s.scope().active(model) == active).toList();
            if (!scalars.isEmpty()) {
                out.append("<fieldset class='two'><legend>").append(h(group.label)).append("</legend>");
                for (var scalar : scalars) scalar(out,draft,scalar,roadOnly);
                out.append("</fieldset>");
            }
        }
        for (Rows group : Rows.values()) {
            if (group.labor == labor && group.scope.active(model) == active) rows(out,draft,group);
        }
    }

    private static void scalar(StringBuilder out, ObjectNode draft, EntityRateFields.Scalar scalar, boolean roadOnly) {
        String label = scalar.label() + (scalar.unit().isEmpty() || scalar.label().endsWith(scalar.unit()) ? " ($)" : " ($ " + scalar.unit() + ")");
        if (roadOnly && scalar.name().equals("boreRatePerFoot")) label += " · " + EntityRateFields.ROAD_ONLY_MEASURE;
        input(out,scalar.name(),label,value(draft,scalar.name()),"");
    }

    private static void rows(StringBuilder out, ObjectNode draft, Rows group) {
        ArrayNode rows = (ArrayNode)draft.get(group.key);
        String section = "edit-" + group.key;
        out.append("<section class='stack' id='").append(section).append("'><div class='worker-section-title'><h3>")
            .append(h(group.title)).append("</h3>").append(button("Add " + group.singular,"add-" + group.key,section + "-row-" + rows.size())).append("</div>")
            .append(hidden(group.key + ".count",Integer.toString(rows.size())));
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); String prefix = group.key + "." + i + ".";
            out.append("<fieldset class='stack' id='").append(section).append("-row-").append(i).append("'><legend>")
                .append(h(group.singular.substring(0,1).toUpperCase(java.util.Locale.ROOT) + group.singular.substring(1)))
                .append(" ").append(i + 1).append("</legend><div class='two'>").append(hidden(prefix + "_index",value(row,"_index")));
            for (Column col : group.columns) {
                String listing = col.key.equals("label") ? (group == Rows.ROLES ? "rate-role-names" : "rate-item-names") : "";
                input(out,prefix + col.key,col.title,value(row,col.key),listing);
            }
            out.append("</div><div>").append(button("Remove " + group.singular + " " + (i + 1),"remove-" + group.key + "-" + i,section))
                .append("</div></fieldset>");
        }
        out.append("</section>");
    }

    private static void input(StringBuilder out, String key, String label, String current, String list) {
        out.append("<label>").append(h(label)).append("<input type='text' name='").append(h(key))
            .append("' value='").append(h(current)).append("'");
        if (list.isEmpty()) out.append(" inputmode='decimal'");
        else out.append(" list='").append(h(list)).append("'");
        out.append("></label>");
    }

    private static String button(String label, String action, String target) {
        return "<button type='submit' class='secondary' name='action' value='" + h(action)
            + "' formaction='#" + h(target) + "' formnovalidate>" + h(label) + "</button>";
    }

    private static void datalist(StringBuilder out, String id, Set<String> names) {
        out.append("<datalist id='").append(id).append("'>");
        for (String name : names) out.append("<option value='").append(h(name)).append("'></option>");
        out.append("</datalist>");
    }
}
