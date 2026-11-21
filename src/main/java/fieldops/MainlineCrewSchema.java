package fieldops;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive upgrades for existing Mainline projects and daily crews. */
final class MainlineCrewSchema {
    private MainlineCrewSchema() {}

    static void initialize(Connection db) throws SQLException {
        boolean pg = StoreSql.postgres(db);
        try (var sql = db.createStatement()) {
            addColumn(db, pg, "mainline_projects", "supervisor_person_id", (pg ? "bigint" : "INTEGER") + " REFERENCES people(id)");
            addColumn(db, pg, "mainline_daily_members", "supervisor_only", "INTEGER NOT NULL DEFAULT 0 CHECK(supervisor_only IN (0,1))");
            addColumn(db, pg, "mainline_dailies", "crew_revision", (pg ? "bigint" : "INTEGER") + " NOT NULL DEFAULT 0");
            addColumn(db, pg, "mainline_daily_members", "removed", "INTEGER NOT NULL DEFAULT 0 CHECK(removed IN (0,1))");
            sql.execute("CREATE INDEX IF NOT EXISTS mainline_projects_by_supervisor ON mainline_projects(supervisor_person_id,id)");
        }
    }

    private static void addColumn(Connection db, boolean pg, String table, String column, String type) throws SQLException {
        if (!pg && hasColumn(db, table, column)) return;
        try (var sql = db.createStatement()) {
            sql.execute("ALTER TABLE " + table + " ADD COLUMN " + (pg ? "IF NOT EXISTS " : "") + column + " " + type);
        }
    }

    private static boolean hasColumn(Connection db, String table, String column) throws SQLException {
        try (var sql = db.createStatement(); var rows = sql.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (column.equals(rows.getString("name"))) return true;
            return false;
        }
    }
}
