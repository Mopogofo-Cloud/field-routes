package fieldops;

import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
class PostgresCloudPhotoTest extends CloudPhotoTest {
    private PostgresTestDatabase database;

    @Override Connection openFixture() throws Exception {
        if (database == null) database = new PostgresTestDatabase();
        return database.open();
    }

    @Override @AfterEach void stop() throws Exception {
        try {
            super.stop();
        } finally {
            if (database != null) database.close();
        }
    }
}
