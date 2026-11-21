package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import static fieldops.StoreSql.*;

final class PreviousCrews {
    private PreviousCrews() {}

    static ArrayNode list(Connection db, String date) throws SQLException {
        return rows(db, """
            SELECT route_id,team_id,team_name,date,entity_name FROM (
                SELECT id AS route_id,team_id,team_name,date,entity_name,
                       row_number() OVER (PARTITION BY team_id ORDER BY date DESC,id DESC) AS recent
                FROM routes WHERE date<=?) ranked
            WHERE recent=1 ORDER BY date DESC,lower(team_name),route_id DESC
            """, StoreInput.date(date));
    }

    static ObjectNode get(Connection db, long routeId) throws SQLException {
        return transaction(db, false, () -> {
            var crew = one(db, "SELECT team_id,team_name AS name,entity_id FROM routes WHERE id=?", routeId);
            if (crew == null) throw new Problem(404, "Previous crew not found.");
            var members = crew.putArray("member_ids");
            for (var member : rows(db, "SELECT person_id FROM assignments WHERE route_id=? ORDER BY person_id", routeId))
                members.add(member.get("person_id"));
            var vehicles = crew.putArray("vehicle_ids");
            for (var vehicle : rows(db, "SELECT vehicle_id FROM route_vehicles WHERE route_id=? ORDER BY vehicle_id", routeId))
                vehicles.add(vehicle.get("vehicle_id"));
            return crew;
        });
    }
}
