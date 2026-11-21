package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import static fieldops.StoreSql.*;

final class PayrollRuns {
    private static final Logger LOG = Logger.getLogger(PayrollRuns.class.getName());
    static final BigDecimal MAX_CHECK = new BigDecimal("999999999.99");
    private static ScheduledExecutorService timer;

    private PayrollRuns() {}

    static void initialize(Connection db) throws SQLException {
        try (var sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS payroll_statements (
                    pay_date TEXT NOT NULL, person_id INTEGER NOT NULL REFERENCES people,
                    generated_at TEXT NOT NULL, payload TEXT NOT NULL, mailing_address TEXT NOT NULL,
                    check_number INTEGER UNIQUE CHECK(check_number BETWEEN 1 AND 999999999),
                    PRIMARY KEY(pay_date,person_id))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS payroll_schedule (
                    id INTEGER PRIMARY KEY CHECK(id=1), last_friday TEXT NOT NULL)
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS payroll_day_reviews (
                    pay_date TEXT NOT NULL, person_id INTEGER NOT NULL REFERENCES people, review_key TEXT NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('include','exclude')),
                    reviewed_by INTEGER NOT NULL REFERENCES people, reviewed_at TEXT NOT NULL,
                    PRIMARY KEY(pay_date,person_id,review_key))
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS check_print_settings (
                    id INTEGER PRIMARY KEY CHECK(id=1), x_tenths INTEGER NOT NULL, y_tenths INTEGER NOT NULL)
                """);
        }
    }

    static LocalDate latestFriday() {
        return LocalDate.parse(Db.today()).with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
    }

    private static void friday(LocalDate date) {
        if (date == null || date.getDayOfWeek() != DayOfWeek.FRIDAY)
            throw new Problem(422, "Select a Friday check date.");
    }

    static ObjectNode view(Connection db, LocalDate date) throws Exception {
        friday(date);
        return transaction(db, false, () -> merge(db, date, WeeklyPayroll.calculate(db, date)));
    }

    static ObjectNode generate(Connection db, LocalDate date) throws Exception {
        friday(date);
        if (date.isAfter(latestFriday())) throw new Problem(422, "Checks can be generated on or after the selected Friday.");
        return transaction(db, true, () -> {
            var calculated = WeeklyPayroll.calculate(db, date);
            freezeReady(db, date, calculated);
            return merge(db, date, calculated);
        });
    }

    private static void freezeReady(Connection db, LocalDate date, ObjectNode calculated) throws SQLException {
        String generatedAt = Instant.now().toString();
        for (var person : calculated.path("workers")) {
            if (!"ready".equals(person.path("status").asText()) || !person.hasNonNull("gross")) continue;
            change(db, """
                INSERT INTO payroll_statements(pay_date,person_id,generated_at,payload,mailing_address)
                SELECT ?,id,?,?,coalesce(address,'') FROM people WHERE id=?
                ON CONFLICT(pay_date,person_id) DO NOTHING
                """, date.toString(), generatedAt, person.toString(), person.get("person_id").asLong());
        }
    }

    private static ObjectNode merge(Connection db, LocalDate date, ObjectNode calculated) throws Exception {
        var workers = new LinkedHashMap<Long,ObjectNode>();
        for (var row : calculated.path("workers")) workers.put(row.path("person_id").asLong(), (ObjectNode) row);
        int issued = 0;
        String latest = "";
        for (var saved : rows(db, "SELECT * FROM payroll_statements WHERE pay_date=? ORDER BY person_id", date.toString())) {
            long id = saved.get("person_id").asLong();
            ObjectNode statement = (ObjectNode) App.JSON.readTree(saved.get("payload").textValue());
            var current = workers.get(id);
            boolean changed = current == null || !statement.equals((left, right) ->
                left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue()) :
                    (left.equals(right) ? 0 : 1), current);
            statement.put("statement_status", "generated").put("source_changed", changed);
            for (String field : new String[]{"check_number","generated_at","mailing_address"}) statement.set(field, saved.get(field));
            workers.put(id, statement);
            String stamp = saved.get("generated_at").textValue();
            if (latest.compareTo(stamp) < 0) latest = stamp;
            issued++;
        }
        var ordered = new ArrayList<>(workers.values());
        ordered.sort(Comparator.<ObjectNode,String>comparing(w -> w.path("name").asText(), String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(w -> w.path("person_id").asLong()));
        var result = calculated.putArray("workers");
        for (var worker : ordered) {
            if (!worker.has("statement_status")) worker.put("statement_status", "draft");
            result.add(worker);
        }
        if (!latest.isEmpty()) calculated.put("generated_at", latest);
        return calculated.put("run_status", issued == 0 ? "draft" : issued == workers.size() ? "generated" : "partial");
    }

    static void reviewDay(Connection db, LocalDate date, long personId, String key, String previous,
                          String decision, long adminId) throws Exception {
        friday(date);
        if (!java.util.Set.of("include", "exclude").contains(decision) || key == null || !key.matches("[0-9a-f]{64}"))
            throw new Problem(422, "Select a reported day and a payroll review decision.");
        transaction(db, true, () -> {
            if (one(db, "SELECT id FROM people WHERE id=? AND is_admin=1", adminId) == null)
                throw new Problem(403, "Administrator access required.");
            if (one(db, "SELECT 1 FROM payroll_statements WHERE pay_date=? AND person_id=?", date.toString(), personId) != null)
                throw new Problem(409, "This contractor’s statement has already been generated.");
            var run = WeeklyPayroll.calculate(db, date);
            boolean found = false;
            for (var worker : run.path("workers")) {
                if (worker.path("person_id").asLong() == personId) {
                    for (var detail : worker.path("details"))
                        if (key.equals(detail.path("review_key").asText())) found = true;
                }
            }
            if (!found) throw new Problem(409, "The reported work changed. Reopen the statement to review the current day.");
            var old = one(db, "SELECT decision FROM payroll_day_reviews WHERE pay_date=? AND person_id=? AND review_key=?",
                date.toString(), personId, key);
            String saved = old == null ? "none" : old.path("decision").asText();
            if (!saved.equals(previous)) throw new Problem(409, "Another administrator reviewed this day. Reload the statement.");
            change(db, """
                INSERT INTO payroll_day_reviews(pay_date,person_id,review_key,decision,reviewed_by,reviewed_at) VALUES(?,?,?,?,?,?)
                ON CONFLICT(pay_date,person_id,review_key) DO UPDATE SET
                    decision=excluded.decision,reviewed_by=excluded.reviewed_by,reviewed_at=excluded.reviewed_at
                """, date.toString(), personId, key, decision, adminId, Instant.now().toString());
            return null;
        });
    }

    static void numberChecks(Connection db, LocalDate date, Long personId, long firstNumber) throws Exception {
        friday(date);
        if (firstNumber < 1 || firstNumber > 999999999) throw new Problem(422, "Enter a check number from 1 to 999999999.");
        transaction(db, true, () -> {
            var eligible = new ArrayList<ObjectNode>();
            boolean existing = false;
            String name = postgres(db) ? "(CAST(payload AS json)->>'name') COLLATE \"C\"" : "json_extract(payload,'$.name')";
            for (var row : rows(db, """
                    SELECT person_id,payload,check_number FROM payroll_statements WHERE pay_date=?
                    ORDER BY lower(%s),person_id
                    """.formatted(name), date.toString())) {
                if (personId != null && row.get("person_id").asLong() != personId) continue;
                if (row.hasNonNull("check_number")) {
                    existing = true;
                    if (personId != null && row.path("check_number").asLong() != firstNumber)
                        throw new Problem(409, "This check already has a number. Reload it before printing.");
                    continue;
                }
                var gross = App.JSON.readTree(row.get("payload").textValue()).get("gross").decimalValue();
                if (gross.signum() > 0 && gross.compareTo(MAX_CHECK) <= 0) eligible.add((ObjectNode)row);
            }
            if (eligible.isEmpty() && !existing) throw new Problem(422, "There are no generated checks eligible for numbering.");
            if (firstNumber + eligible.size() - 1 > 999999999L) throw new Problem(422, "This range exceeds the maximum check number.");
            long next = firstNumber;
            for (var worker : eligible) {
                uniqueChange(db, "A check in that paper number range has already been assigned. Enter an unused range.",
                    "UPDATE payroll_statements SET check_number=? WHERE pay_date=? AND person_id=? AND check_number IS NULL",
                    next++, date.toString(), worker.path("person_id").asLong());
            }
            return null;
        });
    }

    static ObjectNode printSettings(Connection db) throws SQLException {
        var saved = one(db, "SELECT x_tenths,y_tenths FROM check_print_settings WHERE id=1");
        var settings = App.JSON.createObjectNode();
        for (String axis : new String[]{"x","y"})
            settings.put(axis + "_mm", BigDecimal.valueOf(saved == null ? 0 : saved.path(axis + "_tenths").asLong(), 1));
        return settings;
    }

    static void savePrintSettings(Connection db, BigDecimal x, BigDecimal y) throws SQLException {
        for (var value : new BigDecimal[]{x,y}) {
            if (value == null || value.abs().compareTo(BigDecimal.valueOf(25)) > 0 || value.stripTrailingZeros().scale() > 1)
                throw new Problem(422, "Enter an offset from -25 to 25 mm, in tenths of a millimeter.");
        }
        change(db, """
            INSERT INTO check_print_settings(id,x_tenths,y_tenths) VALUES(1,?,?)
            ON CONFLICT(id) DO UPDATE SET x_tenths=excluded.x_tenths,y_tenths=excluded.y_tenths
            """, x.movePointRight(1).intValueExact(), y.movePointRight(1).intValueExact());
    }

    static void runDue(Connection db, LocalDate today) throws Exception {
        LocalDate due = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        var marker = one(db, "SELECT last_friday FROM payroll_schedule WHERE id=1");
        LocalDate next = marker == null ? due : LocalDate.parse(marker.get("last_friday").asText()).plusWeeks(1);
        for (LocalDate week = next; !week.isAfter(due); week = week.plusWeeks(1)) {
            LocalDate date = week;
            transaction(db, true, () -> {
                freezeReady(db, date, WeeklyPayroll.calculate(db, date));
                change(db, """
                    INSERT INTO payroll_schedule(id,last_friday) VALUES(1,?)
                    ON CONFLICT(id) DO UPDATE SET last_friday=excluded.last_friday
                    WHERE payroll_schedule.last_friday<excluded.last_friday
                    """, date.toString());
                return null;
            });
        }
    }

    static void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "field-routes-weekly-payroll");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try (var db = Db.open()) { runDue(db, LocalDate.parse(Db.today())); }
            catch (Exception e) { LOG.log(Level.SEVERE, "Friday payroll generation failed. The scheduler will retry.", e); }
        }, 0, 5, TimeUnit.MINUTES);
    }

    static void stop() {
        if (timer != null) timer.shutdownNow();
    }
}
