package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** A single projection supplies the saved WG iDig amounts to payroll and the entity page. */
final class EntityRateFields {
    enum Scope {
        ALL, GOOGLE, NON_GOOGLE, BUNDLE;

        boolean active(String model) {
            return switch (this) {
                case ALL -> true;
                case GOOGLE -> "GOOGLE_BILLING".equals(model);
                case NON_GOOGLE -> !"GOOGLE_BILLING".equals(model);
                case BUNDLE -> "BUNDLE".equals(model);
            };
        }
    }

    enum Group {
        ENTITY("Contracting entity", false, Scope.ALL), TIERS("Footage Tiers / Tarifas por Metraje", false, Scope.ALL),
        BORE("Bore Rates / Tarifas de Perforacion", false, Scope.GOOGLE), CONDUIT("Conduit Rates / Tarifas de Conducto", false, Scope.GOOGLE),
        ITEMS("Items & Charges", false, Scope.ALL), LABOR("Labor Rates / Tarifas de Labor", true, Scope.GOOGLE),
        LABOR_ITEMS("Labor Item Rates", true, Scope.GOOGLE), ROLES("Labor Rates by Role", true, Scope.NON_GOOGLE);

        final String label;
        final boolean labor;
        final Scope scope;
        Group(String label, boolean labor, Scope scope) { this.label = label; this.labor = labor; this.scope = scope; }
    }

    record Field(Group group, Scope scope, String label, String unit, String key, BigDecimal amount, int rowIndex) {
        Field(Group group, String label, String unit, String key, BigDecimal amount, int rowIndex) {
            this(group, group.scope, label, unit, key, amount, rowIndex);
        }
    }
    record Scalar(String name, Group group, String label, String unit, Scope scope) {
        Scalar(String name, Group group, String label, String unit) { this(name, group, label, unit, group.scope); }
    }

    static final List<Scalar> SCALARS = List.of(
        new Scalar("driveRate", Group.ENTITY, "Drive Rate", ""),
        new Scalar("baseFeeWithBore", Group.TIERS, "Base fee with bore included", "", Scope.BUNDLE),
        new Scalar("boreRatePerFoot", Group.BORE, "Rate per ft", "per ft", Scope.NON_GOOGLE),
        new Scalar("boreDrivewayRate", Group.BORE, "Driveway", "per ft"),
        new Scalar("boreSidewalkRate", Group.BORE, "Sidewalk", "per Q"),
        new Scalar("boreSidewalkOverageRate", Group.BORE, "Sidewalk Overage", "per ft over 6ft"),
        new Scalar("boreRoadBoreRate", Group.BORE, "Road Bore", "per ft"),
        new Scalar("conduitDwRate", Group.CONDUIT, "Conducto DW", "per Q"),
        new Scalar("conduitSwRate", Group.CONDUIT, "Conducto SW", "per Q"),
        new Scalar("conduitCeRate", Group.CONDUIT, "Conducto Existente", "per Q"),
        new Scalar("conduitCeOverageRate", Group.CONDUIT, "C.E > 200", "overage per ft"),
        new Scalar("googleLaborDropRate", Group.LABOR, "Drop", "per drop"),
        new Scalar("googleLaborBoreDrivewayRate", Group.LABOR, "Bore Driveway", "per Q"),
        new Scalar("googleLaborBoreSidewalkRate", Group.LABOR, "Bore Sidewalk", "per Q"),
        new Scalar("googleLaborBoreRoadBoreRate", Group.LABOR, "Bore Road Bore", "per Q"),
        new Scalar("googleLaborConduitDwRate", Group.LABOR, "Conduit DW", "per Q"),
        new Scalar("googleLaborConduitSwRate", Group.LABOR, "Conduit SW", "per Q")
    );

    static final String GENERIC_BORE_KEY = "wg_bore_rate_per_foot";
    static final String ROAD_ONLY_MEASURE = "Road bore feet only";

    private EntityRateFields() {}

    static boolean roadOnlyPerFoot(ObjectNode card) {
        // The imported id belongs to WG iDig. Locally created cards have no source id.
        return card != null && "2".equals(card.path("id").asText())
            && "COMCAST - SUBCONTRACTOR 2026".equals(card.path("companyName").asText("").strip());
    }

    static Map<String, BigDecimal> amounts(ObjectNode card) {
        Map<String,BigDecimal> variables = new LinkedHashMap<>();
        for (Field field : fields(card)) {
            if (field.amount() != null && variables.put(field.key(), field.amount()) != null)
                throw new IllegalArgumentException("Duplicate WG iDig formula key");
        }
        return variables;
    }

    static List<Field> fields(ObjectNode card) {
        var result = new ArrayList<Field>();
        if (card == null) return result;
        addTiers(result, card);
        for (Scalar scalar : SCALARS) {
            BigDecimal value = decimal(card, scalar.name());
            if (value == null) continue;
            String key = "wg_" + scalar.name().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
            result.add(new Field(scalar.group(), scalar.scope(), scalar.label(), scalar.unit(), key, value, -1));
        }
        addItems(result, card, false);
        addItems(result, card, true);
        addRoles(result, card);
        return result;
    }

    private static void addTiers(List<Field> result, ObjectNode card) {
        var counts = new HashMap<String,Integer>();
        int position = 0;
        for (JsonNode tier : card.path("footageTiers")) {
            position++;
            BigDecimal from = decimal(tier, "fromFt"), to = decimal(tier, "toFt"), amount = decimal(tier, "rate");
            if (from == null && to == null && amount == null) continue;
            String label = (from == null ? "" : "From " + displayNumber(from) + " ft · ") +
                (to == null ? "To ∞" : "To " + displayNumber(to) + " ft");
            String base = "wg_tier_" + boundKey(from, "start") + "_" + boundKey(to, "open");
            // Leave room for repeat suffixes within PayrollExpression's 64-character key limit.
            if (base.length() > 50)
                base = "wg_tier_" + fingerprint(base);
            String key = tier.path("_formulaKey").asText(occurrence(base, counts));
            result.add(new Field(Group.TIERS, label, "", key, amount, position - 1));
        }
    }

    static String displayNumber(BigDecimal value) {
        // Avoid expanding an extreme decimal exponent into an unbounded display string.
        return Math.abs((long)value.scale()) > 128 ? value.toString() : value.toPlainString();
    }

    private static String boundKey(BigDecimal value, String empty) {
        return value == null ? empty : displayNumber(value.stripTrailingZeros()).replace("-", "neg").replace('+', 'p').replace('.', 'p').toLowerCase(Locale.ROOT);
    }

    private static String fingerprint(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static String occurrence(String base, Map<String,Integer> counts) {
        int n = counts.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "_" + n;
    }

    private static void addItems(List<Field> result, ObjectNode card, boolean labor) {
        var counts = new HashMap<String,Integer>();
        int position = 0;
        for (JsonNode row : card.path(labor ? "googleLaborItemRates" : "items")) {
            position++;
            String id = sourceId(row, "dropItemId");
            String type = row.path("type").asText("");
            String embeddedName = row.path("name").asText("").isBlank() ? "type" : "name";
            String name = row.path("_label").asText(catalogLabel(card.path("itemNames"), id, row, embeddedName));
            BigDecimal value = decimal(row, "rate");
            if (id.isEmpty() && name.isEmpty() && value == null) continue;
            String identifier = id;
            if (identifier.isEmpty()) {
                // Older WG cards recorded a type string instead of a catalog ID.
                identifier = type.isBlank() ? "row_" + position : "type_" + fingerprint(type);
            }
            String base = (labor ? "wg_labor_item_" : "wg_item_") + identifier;
            String key = row.path("_formulaKey").asText(occurrence(base, counts));
            result.add(new Field(labor ? Group.LABOR_ITEMS : Group.ITEMS, name, "", key, value, position - 1));
        }
    }

    private static void addRoles(List<Field> result, ObjectNode card) {
        var counts = new HashMap<String,Integer>();
        int position = 0;
        for (JsonNode row : card.path("roleRates")) {
            position++;
            String id = sourceId(row, "employeeRoleId");
            String embeddedName = row.path("employeeRoleName").asText("").isBlank() ? "roleName" : "employeeRoleName";
            String name = row.path("_label").asText(catalogLabel(card.path("roleNames"), id, row, embeddedName));
            BigDecimal drop = decimal(row, "dropRate"), bore = decimal(row, "boreRate");
            if (id.isEmpty() && name.isEmpty() && drop == null && bore == null) continue;
            String base = row.path("_formulaKey").asText(occurrence("wg_role_" + (id.isEmpty() ? "row_" + position : id), counts));
            String prefix = name.isEmpty() ? "" : name + " · ";
            result.add(new Field(Group.ROLES, prefix + "Drop Rate", "", base + "_drop", drop, position - 1));
            result.add(new Field(Group.ROLES, prefix + "Bore Rate", "", base + "_bore", bore, position - 1));
        }
    }

    private static String sourceId(JsonNode row, String field) {
        BigDecimal value = decimal(row, field);
        return value == null ? "" : value.toBigIntegerExact().toString();
    }

    private static String catalogLabel(JsonNode lookup, String id, JsonNode row, String embeddedName) {
        String name = lookup.path(id).asText(""), stored = row.path(embeddedName).asText("");
        if (name.isBlank()) return stored.isBlank() ? id : stored;
        if (stored.isBlank() || stored.equals(name)) return name;
        return name + " (saved as " + stored + ")";
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        return new BigDecimal(value.asText().strip());
    }
}
