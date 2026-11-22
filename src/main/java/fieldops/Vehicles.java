package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import static fieldops.StoreSql.*;

final class Vehicles {
    private Vehicles() {}

    static void initialize(Connection db) throws SQLException {
        try (var sql = db.createStatement()) {
            sql.execute("""
                CREATE TABLE IF NOT EXISTS vehicles (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    brand TEXT NOT NULL DEFAULT '', plate TEXT NOT NULL DEFAULT '')
                """);
            sql.execute("""
                CREATE TABLE IF NOT EXISTS route_vehicles (
                    route_id INTEGER NOT NULL REFERENCES routes, vehicle_id INTEGER NOT NULL REFERENCES vehicles,
                    name TEXT NOT NULL, brand TEXT NOT NULL, plate TEXT NOT NULL, PRIMARY KEY(route_id,vehicle_id))
                """);
            sql.execute("CREATE INDEX IF NOT EXISTS route_vehicle_use ON route_vehicles(vehicle_id,route_id)");
        }
        FleetImport.initialize(db);
    }

    private static final String BOOKED = """
        EXISTS(SELECT 1 FROM route_vehicles used JOIN routes booked ON booked.id=used.route_id
               WHERE used.vehicle_id=v.id AND booked.date=? AND booked.team_id<>?)
        OR EXISTS(SELECT 1 FROM mainline_daily_vehicles used JOIN mainline_dailies booked ON booked.id=used.daily_id
               WHERE used.vehicle_id=v.id AND booked.date=?)
        """;

    static ArrayNode list(Connection db, String date, long teamId) throws SQLException {
        return rows(db, "SELECT v.id,v.name,v.brand,v.plate,CASE WHEN " + BOOKED +
            " THEN 0 ELSE 1 END AS available FROM vehicles v ORDER BY lower(v.name),v.id", StoreInput.date(date), teamId, date);
    }

    static ArrayNode list(Connection db, String date) throws SQLException {
        return list(db, date, 0);
    }

    static ArrayNode list(Connection db) throws SQLException {
        return list(db, Db.today());
    }

    static void save(Connection db, ObjectNode input, Long id) throws SQLException {
        String name = StoreInput.text(input, "name", 1, 120);
        String brand = StoreInput.text(input, "brand", 0, 120);
        String plate = StoreInput.text(input, "plate", 0, 80);
        String duplicate = "A vehicle with that name already exists.";
        if (id == null) {
            uniqueChange(db, duplicate, "INSERT INTO vehicles(name,brand,plate) VALUES(?,?,?)", name, brand, plate);
        } else if (uniqueChange(db, duplicate, "UPDATE vehicles SET name=?,brand=?,plate=? WHERE id=?", name, brand, plate, id) == 0) {
            throw new Problem(404, "Vehicle not found.");
        }
    }

    // Called within the route's write transaction. Multiple routes for the same team can share its truck.
    static void assign(Connection db, long routeId, String date, Set<Long> vehicleIds) throws SQLException {
        if (vehicleIds.isEmpty()) return;
        long teamId = one(db, "SELECT team_id FROM routes WHERE id=?", routeId).get("team_id").asLong();
        for (long id : vehicleIds) {
            var vehicle = one(db, "SELECT v.name,v.brand,v.plate," + BOOKED + " AS booked FROM vehicles v WHERE id=?",
                date, teamId, date, id);
            if (vehicle == null) throw new Problem(422, "Select a vehicle in the vehicle list.");
            if (vehicle.path("booked").asBoolean()) throw new Problem(409,
                vehicle.get("name").asText() + " is already assigned to another team on " + date + ". Choose an available vehicle.");
            change(db, "INSERT INTO route_vehicles(route_id,vehicle_id,name,brand,plate) VALUES(?,?,?,?,?)",
                routeId, id, vehicle.get("name").asText(), vehicle.get("brand").asText(), vehicle.get("plate").asText());
        }
    }
}
