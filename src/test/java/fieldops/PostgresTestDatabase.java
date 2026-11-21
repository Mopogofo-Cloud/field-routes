package fieldops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

/** A fresh database on an explicitly selected local PostgreSQL test server. */
final class PostgresTestDatabase implements AutoCloseable {
    private final String name = "fieldroutes_test_" + UUID.randomUUID().toString().replace("-", "");
    private final String host = "jdbc:postgresql://127.0.0.1:" +
        Integer.parseInt(System.getenv("FIELDOPS_TEST_POSTGRES_PORT")) + "/";
    private final String user = System.getenv("FIELDOPS_TEST_POSTGRES_USER");
    private final String passwordFile = System.getenv("FIELDOPS_TEST_POSTGRES_PASSWORD_FILE");
    private final Properties properties = new Properties();

    PostgresTestDatabase() throws Exception {
        properties.setProperty("user", user);
        properties.setProperty("password", Files.readString(Path.of(passwordFile)).stripTrailing());
        adminSql("CREATE DATABASE " + name);
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(host + name + "?currentSchema=fieldroutes", properties);
    }

    void configure(ProcessBuilder launch) {
        var env = launch.environment();
        env.put("FIELDOPS_POSTGRES_URL", host + name + "?sslmode=disable");
        env.put("FIELDOPS_POSTGRES_USER", user);
        env.put("FIELDOPS_POSTGRES_PASSWORD_FILE", passwordFile);
    }

    private void adminSql(String sql) throws SQLException {
        try (var admin = DriverManager.getConnection(host + "postgres", properties);
             var statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override public void close() throws SQLException {
        adminSql("DROP DATABASE " + name + " WITH (FORCE)");
    }
}
