package fieldops;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

final class Db {
    private Db() {}

    private static HikariDataSource pool;
    private static final String SEED_ITEMS = """
        WITH defaults(name) AS (VALUES ('Vault'),('Riser'),('Splicer'),('Caja casa'),('Road Bore'),('Survey'))
        INSERT INTO report_items(name) SELECT name FROM defaults d
        WHERE NOT EXISTS (SELECT 1 FROM report_items i WHERE lower(i.name)=lower(d.name) AND i.unit='')
        ON CONFLICT DO NOTHING
        """;

    private static final String ASSIGNMENT_COLUMNS = """
        (route_id INTEGER NOT NULL REFERENCES routes, person_id INTEGER NOT NULL REFERENCES people,
         name TEXT NOT NULL, role TEXT NOT NULL, pay_basis TEXT NOT NULL, rate_cents INTEGER,
         PRIMARY KEY(route_id,person_id))""";
    private static final List<String> JOB_COLUMNS = List.of(
        "last_edited_by INTEGER REFERENCES people", "last_edited_at TEXT",
        "stop_label TEXT NOT NULL DEFAULT '' CHECK(stop_label IN ('','drop','damage','survey'))",
        "urgent INTEGER NOT NULL DEFAULT 0 CHECK(urgent IN (0,1))",
        "tech_needed INTEGER CHECK(tech_needed IN (0,1))",
        "external_job_id TEXT NOT NULL DEFAULT ''",
        "conduits TEXT NOT NULL DEFAULT '[]'", "items TEXT NOT NULL DEFAULT '[]'",
        "comments TEXT NOT NULL DEFAULT ''",
        "outcome TEXT CHECK(outcome IN ('done','pending','cancel'))", "outcome_reason TEXT");

    private static final List<String> SCHEMA = List.of(
        """
        CREATE TABLE IF NOT EXISTS people (
            id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password_hash TEXT NOT NULL, name TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0,
            must_change_password INTEGER NOT NULL DEFAULT 0,
            role TEXT NOT NULL DEFAULT '', pay_basis TEXT CHECK(pay_basis IN ('daily','production')),
            rate_cents INTEGER, email TEXT, phone TEXT, address TEXT, start_date TEXT, status TEXT,
            wgidig_employee_id TEXT, wgidig_account_type TEXT, wgidig_availability TEXT)
        """,
        """
        CREATE TABLE IF NOT EXISTS sessions (
            token_hash TEXT PRIMARY KEY, person_id INTEGER NOT NULL REFERENCES people, expires INTEGER NOT NULL)
        """,
        "CREATE INDEX IF NOT EXISTS session_expiry ON sessions(expires)",
        "CREATE TABLE IF NOT EXISTS login_attempts (source TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS roles (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)",
        """
        CREATE TABLE IF NOT EXISTS report_items (
            id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE,
            unit TEXT NOT NULL DEFAULT '' COLLATE NOCASE, UNIQUE(name,unit))""",
        "CREATE TABLE IF NOT EXISTS entities (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)",
        """
        CREATE TABLE IF NOT EXISTS wgidig_entities (entity_id INTEGER PRIMARY KEY REFERENCES entities,
            wgidig_id INTEGER NOT NULL UNIQUE)
        """,
        """
        CREATE TABLE IF NOT EXISTS teams (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            entity_id INTEGER NOT NULL REFERENCES entities)
        """,
        """
        CREATE TABLE IF NOT EXISTS team_members (team_id INTEGER NOT NULL REFERENCES teams,
            person_id INTEGER NOT NULL REFERENCES people, PRIMARY KEY(team_id,person_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS routes (id INTEGER PRIMARY KEY, date TEXT NOT NULL,
            team_id INTEGER NOT NULL REFERENCES teams, team_name TEXT NOT NULL,
            entity_id INTEGER NOT NULL REFERENCES entities, entity_name TEXT NOT NULL,
            address_hash TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, UNIQUE(date,team_id,address_hash))
        """,
        "CREATE TABLE IF NOT EXISTS assignments " + ASSIGNMENT_COLUMNS,
        "CREATE INDEX IF NOT EXISTS person_routes ON assignments(person_id,route_id)",
        """
        CREATE TABLE IF NOT EXISTS jobs (id INTEGER PRIMARY KEY, route_id INTEGER NOT NULL REFERENCES routes,
            position INTEGER NOT NULL, address TEXT NOT NULL, feet_buried REAL, fiber_type TEXT,
            bores TEXT NOT NULL DEFAULT '[]', submitted_by INTEGER REFERENCES people, submitted_at TEXT,
            revision INTEGER NOT NULL DEFAULT 0, %s, UNIQUE(route_id,position))
        """.formatted(String.join(",", JOB_COLUMNS)),
        """
        CREATE TABLE IF NOT EXISTS job_removals (job_id INTEGER PRIMARY KEY REFERENCES jobs,
            position INTEGER NOT NULL, removed_at TEXT NOT NULL)
        """,
        """
        CREATE VIEW IF NOT EXISTS active_route_jobs AS SELECT j.* FROM jobs j
            WHERE NOT EXISTS(SELECT 1 FROM job_removals x WHERE x.job_id=j.id)
        """,
        """
        CREATE TABLE IF NOT EXISTS job_notes (id INTEGER PRIMARY KEY, job_id INTEGER NOT NULL REFERENCES jobs,
            author_id INTEGER NOT NULL REFERENCES people, author_name TEXT NOT NULL, text TEXT NOT NULL,
            created_at TEXT NOT NULL)
        """,
        "CREATE INDEX IF NOT EXISTS job_notes_by_job ON job_notes(job_id,id)",
        """
        CREATE TABLE IF NOT EXISTS photos (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL
            REFERENCES jobs, mime TEXT NOT NULL, content BLOB NOT NULL)
        """,
        "CREATE INDEX IF NOT EXISTS photos_job ON photos(job_id)",
        """
        CREATE TABLE IF NOT EXISTS photo_objects (
            photo_id INTEGER PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE,
            destination TEXT NOT NULL, object_key TEXT NOT NULL,
            byte_count INTEGER NOT NULL CHECK(byte_count BETWEEN 1 AND 12582912), sha256 TEXT NOT NULL,
            UNIQUE(destination,object_key))
        """,
        """
        CREATE TABLE IF NOT EXISTS photo_cleanup (
            destination TEXT NOT NULL,object_key TEXT NOT NULL,eligible_at INTEGER NOT NULL,
            committable INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(destination,object_key))
        """,
        "CREATE INDEX IF NOT EXISTS photo_cleanup_due ON photo_cleanup(destination,eligible_at)",
        """
        CREATE TRIGGER IF NOT EXISTS collect_removed_photo AFTER DELETE ON photo_objects
        BEGIN
            INSERT INTO photo_cleanup(destination,object_key,eligible_at)
            VALUES(OLD.destination,OLD.object_key,unixepoch())
            ON CONFLICT(destination,object_key) DO UPDATE SET eligible_at=excluded.eligible_at,committable=0;
        END
        """
    );

    static String today() {
        var zone = ZoneId.of(System.getenv().getOrDefault("FIELDOPS_TZ", "America/New_York"));
        return LocalDate.now(zone).toString();
    }

    static Connection open() throws SQLException {
        if (System.getenv("FIELDOPS_POSTGRES_URL") != null) return postgresPool().getConnection();
        var directory = Path.of(System.getenv().getOrDefault("FIELDOPS_DATA_DIR", "data/fieldops"));
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException ex) {
            throw new SQLException("Could not open the field operations data directory.", ex);
        }
        var config = new Properties();
        config.setProperty("foreign_keys", "true");
        config.setProperty("busy_timeout", "15000");
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("fieldops.sqlite3").toAbsolutePath(), config);
    }

    private static String postgresUrl() throws SQLException {
        String url = System.getenv("FIELDOPS_POSTGRES_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new SQLException("Set FIELDOPS_POSTGRES_URL to a PostgreSQL JDBC URL.");
        }
        return url;
    }

    private static Properties postgresProperties() throws SQLException {
        var config = new Properties();
        config.setProperty("sslmode", "verify-full");
        config.setProperty("connectTimeout", "10");
        config.setProperty("ApplicationName", "Field Routes");
        String user = System.getenv("FIELDOPS_POSTGRES_USER");
        if (user != null) config.setProperty("user", user);
        String file = System.getenv("FIELDOPS_POSTGRES_PASSWORD_FILE");
        String password = System.getenv("FIELDOPS_POSTGRES_PASSWORD");
        if (file != null) {
            try {
                password = Files.readString(Path.of(file)).replaceFirst("\\r?\\n$", "");
            } catch (IOException e) {
                throw new SQLException("Could not read the PostgreSQL password file.", e);
            }
        }
        if (password != null) config.setProperty("password", password);
        return config;
    }

    // Migrations use a dedicated connection with a caller-owned transaction.
    static Connection openPostgres() throws SQLException {
        Connection db = DriverManager.getConnection(postgresUrl(), postgresProperties());
        try {
            db.setSchema(PgSchema.NAME);
            return db;
        } catch (SQLException e) {
            db.close();
            throw e;
        }
    }

    private static synchronized HikariDataSource postgresPool() throws SQLException {
        if (pool == null) {
            var config = new HikariConfig();
            config.setJdbcUrl(postgresUrl());
            config.setDataSourceProperties(postgresProperties());
            config.setSchema(PgSchema.NAME);
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(1);
            config.setPoolName("fieldroutes");
            var dataSource = new HikariDataSource(config);
            Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close, "fieldroutes-database-shutdown"));
            pool = dataSource;
        }
        return pool;
    }

    static void initialize() throws SQLException {
        today();
        try (var db = open()) {
            initialize(db);
        }
    }

    static void initialize(Connection db) throws SQLException {
        if (StoreSql.postgres(db)) {
            db.setSchema(PgSchema.NAME);
            StoreSql.transaction(db, true, () -> {
                try (var sql = db.createStatement()) {
                    boolean create;
                    try (var rows = sql.executeQuery("SELECT to_regclass('fieldroutes.people') IS NULL")) {
                        rows.next();
                        create = rows.getBoolean(1);
                    }
                    if (create) PgSchema.createTables(db);
                    sql.execute(SEED_ITEMS);
                }
                return null;
            });
            return;
        }
        try (var sql = db.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            StoreSql.transaction(db, true, () -> {
                for (var statement : SCHEMA) sql.execute(statement);
                Vehicles.initialize(db);
                MainlineSchema.initialize(db);
                PayrollRuns.initialize(db);
                EntityPayroll.initialize(db);
                EntityRateCards.initialize(db);
                sql.execute(SEED_ITEMS);
                var contacts = new HashSet<String>();
                try (var columns = sql.executeQuery("PRAGMA table_info(people)")) {
                    while (columns.next()) contacts.add(columns.getString("name"));
                }
                for (var field : List.of("email", "phone", "address", "start_date", "status",
                        "wgidig_employee_id", "wgidig_account_type", "wgidig_availability")) {
                    if (!contacts.contains(field)) sql.execute("ALTER TABLE people ADD COLUMN " + field + " TEXT");
                }
                if (!contacts.contains("must_change_password")) {
                    sql.execute("ALTER TABLE people ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0");
                }
                sql.execute("UPDATE people SET must_change_password=0 WHERE is_admin=0 AND must_change_password<>0");
                sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS people_wgidig_id ON people(wgidig_employee_id)");
                var jobColumns = new HashSet<String>();
                try (var columns = sql.executeQuery("PRAGMA table_info(jobs)")) {
                    while (columns.next()) jobColumns.add(columns.getString("name"));
                }
                for (String definition : JOB_COLUMNS) {
                    String name = definition.substring(0, definition.indexOf(' '));
                    if (!jobColumns.contains(name)) sql.execute("ALTER TABLE jobs ADD COLUMN " + definition);
                }
                boolean routeRevision;
                try (var column = sql.executeQuery("SELECT 1 FROM pragma_table_info('routes') WHERE name='revision'")) {
                    routeRevision = column.next();
                }
                if (!routeRevision) sql.execute("ALTER TABLE routes ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
                boolean requiresRate = false;
                try (var columns = sql.executeQuery("PRAGMA table_info(assignments)")) {
                    while (columns.next()) {
                        if (columns.getString("name").equals("rate_cents")) requiresRate = columns.getBoolean("notnull");
                    }
                }
                if (requiresRate) {
                    sql.execute("CREATE TABLE assignments_v2 " + ASSIGNMENT_COLUMNS);
                    sql.execute("""
                        INSERT INTO assignments_v2(route_id,person_id,name,role,pay_basis,rate_cents)
                        SELECT route_id,person_id,name,role,pay_basis,rate_cents FROM assignments
                        """);
                    sql.execute("DROP TABLE assignments");
                    sql.execute("ALTER TABLE assignments_v2 RENAME TO assignments");
                    sql.execute("CREATE INDEX person_routes ON assignments(person_id,route_id)");
                }
                return null;
            });
        }
    }
}
