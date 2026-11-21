package fieldops;

import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
class PostgresHttpApiTest extends HttpApiTest {
    private PostgresTestDatabase database;

    @BeforeEach void createDatabase() throws Exception {
        database = new PostgresTestDatabase();
    }

    @Override Connection fixtureDatabase() throws Exception { return database.open(); }

    @Override void configureDatabase(ProcessBuilder launch) {
        super.configureDatabase(launch);
        database.configure(launch);
    }

    @AfterEach void removeDatabase() throws Exception {
        if (database != null) database.close();
    }
}
