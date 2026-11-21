package fieldops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Offline migration utility. Both the import and its independent verification use a single source snapshot. */
public final class PostgresImport {
    private static final String SCHEMA = PgSchema.NAME;
    // The standalone WG iDig roster import added these only on sites which ran it.
    private static final Set<String> OPTIONAL = Set.of("wgidig_roster_import", "wgidig_roster_links");

    private PostgresImport() {}

    public static void main(String[] args) {
        if (args.length != 2 || !Set.of("copy", "verify").contains(args[0])) {
            System.err.println("Usage: fieldops.PostgresImport copy|verify /path/to/fieldops.sqlite3");
            System.exit(2);
        }
        try (var target = Db.openPostgres()) {
            migrate(Path.of(args[1]), target, args[0].equals("copy"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        } catch (SQLException ex) {
            // Driver errors can contain an entire failing row, including credentials or personal information.
            System.err.println("Database operation failed (SQL state " + ex.getSQLState() + "). Check the connection, permissions and schema.");
            System.exit(1);
        }
    }

    static void migrate(Path file, Connection target, boolean copy) throws SQLException {
        check(Files.isRegularFile(file), "The SQLite source must be an existing database file.");
        String url = "jdbc:sqlite:" + file.toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
        try (var source = DriverManager.getConnection(url); var config = source.createStatement()) {
            config.execute("PRAGMA query_only=ON");
            source.setAutoCommit(false);
            try (var result = config.executeQuery("PRAGMA integrity_check")) {
                check(result.next() && "ok".equals(result.getString(1)), "SQLite integrity check failed.");
            }
            try (var result = config.executeQuery("PRAGMA foreign_key_check")) {
                check(!result.next(), "The SQLite source has a broken foreign key.");
            }
            if (!copy) {
                target.setReadOnly(true);
                target.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
            target.setAutoCommit(false);
            try {
                transfer(source, target, copy);
                target.commit();
            } catch (SQLException | RuntimeException ex) {
                try { target.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
        }
        System.out.println(copy ? "Verified import committed." : "Independent read-only verification passed.");
    }

    private static void transfer(Connection source, Connection target, boolean copy) throws SQLException {
        var sourceNames = strings(source, "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'sqlite_*'");
        check(sourceNames.containsAll(Set.of("people", "entities", "routes", "jobs", "photos")), "Source is missing core Field Routes tables.");
        try (var sql = target.createStatement()) {
            if (copy) {
                sql.execute("SET LOCAL lock_timeout='15s'");
                sql.execute("SELECT pg_advisory_xact_lock(hashtext('fieldroutes'),hashtext('database import'))");
                try (var existing = sql.executeQuery("SELECT to_regclass('fieldroutes.people') IS NOT NULL")) {
                    existing.next();
                    if (!existing.getBoolean(1)) PgSchema.createTables(target);
                }
            }
            check(strings(target, "SELECT current_schema()").equals(Set.of(SCHEMA)), "A dedicated fieldroutes schema is required.");
            try (var exposed = sql.executeQuery("""
                    SELECT 1 FROM pg_roles WHERE rolname IN ('anon','authenticated')
                    AND has_schema_privilege(oid,'fieldroutes','USAGE')
                    UNION ALL
                    SELECT 1 FROM pg_namespace n, aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) acl
                    WHERE n.nspname='fieldroutes' AND acl.grantee=0 AND acl.privilege_type='USAGE'
                    """)) {
                check(!exposed.next(), "The fieldroutes schema must be private before importing or verifying contractor data.");
            }
            var targetNames = strings(target, "SELECT table_name FROM information_schema.tables WHERE table_schema='fieldroutes' AND table_type='BASE TABLE'");
            var required = new TreeSet<>(targetNames);
            var optionalTables = new TreeSet<>(OPTIONAL);
            // Pre-Mainline SQLite backups contain none of the project tables.
            if (java.util.Collections.disjoint(sourceNames, MainlineSchema.TABLES))
                optionalTables.addAll(MainlineSchema.TABLES);
            required.removeAll(optionalTables);
            check(targetNames.containsAll(sourceNames) && sourceNames.containsAll(required),
                "The source and destination table sets differ. Check the schema version and upgrade a backup of the source first.");
            if (copy) {
                // Hold the locks through verification and commit so the check covers concurrent application starts as well.
                sql.execute("LOCK TABLE " + String.join(",", targetNames.stream().map(PostgresImport::qualified).toList()) + " IN ACCESS EXCLUSIVE MODE");
                for (String table : targetNames) requireEmpty(target, table);
            }
            for (String optional : optionalTables) {
                if (targetNames.contains(optional) && !sourceNames.contains(optional)) requireEmpty(target, optional);
            }
            long total = 0;
            for (String table : parentTablesFirst(source, sourceNames)) {
                try {
                    var columns = columns(source, target, table);
                    if (copy) {
                        copyRows(source, target, table, columns);
                        restartIdentities(source, target, table, columns);
                    }
                    long count = compareRows(source, target, table, columns);
                    System.out.printf("%-28s %,d verified%n", table, count);
                    total += count;
                } catch (SQLException ex) {
                    throw new IllegalStateException("Could not process table " + table + " (SQL state " + ex.getSQLState() + ").", ex);
                }
            }
            System.out.printf("%d source tables and %,d rows matched, including every column.%n", sourceNames.size(), total);
        }
    }

    private static void requireEmpty(Connection db, String table) throws SQLException {
        try (var stmt = db.createStatement(); var rows = stmt.executeQuery("SELECT 1 FROM " + qualified(table) + " LIMIT 1")) {
            check(!rows.next(), "Destination table " + table + " contains data. Use a fresh PostgreSQL database for a new copy.");
        }
    }

    private static List<String> parentTablesFirst(Connection db, Set<String> tables) throws SQLException {
        var pending = new TreeMap<String, Set<String>>();
        for (String name : tables) {
            var dependencies = new TreeSet<String>();
            try (var stmt = db.createStatement(); var keys = stmt.executeQuery("PRAGMA foreign_key_list(" + quote(name) + ")")) {
                while (keys.next()) dependencies.add(keys.getString("table"));
            }
            pending.put(name, dependencies);
        }
        var ordered = new LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            String table = pending.entrySet().stream().filter(e -> ordered.containsAll(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalStateException("Unsupported dependency cycle or missing parent table."));
            ordered.add(table);
            pending.remove(table);
        }
        return List.copyOf(ordered);
    }

    private record Column(String name, String type, int keyPosition, boolean identity, String sourceExpression) {
        boolean integral() { return Set.of("bigint", "integer", "smallint").contains(type); }
    }

    private static List<Column> columns(Connection source, Connection target, String table) throws SQLException {
        var primaryKeys = new HashMap<String, Integer>();
        try (var stmt = source.createStatement(); var row = stmt.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (row.next()) primaryKeys.put(row.getString("name"), row.getInt("pk"));
        }
        var result = new ArrayList<Column>();
        try (var stmt = target.prepareStatement("""
                SELECT column_name,data_type,is_identity FROM information_schema.columns
                WHERE table_schema=? AND table_name=? ORDER BY ordinal_position
                """)) {
            stmt.setString(1, SCHEMA);
            stmt.setString(2, table);
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    Integer key = primaryKeys.remove(name);
                    String sourceExpression = quote(name);
                    // Legacy SQLite projects used display text only. A historical crew assignment was explicit.
                    if (key == null && table.equals("mainline_projects") && name.equals("supervisor_person_id")) {
                        key = 0;
                        sourceExpression = "NULL";
                    } else if (key == null && ((table.equals("mainline_daily_members") && Set.of("supervisor_only","removed").contains(name)) ||
                                              (table.equals("mainline_dailies") && name.equals("crew_revision")))) {
                        key = 0;
                        sourceExpression = "0";
                    }
                    check(key != null, "Source is missing " + table + "." + name + ". Upgrade a backup of the source first.");
                    result.add(new Column(name, rows.getString(2), key, rows.getString(3).equals("YES"), sourceExpression));
                }
            }
        }
        check(primaryKeys.isEmpty() && !result.isEmpty(), "Destination columns differ for " + table + ".");
        check(result.stream().anyMatch(c -> c.keyPosition > 0), "Cannot verify a table without a primary key: " + table);
        return result;
    }

    private static void copyRows(Connection source, Connection target, String table, List<Column> columns) throws SQLException {
        String names = columnNames(columns);
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        try (var read = source.createStatement(); var rows = read.executeQuery("SELECT " + sourceNames(columns) + " FROM " + quote(table));
             var insert = target.prepareStatement("INSERT INTO " + qualified(table) + " (" + names + ") VALUES (" + placeholders + ")")) {
            int batchSize = columns.stream().anyMatch(c -> c.type.equals("bytea")) ? 1 : 256;
            int pending = 0;
            while (rows.next()) {
                for (int i = 0; i < columns.size(); i++) insert.setObject(i + 1, value(rows, i + 1, columns.get(i)));
                insert.addBatch();
                if (++pending == batchSize) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) insert.executeBatch();
        }
    }

    private static void restartIdentities(Connection source, Connection target, String table, List<Column> columns) throws SQLException {
        for (Column column : columns) {
            if (!column.identity) continue;
            long last;
            try (var stmt = target.createStatement();
                 var result = stmt.executeQuery("SELECT coalesce(max(" + quote(column.name) + "),0) FROM " + qualified(table))) {
                result.next();
                last = Math.max(0, result.getLong(1));
            }
            // Keep the high-water mark for every SQLite AUTOINCREMENT table, including removed media IDs.
            try (var stmt = source.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")) {
                stmt.setString(1, table);
                try (var seq = stmt.executeQuery()) {
                    if (seq.next()) last = Math.max(last, seq.getLong(1));
                }
            }
            check(last < Long.MAX_VALUE, "No new IDs can be allocated for " + table + ".");
            try (var stmt = target.createStatement()) {
                // ALTER ... RESTART is transactional; setval() would survive a failed import.
                stmt.execute("ALTER TABLE " + qualified(table) + " ALTER COLUMN " + quote(column.name) + " RESTART WITH " + (last + 1));
            }
        }
    }

    private static long compareRows(Connection source, Connection target, String table, List<Column> columns) throws SQLException {
        String names = columnNames(columns);
        var keys = columns.stream().filter(c -> c.keyPosition > 0)
            .sorted(java.util.Comparator.comparingInt(Column::keyPosition)).toList();
        String sqlite = "SELECT " + sourceNames(columns) + " FROM " + quote(table) + " ORDER BY " + keyOrder(keys, "BINARY");
        String postgres = "SELECT " + names + " FROM " + qualified(table) + " ORDER BY " + keyOrder(keys, "\"C\"");
        long count = 0;
        try (var left = source.createStatement(); var right = target.createStatement()) {
            right.setFetchSize(columns.stream().anyMatch(c -> c.type.equals("bytea")) ? 1 : 256);
            try (var a = left.executeQuery(sqlite); var b = right.executeQuery(postgres)) {
                while (a.next()) {
                    check(b.next(), "Destination has fewer rows in " + table + ".");
                    for (int i = 0; i < columns.size(); i++) {
                        Column col = columns.get(i);
                        check(Objects.deepEquals(value(a, i + 1, col), value(b, i + 1, col)),
                            "Different data in " + table + "." + col.name + " at ordered row " + (count + 1) + ".");
                    }
                    count++;
                }
                check(!b.next(), "Destination has extra rows in " + table + ".");
            }
        }
        return count;
    }

    private static String keyOrder(List<Column> keys, String collation) {
        return String.join(",", keys.stream().map(c -> quote(c.name) + (c.type.equals("text") ? " COLLATE " + collation : "")).toList());
    }

    private static String columnNames(List<Column> columns) {
        return String.join(",", columns.stream().map(c -> quote(c.name)).toList());
    }

    private static String sourceNames(List<Column> columns) {
        return String.join(",", columns.stream().map(Column::sourceExpression).toList());
    }

    private static Object value(ResultSet row, int index, Column column) throws SQLException {
        Object raw = row.getObject(index);
        if (raw == null) return null;
        if (column.integral() && (raw instanceof Integer || raw instanceof Long || raw instanceof Short)) return ((Number) raw).longValue();
        if (column.type.equals("double precision") && raw instanceof Number number) return number.doubleValue();
        if (column.type.equals("bytea") && raw instanceof byte[]) return raw;
        if (column.type.equals("text") && raw instanceof String) return raw;
        throw new IllegalStateException("Unsupported or inconsistent value type for column " + column.name + " (" + column.type + ").");
    }

    private static Set<String> strings(Connection db, String sql) throws SQLException {
        var result = new TreeSet<String>();
        try (var stmt = db.createStatement(); var rows = stmt.executeQuery(sql)) {
            while (rows.next()) {
                String value = rows.getString(1);
                if (value != null) result.add(value);
            }
        }
        return result;
    }

    private static String qualified(String table) { return SCHEMA + "." + quote(table); }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
