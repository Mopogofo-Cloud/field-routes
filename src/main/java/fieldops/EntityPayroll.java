package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import static fieldops.StoreInput.*;
import static fieldops.StoreSql.*;

final class EntityPayroll {
    private EntityPayroll() {}

    static void initialize(Connection db) throws SQLException {
        try (var sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS entity_payroll_config (
                    entity_id INTEGER PRIMARY KEY REFERENCES entities, revision INTEGER NOT NULL CHECK(revision>0))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS entity_payroll_rates (
                    entity_id INTEGER NOT NULL REFERENCES entity_payroll_config(entity_id) ON DELETE CASCADE,
                    position INTEGER NOT NULL, rate_key TEXT NOT NULL, label TEXT NOT NULL, unit TEXT NOT NULL,
                    amount_10000 INTEGER NOT NULL CHECK(amount_10000 BETWEEN 0 AND 10000000000000),
                    PRIMARY KEY(entity_id,position), UNIQUE(entity_id,rate_key))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS entity_payroll_formulas (
                    entity_id INTEGER NOT NULL REFERENCES entity_payroll_config(entity_id) ON DELETE CASCADE,
                    position INTEGER NOT NULL, label TEXT NOT NULL COLLATE NOCASE,
                    basis TEXT NOT NULL CHECK(basis IN ('daily','production')), expression TEXT NOT NULL,
                    footage_credit TEXT NOT NULL DEFAULT 'full' CHECK(footage_credit IN ('full','split')),
                    PRIMARY KEY(entity_id,position), UNIQUE(entity_id,basis,label))
                """);
            boolean hasCredit;
            try (var column = sql.executeQuery("SELECT 1 FROM pragma_table_info('entity_payroll_formulas') WHERE name='footage_credit'")) {
                hasCredit = column.next();
            }
            if (!hasCredit) sql.execute("ALTER TABLE entity_payroll_formulas ADD COLUMN footage_credit TEXT NOT NULL DEFAULT 'full' CHECK(footage_credit IN ('full','split'))");
        }
    }

    static ObjectNode load(Connection db, long entityId) throws SQLException {
        return transaction(db, false, () -> read(db, entityId));
    }

    private static ObjectNode header(Connection db, long entityId) throws SQLException {
        var row = one(db, """
            SELECT e.id AS entity_id,coalesce(p.revision,0) AS revision FROM entities e
            LEFT JOIN entity_payroll_config p ON p.entity_id=e.id WHERE e.id=?
            """, entityId);
        if (row == null) throw new Problem(404, "Contracting entity not found.");
        return row;
    }

    // Caller owns the transaction when reading an entire payroll run.
    static ObjectNode read(Connection db, long entityId) throws SQLException {
        var result = header(db, entityId);
        var rates = rows(db, """
            SELECT rate_key AS key,label,unit,amount_10000 FROM entity_payroll_rates
            WHERE entity_id=? ORDER BY position
            """, entityId);
        for (var node : rates) {
            var rate = (ObjectNode) node;
            long units = rate.remove("amount_10000").longValue();
            rate.put("amount", BigDecimal.valueOf(units, 4));
        }
        result.set("rates", rates);
        ObjectNode cards = EntityRateCards.read(db, entityId);
        if (cards != null) result.set("rate_cards", cards);
        result.set("formulas", rows(db, """
            SELECT label,basis,expression,footage_credit FROM entity_payroll_formulas WHERE entity_id=? ORDER BY position
            """, entityId));
        return result;
    }

    static ObjectNode save(Connection db, long entityId, ObjectNode input) throws SQLException {
        fields(input, "revision", "rates", "formulas");
        long revision = integer(input.get("revision"), "revision");
        if (revision < 0) throw invalid("revision");
        var rates = array(input, "rates", 0, 100);
        var formulas = array(input, "formulas", 0, 100);
        return transaction(db, true, () -> {
            if (header(db, entityId).get("revision").longValue() != revision)
                throw new Problem(409, "These payroll settings changed. Reload the current settings before saving.");
            change(db, """
                INSERT INTO entity_payroll_config(entity_id,revision) VALUES(?,1)
                ON CONFLICT(entity_id) DO UPDATE SET revision=entity_payroll_config.revision+1
                """, entityId);
            change(db, "DELETE FROM entity_payroll_formulas WHERE entity_id=?", entityId);
            change(db, "DELETE FROM entity_payroll_rates WHERE entity_id=?", entityId);
            var variableNames = new HashSet<>(PayrollInputs.labels(db).keySet());
            var card = EntityRateCards.read(db, entityId);
            variableNames.addAll(EntityRateFields.amounts(card).keySet());
            var roadBoreRules = RoadBoreRules.forCard(card);
            int position = 0;
            for (var entry : rates) {
                if (!(entry instanceof ObjectNode row)) throw invalid("rates");
                fields(row, "key", "label", "unit", "amount");
                String key = text(row, "key", 1, 64);
                if (!key.matches("[a-z][a-z0-9_]{0,63}"))
                    throw new Problem(422, "Rate keys must start with a lowercase letter and contain only lowercase letters, numbers or underscores.");
                if (key.startsWith("item_") || !variableNames.add(key))
                    throw new Problem(422, "Rate key “" + key + "” is already used or reserved.");
                String label = text(row, "label", 1, 120);
                String unit = row.has("unit") ? text(row, "unit", 0, 80) : "";
                long amount = amountUnits(row.get("amount"));
                change(db, """
                    INSERT INTO entity_payroll_rates(entity_id,position,rate_key,label,unit,amount_10000)
                    VALUES(?,?,?,?,?,?)
                    """, entityId, position++, key, label, unit, amount);
            }
            var labelsByBasis = new HashSet<String>();
            position = 0;
            for (var entry : formulas) {
                if (!(entry instanceof ObjectNode row)) throw invalid("formulas");
                fields(row, "label", "basis", "expression", "footage_credit");
                String label = text(row, "label", 1, 120);
                String basis = text(row, "basis", 1, 20);
                if (!Set.of("daily", "production").contains(basis)) throw invalid("basis");
                if (!labelsByBasis.add(basis + ":" + label.toLowerCase(Locale.ROOT)))
                    throw new Problem(422, "Each formula for the same pay basis needs a distinct name.");
                String expression = text(row, "expression", 1, 2000);
                String credit = row.has("footage_credit") ? text(row, "footage_credit", 1, 20) : "full";
                if (!Set.of("full", "split").contains(credit)) throw invalid("footage_credit");
                PayrollExpression.validate(expression, variableNames, roadBoreRules);
                change(db, """
                    INSERT INTO entity_payroll_formulas(entity_id,position,label,basis,expression,footage_credit)
                    VALUES(?,?,?,?,?,?)
                    """, entityId, position++, label, basis, expression, credit);
            }
            return read(db, entityId);
        });
    }

    static long amountUnits(JsonNode value) {
        if (value != null && (value.isNumber() || value.isTextual())) {
            try {
                BigDecimal amount = new BigDecimal(value.asText().strip()).stripTrailingZeros();
                if (amount.signum() >= 0 && amount.scale() <= 4 && amount.compareTo(BigDecimal.valueOf(1_000_000_000L)) <= 0)
                    return amount.movePointRight(4).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {}
        }
        throw new Problem(422, "Enter a rate from 0 to 1,000,000,000 dollars with up to four decimal places.");
    }
}
