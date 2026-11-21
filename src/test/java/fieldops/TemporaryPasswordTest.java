package fieldops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryPasswordTest {
    private static final String USER = "temporary-user";
    private static final String TEMPORARY = "single-use-example";

    private void issue(Connection db, boolean admin) throws Exception {
        Db.initialize(db);
        StoreSql.change(db, "INSERT INTO people(username,name,password_hash,is_admin,must_change_password) VALUES(?,?,?,?,1)",
                USER, "Test User", Auth.hash(TEMPORARY), admin ? 1 : 0);
    }

    @Test
    void migrationKeepsExistingUsersAndAdminsActive() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:"); var stmt = db.createStatement()) {
            Db.initialize(db);
            stmt.execute("ALTER TABLE people DROP COLUMN must_change_password");
            stmt.execute("INSERT INTO people(username,name,password_hash,is_admin) VALUES('old','Old admin','fixture',1)");
            Db.initialize(db);
            assertEquals(0, StoreSql.one(db, "SELECT must_change_password FROM people").get("must_change_password").intValue());
        }
    }

    @Test
    void contractorWithLegacyFlagCanSignInAndCannotChangeTheirPassword() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            issue(db, false);
            var login = Auth.login(db, USER, TEMPORARY, "localhost");
            assertFalse(login.person().path("must_change_password").asBoolean());
            assertFalse(Auth.me(db, login.token()).path("must_change_password").asBoolean());
            assertFalse(Store.profiles(db).get(0).has("must_change_password"));
            assertEquals(403, assertThrows(Problem.class,
                    () -> Auth.changePassword(db, login.token(), TEMPORARY, "different-password")).status);
            assertDoesNotThrow(() -> Auth.login(db, USER, TEMPORARY, "localhost"));
        }
    }

    @Test
    void adminCanChangeTemporaryAndPermanentPasswordsAndRevokesPreviousSessions() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            issue(db, true);
            var first = Auth.login(db, USER, TEMPORARY, "localhost");
            var another = Auth.login(db, USER, TEMPORARY, "localhost");
            assertTrue(first.person().get("must_change_password").asBoolean());
            assertTrue(Auth.me(db, first.token()).get("must_change_password").asBoolean());
            for (String candidate : List.of("1234567", TEMPORARY)) {
                assertEquals(422, assertThrows(Problem.class,
                        () -> Auth.changePassword(db, first.token(), TEMPORARY, candidate)).status);
            }
            assertEquals(422, assertThrows(Problem.class,
                    () -> Auth.changePassword(db, first.token(), "different-current-password", "12345678")).status);
            Auth.changePassword(db, first.token(), TEMPORARY, "12345678");
            for (var oldSession : List.of(first, another)) {
                assertEquals(401, assertThrows(Problem.class, () -> Auth.me(db, oldSession.token())).status);
            }
            assertEquals(401, assertThrows(Problem.class, () -> Auth.login(db, USER, TEMPORARY, "localhost")).status);
            var permanent = Auth.login(db, USER, "12345678", "localhost");
            assertFalse(permanent.person().get("must_change_password").asBoolean());
            Auth.changePassword(db, permanent.token(), "12345678", "my-next-password");
            assertEquals(401, assertThrows(Problem.class, () -> Auth.me(db, permanent.token())).status);
            assertFalse(Auth.login(db, USER, "my-next-password", "localhost").person().path("must_change_password").asBoolean());
        }
    }

    @Test
    void simultaneousChangesHaveExactlyOneWinner(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("race.sqlite3");
        try (var db = DriverManager.getConnection(url); var threads = Executors.newFixedThreadPool(2)) {
            issue(db, true);
            String token = Auth.login(db, USER, TEMPORARY, "localhost").token();
            var start = new CountDownLatch(1);
            Callable<Boolean> change = () -> {
                try (var worker = DriverManager.getConnection(url)) {
                    start.await();
                    try {
                        Auth.changePassword(worker, token, TEMPORARY, "updated-password");
                        return true;
                    } catch (Problem ex) {
                        assertEquals(401, ex.status);
                        return false;
                    }
                }
            };
            var first = threads.submit(change);
            var second = threads.submit(change);
            start.countDown();
            assertNotEquals(first.get(), second.get());
            assertFalse(Auth.login(db, USER, "updated-password", "localhost").person().get("must_change_password").asBoolean());
        }
    }
}
