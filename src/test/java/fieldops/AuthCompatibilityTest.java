package fieldops;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthCompatibilityTest {
    // Independently generated with the previous service's Python hashlib.scrypt and SHA-256 parameters.
    private static final String OLD_PASSWORD_HASH =
            "000102030405060708090a0b0c0d0e0f:cb0027b2570dad8b3e76dc30f701739b46cc5208d321f3e6716ffc290cc1c94de"
                    + "c802096c7c17a3171919037ba0e23a11b9b0de108ffa5f1eb1233b29f2d93e2";
    private static final String OLD_SESSION_HASH = "f921571e27b2772d6cf6d3cdb9284fe49b3d5b5fbdce0eb4ffc4c6b41d675fe9";

    @Test
    void legacyCredentialsSessionsAndAccountLockoutWork() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Db.initialize(db);
            StoreSql.change(db, "INSERT INTO people(id,name,username,password_hash,is_admin) VALUES(1,'Admin','owner',?,1)", OLD_PASSWORD_HASH);
            StoreSql.change(db, "INSERT INTO sessions VALUES(?,1,?)", OLD_SESSION_HASH, Instant.now().getEpochSecond() + 120);
            assertTrue(Auth.me(db, "fieldops-previous-session").get("is_admin").asBoolean());
            StoreSql.change(db, "UPDATE sessions SET expires=0 WHERE token_hash=?", OLD_SESSION_HASH);
            assertEquals(401, assertThrows(Problem.class, () -> Auth.me(db, "fieldops-previous-session")).status);
            Auth.logout(db, "fieldops-previous-session");
            assertEquals(401, assertThrows(Problem.class, () -> Auth.me(db, "fieldops-previous-session")).status);

            Auth.Login login = Auth.login(db, "OWNER", "test-password-ñ", "127.0.0.1");
            assertEquals(login.person(), Auth.me(db, login.token()));
            Auth.logout(db, login.token());
            assertEquals(401, assertThrows(Problem.class, () -> Auth.me(db, login.token())).status);
            for (int i = 0; i < 12; i++) {
                assertEquals(401, assertThrows(Problem.class, () -> Auth.login(db, "owner", "wrong", "127.0.0.1")).status);
            }
            assertEquals(429, assertThrows(Problem.class, () -> Auth.login(db, "owner", "test-password-ñ", "127.0.0.1")).status);
        }
    }

    @Test
    void earlierDispatchesKeepAssignmentsWhenRatesBecomeOptional() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = db.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            Db.initialize(db);
            sql.execute("ALTER TABLE people DROP COLUMN email");
            sql.execute("ALTER TABLE routes DROP COLUMN revision");
            sql.execute("DROP VIEW active_route_jobs");
            sql.execute("ALTER TABLE jobs DROP COLUMN stop_label");
            sql.execute("ALTER TABLE jobs DROP COLUMN urgent");
            for (String field : java.util.List.of("tech_needed", "conduits", "items", "comments", "outcome", "outcome_reason", "external_job_id")) {
                sql.execute("ALTER TABLE jobs DROP COLUMN " + field);
            }
            sql.execute("DROP TABLE job_removals");
            sql.execute("DROP TABLE assignments");
            sql.execute("""
                CREATE TABLE assignments (route_id INTEGER REFERENCES routes, person_id INTEGER REFERENCES people,
                name TEXT NOT NULL, role TEXT NOT NULL, pay_basis TEXT NOT NULL, rate_cents INTEGER NOT NULL,
                PRIMARY KEY(route_id, person_id))""");
            sql.execute("INSERT INTO entities VALUES(1, 'Entity')");
            sql.execute("INSERT INTO teams VALUES(1,'Team',1)");
            sql.execute("INSERT INTO routes VALUES(1,'2020-01-01',1,'Team',1,'Entity','addresses')");
            sql.execute("INSERT INTO jobs(id,route_id,position,address) VALUES(1,1,1,'Original stop')");
            String oldBores = "[{\"type\":\"road\",\"feet\":23}]";
            StoreSql.change(db, "UPDATE jobs SET revision=3,fiber_type='Legacy fiber',bores=?,submitted_at='2020-01-01T12:00:00Z' WHERE id=1", oldBores);
            sql.execute("INSERT INTO people(id,username,name,password_hash) VALUES(1,'worker','Crew Member','test')");
            sql.execute("INSERT INTO assignments VALUES(1,1,'Historical Name','Lead','daily',12500)");

            Db.initialize(db);
            Db.initialize(db);
            var row = StoreSql.one(db, "SELECT name,rate_cents FROM assignments");
            assertEquals("Historical Name", row.get("name").asText());
            assertEquals(12500, row.get("rate_cents").asInt());
            assertEquals(0, StoreSql.one(db, "SELECT revision FROM routes WHERE id=1").get("revision").intValue());
            var stop = StoreSql.one(db, "SELECT * FROM active_route_jobs WHERE id=1");
            assertEquals("Original stop", stop.get("address").textValue());
            assertEquals("", stop.get("external_job_id").textValue());
            assertEquals("", stop.get("stop_label").textValue());
            assertEquals(0, stop.get("urgent").intValue());
            assertEquals(3, stop.get("revision").intValue());
            assertEquals("Legacy fiber", stop.get("fiber_type").textValue());
            assertEquals(oldBores, stop.get("bores").textValue());
            assertTrue(stop.get("tech_needed").isNull());
            assertEquals("[]", stop.get("conduits").textValue());
            assertEquals("[]", stop.get("items").textValue());
            assertEquals("", stop.get("comments").textValue());
            var admin = App.JSON.createObjectNode().put("id", 2).put("is_admin", true);
            var migrated = JobDetails.get(db, admin, 1).path("job");
            assertEquals("done", migrated.get("outcome").textValue());
            assertEquals("submitted", migrated.get("status").textValue());
            assertTrue(migrated.get("outcome_reason").isNull());
            assertEquals(1, StoreSql.change(db, "UPDATE assignments SET rate_cents=NULL"));
            assertEquals(1, StoreSql.change(db, "UPDATE people SET email='crew@example.invalid'"));
            try (var broken = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(broken.next()); }
        }
    }
}
