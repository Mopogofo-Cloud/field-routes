package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static fieldops.Views.*;

/** Submitted text and selections remain available to render the report if a save fails. */
final class MainlineReportForm {
    final ObjectNode draft;
    final Set<String> removedPhotos;
    private final Set<String> removedExpenses;
    private final Map<String,String> extraAmounts = new LinkedHashMap<>();

    MainlineReportForm(HttpServletRequest req, ObjectNode daily, boolean post) {
        removedPhotos = post ? new HashSet<>(params(req, "remove_photo")) : Set.of();
        removedExpenses = post ? new HashSet<>(params(req, "remove_expense")) : Set.of();
        if (!post) {
            draft = daily.deepCopy();
            return;
        }
        draft = App.JSON.createObjectNode().put("revision", param(req, "revision"))
            .put("priority", param(req, "priority")).put("comments", param(req, "comments"))
            .put("total_footage", param(req, "total_footage"));
        ArrayNode drivers = draft.putArray("driver_ids");
        for (String key : params(req, "driver")) drivers.add(key);
        if ("1".equals(param(req, "driver_baseline"))) {
            var baseline = draft.putArray("initial_driver_ids");
            for (String key : params(req, "initial_driver")) baseline.add(key);
        }
        var types = params(req, "expense_type");
        var costs = params(req, "expense_cost");
        // An existing report can have up to 500 expenses and the form includes one blank row.
        if (costs.size() != types.size() || types.size() > 501) throw StoreInput.invalid("expenses");
        var expenses = draft.putArray("expenses");
        String action = param(req, "action");
        for (int i = 0; i < types.size(); i++) {
            if (!action.equals("remove-expense-" + i))
                expenses.addObject().put("type", types.get(i)).put("cost", costs.get(i));
        }
        if (action.equals("add-expense") && expenses.size() < 500) expenses.addObject();
        for (String type : Mainline.EXPENSE_TYPES) {
            String amounts = param(req, "new_expense_" + type);
            if (!amounts.isBlank()) extraAmounts.put(type, amounts);
        }
    }

    ObjectNode changes() {
        var result = draft.deepCopy();
        var expenses = result.putArray("expenses");
        int i = 0;
        for (var entry : draft.path("expenses")) {
            boolean remove = removedExpenses.contains(Integer.toString(i++));
            if (!remove && !(value(entry, "type").isBlank() && value(entry, "cost").isBlank())) expenses.add(entry);
        }
        for (var group : extraAmounts.entrySet()) {
            var amounts = group.getValue().lines().iterator();
            while (amounts.hasNext()) {
                String cost = amounts.next().strip();
                if (cost.isEmpty()) continue;
                if (expenses.size() >= 500) throw new Problem(422, "A daily report can have up to 500 expenses.");
                expenses.addObject().put("type", group.getKey()).put("cost", cost);
            }
        }
        return result;
    }

    Set<Long> removals() {
        var ids = new HashSet<Long>();
        for (String value : removedPhotos) ids.add(id(value));
        return ids;
    }

    String expenses() {
        var out = new StringBuilder("<fieldset class='stack'><legend>Expenses</legend>");
        int index = 0;
        boolean hasBlank = false;
        for (var expense : draft.path("expenses")) {
            String type = value(expense, "type"), cost = value(expense, "cost");
            hasBlank |= type.isBlank() && cost.isBlank();
            expenseRow(out, type, cost);
            if (!type.isEmpty() || !cost.isEmpty()) out.append("<label class='urgent-check'><input type='checkbox' name='remove_expense' value='")
                .append(index).append("'").append(removedExpenses.contains(Integer.toString(index)) ? " checked" : "")
                .append(">Remove expense ").append(index + 1).append(" on save</label>");
            index++;
        }
        if (!hasBlank && index < 500) expenseRow(out, "", "");
        out.append("<details").append(extraAmounts.isEmpty() ? "" : " open")
            .append("><summary>Add more expenses</summary><div class='stack'><p class='helper'>Enter each additional amount on its own line under its expense type.</p>");
        for (String type : Mainline.EXPENSE_TYPES) out.append("<label>").append(h(type)).append(" ($)<textarea rows='2' name='new_expense_")
            .append(h(type)).append("'>").append(h(extraAmounts.getOrDefault(type, ""))).append("</textarea></label>");
        return out.append("</div></details></fieldset>").toString();
    }

    private static void expenseRow(StringBuilder out, String type, String cost) {
        out.append("<div class='two'><label>Expense type<select name='expense_type'>").append(option("", "Select type", type));
        for (String choice : Mainline.EXPENSE_TYPES) out.append(option(choice, choice, type));
        out.append("</select></label>").append(field("Cost ($)", "expense_cost", cost,
            "type='number' min='0' step='0.01'")).append("</div>");
    }

    static boolean multipart(HttpServletRequest req) {
        String type = req.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    static Map<String,List<Part>> uploads(HttpServletRequest req) throws IOException {
        var uploads = new LinkedHashMap<String,List<Part>>();
        if (!multipart(req)) return uploads;
        try {
            for (Part part : req.getParts()) {
                String filename = part.getSubmittedFileName();
                if (filename == null || filename.isEmpty()) continue;
                String category = switch (part.getName()) {
                    case "photos_map" -> "map";
                    case "photos_pictures" -> "pictures";
                    case "photos_receipts" -> "receipts";
                    default -> throw new Problem(422, "Choose photos in Map, Pictures, or Receipts.");
                };
                uploads.computeIfAbsent(category, key -> new ArrayList<>()).add(part);
            }
        } catch (ServletException err) {
            throw new Problem(422, "The upload could not be read. Choose your photos again.");
        } catch (IllegalStateException err) {
            throw new Problem(413, "The upload exceeds the size limit. Each photo may be up to 12 MiB.");
        }
        return uploads;
    }
}
