package fieldops;

import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
class PostgresPayrollTest {
    private PostgresTestDatabase database;

    @BeforeEach void createDatabase() throws Exception { database = new PostgresTestDatabase(); }

    @AfterEach void removeDatabase() throws Exception {
        if (database != null) database.close();
    }

    @Nested class Calculations extends WeeklyPayrollTest {
        @Override Connection openFixture() throws Exception { return database.open(); }
    }

    @Nested class Statements extends PayrollRunsTest {
        @Override Connection openFixture() throws Exception { return database.open(); }
    }
}
