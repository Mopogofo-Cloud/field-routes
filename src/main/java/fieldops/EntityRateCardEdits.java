package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static fieldops.StoreSql.*;

final class EntityRateCardEdits {
    private static final Set<String> MODELS = Set.of("", "BUNDLE", "BASE_PER_FOOT", "GOOGLE_BILLING", "TIERED_FLAT");
    private static final Map<String, List<String>> AMOUNT_COLUMNS = Map.of(
        "footageTiers", List.of("fromFt", "toFt", "rate"), "items", List.of("rate"),
        "googleLaborItemRates", List.of("rate"), "roleRates", List.of("dropRate", "boreRate"));

    private EntityRateCardEdits() {}

    /** Caller has authenticated an administrator. Check the edit token and dependencies inside the write transaction. */
    static ObjectNode save(Connection db, long entityId, String expectedDigest, ObjectNode patch) throws SQLException {
        return transaction(db, true, () -> {
            var entity = one(db, "SELECT name FROM entities WHERE id=?", entityId);
            if (entity == null) throw new Problem(404, "Contracting entity not found.");
            ObjectNode current = EntityRateCards.read(db, entityId);
            if (!EntityRateCards.digest(current).equals(expectedDigest))
                throw new Problem(409, "This rate card changed. Reopen it to review the latest rates.");
            ObjectNode candidate = current == null ? EntityRateCards.JSON.createObjectNode() : current.deepCopy();
            var original = EntityRateCards.canonical(candidate);
            for (var field : patch.properties()) {
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (EntityRateCards.AMOUNTS.contains(key)) assignNumber(candidate, key, value);
                else if (key.equals("billingModel")) {
                    String model = input(value);
                    if (!model.equals(candidate.path(key).asText(""))) {
                        if (!MODELS.contains(model)) throw new Problem(422, "Choose a supported billing model.");
                        if (model.isEmpty()) candidate.putNull(key);
                        else candidate.put(key, model);
                    }
                } else if (AMOUNT_COLUMNS.containsKey(key)) applyRows(candidate, key, value);
                else throw new Problem(422, "Unknown rate card field.");
            }
            if (original.equals(EntityRateCards.canonical(candidate))) return current;
            if (current == null) candidate.put("companyName", entity.get("name").textValue());
            try {
                EntityRateCards.validatePayroll(db, entityId, candidate);
            } catch (IllegalArgumentException ex) {
                throw new Problem(422, ex.getMessage());
            }
            // source_document remains the independently captured, unmodified WG iDig document.
            change(db, """
                INSERT INTO entity_rate_cards(entity_id,document) VALUES(?,?)
                ON CONFLICT(entity_id) DO UPDATE SET document=excluded.document
                """, entityId, EntityRateCards.canonical(candidate));
            return EntityRateCards.read(db, entityId);
        });
    }

    static String label(ObjectNode card, String section, JsonNode row) {
        if (row.hasNonNull("_label")) return row.get("_label").asText();
        boolean role = section.equals("roleRates");
        String id = row.path(role ? "employeeRoleId" : "dropItemId").asText("");
        String lookup = card == null ? "" : card.path(role ? "roleNames" : "itemNames").path(id).asText("");
        if (!lookup.isBlank()) return lookup;
        List<String> names = role ? List.of("employeeRoleName", "roleName") : List.of("name", "type");
        for (String field : names) {
            String value = row.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return id;
    }

    private static String input(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() && !value.isNumber()) throw new Problem(422, "Invalid rate card value.");
        return value.asText().strip();
    }

    private static void assignNumber(ObjectNode target, String key, JsonNode value) {
        String raw = input(value);
        if (raw.equals(target.path(key).asText("").strip())) return;
        if (raw.isEmpty()) {
            target.putNull(key);
            return;
        }
        try {
            BigDecimal amount = new BigDecimal(raw);
            // Match the arithmetic range supported by payroll formulas without rounding the saved value.
            if (amount.precision() > 1000 || Math.abs((long)amount.scale()) > 1000)
                throw new NumberFormatException();
            if ((key.equals("fromFt") || key.equals("toFt")) && amount.signum() < 0)
                throw new Problem(422, "A footage tier cannot have a negative boundary.");
            target.put(key, amount);
        } catch (NumberFormatException ex) {
            throw new Problem(422, "Enter a valid decimal for " + key + ".");
        }
    }

    private static void applyRows(ObjectNode card, String section, JsonNode supplied) {
        if (!supplied.isArray()) throw new Problem(422, "Expected a list of rate card rows.");
        JsonNode before = card.path(section);
        var after = EntityRateCards.JSON.createArrayNode();
        List<Integer> origins = new ArrayList<>();
        var seen = new HashSet<Integer>();
        boolean tier = section.equals("footageTiers");
        for (JsonNode patch : supplied) {
            if (!patch.isObject()) throw new Problem(422, "Invalid rate card row.");
            for (var property : patch.properties()) {
                String key = property.getKey();
                if (!AMOUNT_COLUMNS.get(section).contains(key) && !key.equals("_index") && !(key.equals("label") && !tier))
                    throw new Problem(422, "Unknown field in a rate card row.");
            }
            int index = -1;
            if (patch.hasNonNull("_index")) {
                try { index = Integer.parseInt(input(patch.get("_index"))); }
                catch (NumberFormatException ex) { throw new Problem(422, "Invalid rate card row number."); }
                if (!before.isArray() || index < 0 || index >= before.size() || !seen.add(index))
                    throw new Problem(422, "Invalid or repeated rate card row number.");
            }
            ObjectNode row = index < 0 ? EntityRateCards.JSON.createObjectNode() : ((ObjectNode)before.get(index)).deepCopy();
            for (String key : AMOUNT_COLUMNS.get(section)) {
                if (patch.has(key)) assignNumber(row, key, patch.get(key));
            }
            if (!tier && patch.has("label")) {
                String name = input(patch.get("label"));
                if (!name.equals(label(card, section, row))) {
                    if (name.isEmpty()) throw new Problem(422, "Enter an item or role name, or remove the row.");
                    row.put("_label", name);
                }
            }
            if (index < 0 && row.isEmpty()) continue; // A blank add-row control is not a saved rate.
            if (!tier && index < 0 && label(card, section, row).isBlank())
                throw new Problem(422, "Enter an item or role name, or remove the row.");
            if (tier && (index < 0 || patch.has("fromFt") || patch.has("toFt"))) {
                String from = row.path("fromFt").asText("").strip(), to = row.path("toFt").asText("").strip();
                if (!from.isEmpty() && !to.isEmpty() && new BigDecimal(from).compareTo(new BigDecimal(to)) > 0)
                    throw new Problem(422, "The end of a footage tier must be at or above its start.");
            }
            after.add(row);
            origins.add(index);
        }
        if (after.isEmpty() && !before.isArray()) return;
        if (before.toString().equals(after.toString())) return;
        // Freeze every retained row's pre-edit reference before removing or reordering any duplicate rows.
        Map<Integer,String> keys = originalKeys(card, section);
        var reserved = new HashSet<>(keys.values());
        for (int i = 0; i < after.size(); i++) {
            var row = (ObjectNode)after.get(i);
            String key = keys.get(origins.get(i));
            if (key == null) key = newKey(card, section, row, reserved);
            row.put("_formulaKey", key);
        }
        card.set(section, after);
    }

    private static Map<Integer,String> originalKeys(ObjectNode card, String section) {
        EntityRateFields.Group group = switch (section) {
            case "footageTiers" -> EntityRateFields.Group.TIERS;
            case "roleRates" -> EntityRateFields.Group.ROLES;
            case "items" -> EntityRateFields.Group.ITEMS;
            case "googleLaborItemRates" -> EntityRateFields.Group.LABOR_ITEMS;
            default -> throw new IllegalArgumentException("Invalid rate card section");
        };
        var keys = new HashMap<Integer,String>();
        for (var field : EntityRateFields.fields(card)) {
            if (field.group() != group || field.rowIndex() < 0) continue;
            if (group == EntityRateFields.Group.ROLES) {
                if (field.key().endsWith("_drop")) keys.put(field.rowIndex(), field.key().substring(0, field.key().length() - 5));
            } else keys.put(field.rowIndex(), field.key());
        }
        JsonNode original = card.path(section);
        for (int i=0; i<original.size(); i++)
            if (original.get(i).hasNonNull("_formulaKey")) keys.put(i, original.get(i).get("_formulaKey").textValue());
        return keys;
    }

    private static String newKey(ObjectNode card, String section, JsonNode row, Set<String> reserved) {
        String prefix = switch (section) {
            case "footageTiers" -> "wg_local_tier_";
            case "items" -> "wg_local_item_";
            case "googleLaborItemRates" -> "wg_local_labor_item_";
            case "roleRates" -> "wg_local_role_";
            default -> throw new IllegalArgumentException("Invalid rate card section");
        };
        String name = section.equals("footageTiers")
            ? row.path("fromFt").asText("start") + "_" + row.path("toFt").asText("open")
            : label(card, section, row);
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) slug = "rate";
        String base = prefix + slug.substring(0,Math.min(slug.length(),28));
        String key = base;
        for (int suffix = 2; !reserved.add(key); suffix++) key = base + "_" + suffix;
        return key;
    }
}
