package fieldops;

import java.sql.Connection;
import java.sql.SQLException;

/** On a pre-supervisor installation the project labels are retained. Only an explicit account assignment grants access. */
final class MainlineSupervisorSchema {
    private MainlineSupervisorSchema() {}

    static void initialize(Connection db) throws SQLException {
        boolean pg = StoreSql.postgres(db);
        try (var sql = db.createStatement()) {
            if (pg || !hasColumn(db, "mainline_projects", "supervisor_person_id"))
                sql.execute("ALTER TABLE mainline_projects ADD COLUMN " + (pg ? "IF NOT EXISTS " : "") +
                    "supervisor_person_id " + (pg ? "bigint" : "INTEGER") + " REFERENCES people(id)");
            if (pg || !hasColumn(db, "mainline_daily_members", "supervisor_only"))
                sql.execute("ALTER TABLE mainline_daily_members ADD COLUMN " + (pg ? "IF NOT EXISTS " : "") +
                    "supervisor_only INTEGER NOT NULL DEFAULT 0 CHECK(supervisor_only IN (0,1))");
            sql.execute("CREATE INDEX IF NOT EXISTS mainline_projects_by_supervisor ON mainline_projects(supervisor_person_id,id)");
        }
    }

    private static boolean hasColumn(Connection db, String table, String column) throws SQLException {
        try (var sql = db.createStatement(); var rows = sql.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (column.equals(rows.getString("name"))) return true;
            return false;
        }
    }
}
