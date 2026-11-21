package fieldops;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FIELDOPS_TEST_POSTGRES_PORT", matches="[0-9]+")
class PostgresImportTest {
    @Test
    void preservesPhotoBytesAndIdentityAndRefusesDriftOrOverwrite(@TempDir Path dir) throws Exception {
        var file = dir.resolve("crew #1.sqlite3");
        try (var source = DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString())) {
            Db.initialize(source);
            try (var sql = source.createStatement()) {
                sql.execute("INSERT INTO entities(id,name) VALUES(12,'Example')");
                sql.execute("INSERT INTO teams(id,name,entity_id) VALUES(3,'Example Crew',12)");
                sql.execute("INSERT INTO routes(id,date,team_id,team_name,entity_id,entity_name,address_hash) VALUES(19,'2026-01-05',3,'Example Crew',12,'Example','abc')");
                sql.execute("INSERT INTO jobs(id,route_id,position,address,feet_buried) VALUES(8,19,1,'Rue Émile',126.25)");
                sql.execute("INSERT INTO photos(id,job_id,mime,content) VALUES(1,8,'image/jpeg',x'00ff12de0044'),(135,8,'image/png',x'00')");
                sql.execute("DELETE FROM photos WHERE id=135");
            }
        }
        try (var fixture = new PostgresTestDatabase()) {
            try (var source = DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString());
                 var sql = source.createStatement()) {
                sql.execute("CREATE TABLE unexpected(id INTEGER PRIMARY KEY)");
                try (var pg = fixture.open()) {
                    var ex = assertThrows(IllegalStateException.class, () -> PostgresImport.migrate(file, pg, true));
                    assertTrue(ex.getMessage().contains("table sets differ"));
                    try (var query = pg.createStatement(); var count = query.executeQuery("SELECT to_regnamespace('fieldroutes') IS NULL")) {
                        assertTrue(count.next());
                        assertTrue(count.getBoolean(1), "The failed copy should roll back schema creation");
                    }
                }
                sql.execute("DROP TABLE unexpected");
            }
            try (var pg = fixture.open()) { PostgresImport.migrate(file, pg, true); }
            try (var pg = fixture.open()) { PostgresImport.migrate(file, pg, false); }
            try (var pg = fixture.open(); var sql = pg.createStatement()) {
                try (var next = sql.executeQuery("SELECT nextval(pg_get_serial_sequence('fieldroutes.photos','id'))")) {
                    assertTrue(next.next());
                    assertEquals(136L, next.getLong(1));
                }
                sql.execute("UPDATE fieldroutes.photos SET content=set_byte(content,2,3)");
            }
            try (var pg = fixture.open()) {
                var ex = assertThrows(IllegalStateException.class, () -> PostgresImport.migrate(file, pg, false));
                assertTrue(ex.getMessage().contains("Different data in photos.content"));
            }
            try (var pg = fixture.open()) {
                var ex = assertThrows(IllegalStateException.class, () -> PostgresImport.migrate(file, pg, true));
                assertTrue(ex.getMessage().contains("contains data"));
            }
        }
    }
}
