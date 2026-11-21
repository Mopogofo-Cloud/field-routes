package fieldops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static fieldops.StoreSql.*;

/** Imports complete contracting entity cards captured during an authorized visit to WG iDig. */
public final class EntityRateCards {
    static final ObjectMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build();

    private static final Set<String> TEXT = Set.of("companyName", "billingModel", "location");
    private static final Set<String> COORDINATES = Set.of("latitude", "longitude");
    static final Set<String> AMOUNTS = Set.of("driveRate", "baseFeeWithBore", "boreRatePerFoot",
        "boreDrivewayRate", "boreSidewalkRate", "boreSidewalkOverageRate", "boreRoadBoreRate",
        "conduitDwRate", "conduitSwRate", "conduitCeRate", "conduitCeOverageRate", "googleLaborDropRate",
        "googleLaborBoreDrivewayRate", "googleLaborBoreSidewalkRate", "googleLaborBoreRoadBoreRate",
        "googleLaborConduitDwRate", "googleLaborConduitSwRate");
    private static final Map<String, Set<String>> ROWS = Map.of(
        "footageTiers", Set.of("fromFt", "toFt", "rate"),
        "items", Set.of("dropItemId", "rate", "name", "type"),
        "googleLaborItemRates", Set.of("dropItemId", "rate", "name"),
        "roleRates", Set.of("employeeRoleId", "dropRate", "boreRate", "employeeRoleName", "roleName"));

    record Result(int added, int replaced, int unchanged) {}

    private EntityRateCards() {}

    static void initialize(Connection db) throws SQLException {
        try (var statement = db.createStatement()) {
            // Earlier versions keyed every card to the WG link. Cards now also belong to local-only entities.
            boolean migrate = one(db, """
                SELECT 1 FROM pragma_foreign_key_list('entity_rate_cards') WHERE "table"='wgidig_entities'
                """) != null;
            if (migrate) statement.execute("ALTER TABLE entity_rate_cards RENAME TO entity_rate_cards_imported");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS entity_rate_cards (
                    entity_id INTEGER PRIMARY KEY REFERENCES entities(id),
                    document TEXT NOT NULL CHECK(json_valid(document)),
                    source_document TEXT CHECK(source_document IS NULL OR json_valid(source_document)))
                """);
            if (migrate) {
                statement.execute("""
                    INSERT INTO entity_rate_cards(entity_id,document,source_document)
                    SELECT entity_id,document,document FROM entity_rate_cards_imported
                    """);
                statement.execute("DROP TABLE entity_rate_cards_imported");
            }
        }
    }

    static ObjectNode read(Connection db, long entityId) throws SQLException {
        var record = one(db, "SELECT document FROM entity_rate_cards WHERE entity_id=?", entityId);
        if (record == null) return null;
        try {
            return (ObjectNode)JSON.readTree(record.get("document").textValue());
        } catch (JsonProcessingException ex) {
            throw new SQLException("Could not read the saved entity rate cards.", ex);
        }
    }

    static String canonical(ObjectNode card) {
        try {
            return JSON.writeValueAsString(card);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode an entity rate card.", ex);
        }
    }

    static String digest(ObjectNode card) {
        try {
            var data = JSON.writeValueAsBytes(card);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Could not compare saved entity rate cards.", ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static Map<Long, ObjectNode> indexed(JsonNode rows, String type) {
        require(rows != null && rows.isArray(), "Missing " + type + " records.");
        Map<Long, ObjectNode> byId = new LinkedHashMap<>();
        for (var row : rows) {
            require(row.isObject(), "Invalid " + type + " record.");
            long id = positiveId(row.get("id"), type);
            require(row.path("companyName").isTextual() && !row.get("companyName").textValue().isBlank(),
                "Missing " + type + " company name for ID " + id + ".");
            require(byId.putIfAbsent(id, (ObjectNode)row) == null, "Repeated " + type + " ID " + id + ".");
        }
        return byId;
    }

    private static long positiveId(JsonNode value, String field) {
        if (value != null && (value.isIntegralNumber() || value.isTextual())) {
            try {
                long id = Long.parseLong(value.asText());
                if (id > 0) return id;
            } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Invalid source ID in " + field + ".");
    }

    private static boolean blank(JsonNode value) {
        return value.isNull() || value.isTextual() && value.textValue().isBlank();
    }

    private static void numeric(JsonNode value, String field) {
        if (blank(value) || value.isNumber()) return;
        if (value.isTextual()) {
            try {
                new BigDecimal(value.textValue().strip());
                return;
            } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Unrecognized numeric value in " + field + ".");
    }

    private static void rememberLabel(JsonNode ref, String field, JsonNode available, ObjectNode used) {
        if (blank(ref)) return;
        String key = Long.toString(positiveId(ref, field));
        var label = available.get(key);
        require(label != null && label.isTextual() && !label.textValue().isBlank(),
            "Missing catalog label for " + field + " ID " + key + ".");
        used.set(key, label);
    }

    private static JsonNode collection(String field, JsonNode source, JsonNode items, JsonNode roles, ObjectNode card) {
        if (blank(source)) return source;
        JsonNode array;
        try {
            array = source.isTextual() ? JSON.readTree(source.textValue()) : source;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid encoded WG iDig collection in " + field + ".");
        }
        if (array.isNull()) return array;
        require(array.isArray(), "Expected an ordered collection in " + field + ".");
        for (var row : array) {
            require(row.isObject(), "Unrecognized row in " + field + ".");
            for (var cell : row.properties()) {
                var key = cell.getKey();
                var value = cell.getValue();
                // Technical row IDs are kept as provenance, without becoming payable rates.
                if (key.equals("id") || key.equals("contractingEntityId")) {
                    if (!blank(value)) positiveId(value, field + "." + key);
                    continue;
                }
                require(ROWS.get(field).contains(key), "Review additional WG iDig field " + field + "." + key + ".");
                if (key.equals("dropItemId")) rememberLabel(value, key, items, card.withObject("/itemNames"));
                else if (key.equals("employeeRoleId")) rememberLabel(value, key, roles, card.withObject("/roleNames"));
                else if (key.equals("name") || key.equals("type") || key.equals("roleName") || key.equals("employeeRoleName"))
                    require(blank(value) || value.isTextual(), "Unrecognized label in " + field + ".");
                else numeric(value, field + "." + key);
            }
        }
        return array;
    }

    private static ObjectNode normalize(ObjectNode detail, JsonNode items, JsonNode roles) {
        var card = JSON.createObjectNode();
        card.putObject("itemNames");
        card.putObject("roleNames");
        for (var field : detail.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (ROWS.containsKey(key)) value = collection(key, value, items, roles, card);
            else if (AMOUNTS.contains(key) || COORDINATES.contains(key)) numeric(value, key);
            else if (TEXT.contains(key)) require(blank(value) || value.isTextual(), "Unrecognized value in " + key + ".");
            else if (key.equals("lunchProvided")) require(blank(value) || value.isBoolean(), "Unrecognized lunch setting.");
            else if (!key.equals("id")) throw new IllegalArgumentException("Review additional WG iDig entity field " + key + ".");
            card.set(key, value);
        }
        return card;
    }

    private static long linkedEntity(Connection db, long sourceId, String sourceName) throws SQLException {
        String displayName = sourceName.strip();
        var mapped = one(db, """
            SELECT e.id,e.name FROM entities e JOIN wgidig_entities w ON w.entity_id=e.id WHERE w.wgidig_id=?
            """, sourceId);
        if (mapped != null) {
            require(mapped.get("name").textValue().strip().equals(displayName), "Review local name for WG iDig entity ID " + sourceId + ".");
            return mapped.get("id").longValue();
        }
        var candidate = one(db, """
            SELECT e.id,e.name,w.wgidig_id FROM entities e LEFT JOIN wgidig_entities w ON w.entity_id=e.id
            """ + " WHERE " + nameEquals(db, "e.name"), displayName);
        long id;
        if (candidate == null) {
            id = insertId(db, "INSERT INTO entities(name) VALUES(?)", displayName);
        } else {
            require(displayName.equals(candidate.get("name").textValue()) && !candidate.hasNonNull("wgidig_id"),
                "Conflicting local entity for WG iDig ID " + sourceId + ".");
            id = candidate.get("id").longValue();
        }
        change(db, "INSERT INTO wgidig_entities(entity_id,wgidig_id) VALUES(?,?)", id, sourceId);
        return id;
    }

    static Result importCatalog(Connection db, ObjectNode manifest) throws SQLException {
        var listed = indexed(manifest.get("listed"), "listed entity");
        var details = indexed(manifest.get("entities"), "entity detail");
        require(!listed.isEmpty() && listed.keySet().equals(details.keySet()), "Entity list and detail IDs do not match.");
        var items = manifest.path("itemNames");
        var roles = manifest.path("roleNames");
        require(items.isObject() && roles.isObject(), "Missing item or role lookup.");
        var expected = manifest.path("previousDigests");
        require(expected.isMissingNode() || expected.isObject(), "Invalid expected card digests.");
        return transaction(db, true, () -> {
            int added = 0, replaced = 0, unchanged = 0;
            for (var entry : details.entrySet()) {
                long id = entry.getKey();
                ObjectNode detail = entry.getValue();
                String name = detail.get("companyName").textValue();
                require(name.equals(listed.get(id).get("companyName").textValue()), "Entity detail name differs from its list entry for ID " + id + ".");
                long localId = linkedEntity(db, id, name);
                var card = normalize(detail, items, roles);
                validatePayroll(db, localId, card);
                String json = canonical(card);
                var current = read(db, localId);
                if (current == null) {
                    require(!expected.has(Long.toString(id)), "The expected saved card is missing for entity ID " + id + ".");
                    change(db, "INSERT INTO entity_rate_cards(entity_id,document,source_document) VALUES(?,?,?)", localId, json, json);
                    added++;
                } else if (json.equals(canonical(current))) {
                    unchanged++;
                } else {
                    require(digest(current).equals(expected.path(Long.toString(id)).asText()),
                        "Saved card changed for entity ID " + id + "; compare it before replacing.");
                    change(db, "UPDATE entity_rate_cards SET document=?,source_document=? WHERE entity_id=?", json, json, localId);
                    replaced++;
                }
                require(json.equals(canonical(read(db, localId))), "Post-import comparison failed for entity ID " + id + ".");
            }
            return new Result(added, replaced, unchanged);
        });
    }

    static void validatePayroll(Connection db, long entityId, ObjectNode card) throws SQLException {
        var keys = EntityRateFields.amounts(card).keySet();
        var available = new HashSet<>(PayrollInputs.labels(db).keySet());
        available.addAll(keys);
        for (var rate : rows(db, "SELECT rate_key FROM entity_payroll_rates WHERE entity_id=?", entityId)) {
            String key = rate.path("rate_key").textValue();
            require(!keys.contains(key), "An existing payroll rate uses the rate card formula key " + key + ".");
            available.add(key);
        }
        var roadBoreRules = RoadBoreRules.forCard(card);
        for (var formula : rows(db, "SELECT expression FROM entity_payroll_formulas WHERE entity_id=?", entityId))
            PayrollExpression.validate(formula.get("expression").textValue(), available, roadBoreRules);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Supply the verified entity manifest JSON file.");
        JsonNode captured = JSON.readTree(Path.of(args[0]).toFile());
        require(captured.isObject(), "Unexpected entity manifest.");
        try (var db = Db.open()) {
            Db.initialize(db);
            Result result = importCatalog(db, (ObjectNode)captured);
            System.out.printf("Verified %d imported, %d replaced and %d already matching entity cards.%n",
                result.added(), result.replaced(), result.unchanged());
        }
    }
}
