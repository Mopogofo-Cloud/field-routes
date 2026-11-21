package fieldops;

import static fieldops.StoreSql.change;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class Auth {
    private Auth() {}
    static final int SESSION_TTL = 12 * 3600;
    static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9@_.+\\-]{1,150}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    // Each derivation allocates at least 16 MiB. Keep bursts of logins within the server's heap.
    private static final Semaphore HASH_SLOTS = new Semaphore(4, true);
    private static final String DUMMY_HASH = hash(randomToken());

    record Login(String token, ObjectNode person) {}

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        try {
            if (!HASH_SLOTS.tryAcquire(1, TimeUnit.SECONDS)) throw new Problem(429, "Sign-in is busy. Try again shortly.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Problem(503, "Sign-in is unavailable. Try again shortly.");
        }
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        try {
            return SCrypt.generate(secret, salt, 16384, 8, 1, 64);
        } finally {
            Arrays.fill(secret, (byte) 0);
            HASH_SLOTS.release();
        }
    }

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HEX.formatHex(salt) + ":" + HEX.formatHex(derive(password, salt));
    }

    private static boolean matches(String password, String stored) {
        String[] parts = stored.split(":", -1);
        if (parts.length != 2 || parts[0].length() != 32 || parts[1].length() != 128) return false;
        try {
            byte[] actual = derive(password, HEX.parseHex(parts[0]));
            return MessageDigest.isEqual(actual, HEX.parseHex(parts[1]));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static ObjectNode person(ResultSet row) throws SQLException {
        boolean admin = row.getBoolean("is_admin");
        return App.JSON.createObjectNode().put("id", row.getLong("id"))
                .put("name", row.getString("name")).put("username", row.getString("username"))
                .put("is_admin", admin).put("today", Db.today())
                .put("must_change_password", admin && row.getBoolean("must_change_password"));
    }

    static ObjectNode me(Connection db, String rawToken) throws SQLException {
        if (rawToken == null || rawToken.isEmpty()) throw new Problem(401, "Sign in to continue.");
        try (var query = db.prepareStatement("""
                SELECT p.id, p.name, p.username, p.is_admin, p.must_change_password
                FROM sessions s JOIN people p ON p.id=s.person_id
                WHERE s.token_hash=? AND s.expires>?""")) {
            query.setString(1, digest(rawToken));
            query.setLong(2, Instant.now().getEpochSecond());
            try (var row = query.executeQuery()) {
                if (row.next()) return person(row);
            }
        }
        throw new Problem(401, "Sign in to continue.");
    }

    private static int countAttempt(Connection db, String source, long now) throws SQLException {
        try (var stmt = db.prepareStatement("""
                INSERT INTO login_attempts(source,started,count) VALUES(?,?,1)
                ON CONFLICT(source) DO UPDATE SET count=login_attempts.count+1 RETURNING count""")) {
            stmt.setString(1, source);
            stmt.setLong(2, now);
            try (var row = stmt.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    static Login login(Connection db, String username, String password, String ip) throws SQLException {
        String loginName = username == null ? "" : username.strip();
        if (!USERNAME.matcher(loginName).matches() || password == null || password.isEmpty()
                || password.codePointCount(0, password.length()) > 256) {
            throw new Problem(422, "Enter a valid login name and password.");
        }
        String source = digest(ip + "/" + loginName.toLowerCase(Locale.ROOT));
        String ipKey = "ip/" + ip;
        long now = Instant.now().getEpochSecond();
        StoreSql.transaction(db, true, () -> {
            change(db, "DELETE FROM login_attempts WHERE started<=?", now - 300);
            if (countAttempt(db, source, now) > 12 || countAttempt(db, ipKey, now) > 100) {
                throw new Problem(429, "Too many sign-in attempts. Try again in five minutes.");
            }
            return null;
        });

        String stored = DUMMY_HASH;
        ObjectNode identity = null;
        try (var query = db.prepareStatement("SELECT id,name,username,is_admin,password_hash,must_change_password FROM people WHERE "
                + StoreSql.nameEquals(db, "username"))) {
            query.setString(1, loginName);
            try (var row = query.executeQuery()) {
                if (row.next()) {
                    identity = person(row);
                    stored = row.getString("password_hash");
                }
            }
        }
        if (!matches(password, stored) || identity == null) throw new Problem(401, "Incorrect login name or password.");

        String token = randomToken();
        long personId = identity.get("id").longValue();
        String verifiedHash = stored;
        StoreSql.transaction(db, true, () -> {
            // A password reset can occur during derivation. Issuance checks the same hash under the write lock.
            int issued = change(db, """
                    INSERT INTO sessions(token_hash,person_id,expires)
                    SELECT ?,id,? FROM people WHERE id=? AND password_hash=?""",
                    digest(token), now + SESSION_TTL, personId, verifiedHash);
            if (issued != 1) throw new Problem(401, "Sign in again.");
            change(db, "DELETE FROM login_attempts WHERE source=?", source);
            change(db, "UPDATE login_attempts SET count=CASE WHEN count>0 THEN count-1 ELSE 0 END WHERE source=?", ipKey);
            change(db, "DELETE FROM sessions WHERE expires<=?", now);
            return null;
        });
        return new Login(token, identity);
    }

    static void logout(Connection db, String token) throws SQLException {
        if (token != null) change(db, "DELETE FROM sessions WHERE token_hash=?", digest(token));
    }

    static void changePassword(Connection db, String token, String currentPassword, String newPassword) throws SQLException {
        if (token == null || token.isEmpty()) throw new Problem(401, "Sign in to continue.");

        String tokenHash = digest(token);
        long now = Instant.now().getEpochSecond();
        String stored;
        long personId;
        try (var query = db.prepareStatement("""
                SELECT p.id, p.password_hash, p.is_admin
                FROM sessions s JOIN people p ON p.id=s.person_id WHERE s.token_hash=? AND s.expires>?""")) {
            query.setString(1, tokenHash);
            query.setLong(2, now);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new Problem(401, "Sign in to continue.");
                if (!row.getBoolean("is_admin")) throw new Problem(403, "Your administrator manages your password.");
                personId = row.getLong("id");
                stored = row.getString("password_hash");
            }
        }
        if (currentPassword == null || currentPassword.isEmpty() || currentPassword.codePointCount(0, currentPassword.length()) > 256)
            throw new Problem(422, "Enter your current password.");
        int length = newPassword == null ? 0 : newPassword.codePointCount(0, newPassword.length());
        if (length < MIN_PASSWORD_LENGTH || length > 256)
            throw new Problem(422, "Use " + MIN_PASSWORD_LENGTH + " to 256 characters for your new password.");
        if (currentPassword.equals(newPassword)) throw new Problem(422, "Choose a password different from your current password.");
        String attemptKey = "change/" + personId;
        StoreSql.transaction(db, true, () -> {
            change(db, "DELETE FROM login_attempts WHERE source=? AND started<=?", attemptKey, now - 300);
            if (countAttempt(db, attemptKey, now) > 12) throw new Problem(429, "Too many attempts. Try again in five minutes.");
            return null;
        });
        if (!matches(currentPassword, stored)) throw new Problem(422, "The current password is incorrect.");
        String nextHash = hash(newPassword);
        StoreSql.transaction(db, true, () -> {
            // A concurrent logout, administrative reset, expiry, or another password change wins before this write.
            int changed = change(db, """
                    UPDATE people SET password_hash=?, must_change_password=0
                    WHERE id=? AND is_admin=1 AND password_hash=?
                    AND EXISTS (SELECT 1 FROM sessions WHERE person_id=people.id AND token_hash=? AND expires>?)
                    """, nextHash, personId, stored, tokenHash, Instant.now().getEpochSecond());
            if (changed != 1) throw new Problem(401, "Sign in again to change your password.");
            change(db, "DELETE FROM sessions WHERE person_id=?", personId);
            change(db, "DELETE FROM login_attempts WHERE source=?", attemptKey);
            return null;
        });
    }

    /** Local interactive provisioning avoids putting administrator passwords in arguments or shell history. */
    public static void main(String[] args) throws SQLException {
        boolean temporary = args.length == 2 && "--temporary".equals(args[1]);
        if ((args.length != 1 && !temporary) || !USERNAME.matcher(args[0]).matches()) {
            System.err.println("Usage: fieldops.Auth USERNAME [--temporary]");
            System.exit(2);
        }
        var console = System.console();
        if (console == null) {
            System.err.println("Open a terminal on the server to enter the administrator password securely.");
            System.exit(2);
        }
        char[] secret = console.readPassword("Administrator password: ");
        if (secret == null) return;
        String encoded;
        try {
            int length = Character.codePointCount(secret, 0, secret.length);
            if (length < MIN_PASSWORD_LENGTH || length > 256)
                throw new IllegalArgumentException("Password must have " + MIN_PASSWORD_LENGTH + " to 256 characters.");
            encoded = hash(new String(secret));
        } finally {
            Arrays.fill(secret, '\0');
        }
        Db.initialize();
        try (var db = Db.open()) {
            int inserted = change(db, """
                    INSERT INTO people(username,password_hash,name,is_admin,must_change_password) VALUES(?,?,'Administrator',1,?)
                    ON CONFLICT DO NOTHING""", args[0], encoded, temporary ? 1 : 0);
            if (inserted != 1) throw new IllegalArgumentException("This username already exists.");
        }
        console.printf("Created administrator %s.%n", args[0]);
    }
}
