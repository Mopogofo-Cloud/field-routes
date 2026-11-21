package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

final class StoreSql {
    private StoreSql() {}

    interface Work<T,E extends Exception> {
        T run() throws E;
    }

    static boolean postgres(Connection db) throws SQLException {
        return db.getMetaData().getDatabaseProductName().equals("PostgreSQL");
    }

    // These columns have a NOCASE index on SQLite and an ASCII lower() index on PostgreSQL.
    static String nameEquals(Connection db, String column) throws SQLException {
        return postgres(db) ? "lower(" + column + ")=lower(CAST(? AS text) COLLATE \"C\")" : column + "=?";
    }

    static <T,E extends Exception> T transaction(Connection db, boolean write, Work<T,E> work) throws SQLException,E {
        try (Statement control = db.createStatement()) {
            boolean pg = postgres(db);
            if (pg) control.execute(write ? "BEGIN ISOLATION LEVEL READ COMMITTED" : "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY");
            else control.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            try {
                // Route revisions, payroll snapshots, and vehicle reservations assume serial app writers.
                // READ COMMITTED lets the first business query see changes made by the previous writer.
                if (pg && write) {
                    control.execute("SET LOCAL lock_timeout = '15s'");
                    control.execute("SELECT pg_advisory_xact_lock(1179799636, 1)");
                }
                T result = work.run();
                control.execute("COMMIT");
                return result;
            } catch (Exception | Error e) {
                try { control.execute("ROLLBACK"); }
                catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    static void bind(PreparedStatement stmt, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) stmt.setObject(i + 1, args[i]);
    }

    static int change(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement stmt = db.prepareStatement(sql)) {
            bind(stmt, args);
            return stmt.executeUpdate();
        }
    }

    static int uniqueChange(Connection db, String conflict, String sql, Object... args) throws SQLException {
        try {
            return change(db, sql, args);
        } catch (SQLException e) {
            throw handleConflict(e, conflict);
        }
    }

    private static SQLException handleConflict(SQLException e, String conflict) {
        if ("23505".equals(e.getSQLState()) || (e.getErrorCode() == 19 && e.getMessage().contains("UNIQUE")))
            throw new Problem(409, conflict);
        return e;
    }

    static long insertId(Connection db, String sql, Object... args) throws SQLException {
        try (var stmt = db.prepareStatement(sql + " RETURNING id")) {
            bind(stmt, args);
            try (var result = stmt.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing insert id");
                return result.getLong(1);
            }
        }
    }

    static long uniqueInsertId(Connection db, String conflict, String sql, Object... args) throws SQLException {
        try {
            return insertId(db, sql, args);
        } catch (SQLException e) {
            throw handleConflict(e, conflict);
        }
    }

    static ArrayNode rows(Connection db, String sql, Object... args) throws SQLException {
        ArrayNode result = App.JSON.createArrayNode();
        try (PreparedStatement stmt = db.prepareStatement(sql)) {
            bind(stmt, args);
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    ObjectNode row = result.addObject();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.set(md.getColumnLabel(i), App.JSON.valueToTree(rs.getObject(i)));
                    }
                }
            }
        }
        return result;
    }

    static ObjectNode one(Connection db, String sql, Object... args) throws SQLException {
        return (ObjectNode) rows(db, sql, args).get(0);
    }
}
