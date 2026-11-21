package fieldops;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static fieldops.StoreSql.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
class PostgresTransactionTest {
    @Test void aReadKeepsItsSnapshotAndAWaitingEditorSeesTheCommittedRevision() throws Exception {
        try (var database = new PostgresTestDatabase(); var first = database.open();
             var second = database.open(); var executor = Executors.newSingleThreadExecutor()) {
            Db.initialize(first);
            long entity = insertId(first, "INSERT INTO entities(name) VALUES('Test entity')");
            var settings = App.JSON.createObjectNode().put("revision", 0);
            settings.putArray("rates");
            settings.putArray("formulas");

            transaction(first, false, () -> {
                assertEquals(0, EntityPayroll.read(first, entity).get("revision").asInt());
                EntityPayroll.save(second, entity, settings);
                assertEquals(0, EntityPayroll.read(first, entity).get("revision").asInt());
                return null;
            });

            int contender = one(second, "SELECT pg_backend_pid() AS pid").get("pid").intValue();
            var stale = settings.deepCopy().put("revision", 1);
            var waiting = transaction(first, true, () -> {
                change(first, "UPDATE entity_payroll_config SET revision=2 WHERE entity_id=?", entity);
                var future = executor.submit(() -> assertThrows(Problem.class, () -> EntityPayroll.save(second, entity, stale)).status);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!one(first, "SELECT pg_backend_pid()=ANY(pg_blocking_pids(?)) AS blocked", contender).get("blocked").asBoolean()) {
                    assertTrue(System.nanoTime() < deadline, "Second editor did not wait for the first");
                    Thread.sleep(10);
                }
                return future;
            });
            assertEquals(409, waiting.get(5, TimeUnit.SECONDS));
            assertEquals(2, EntityPayroll.load(second, entity).get("revision").asInt());
        }
    }
}
