package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fieldops.StoreSql.*;

/** Edit the ordered stop list while preserving the original assignment and every job record. */
final class RouteStops {
    static final List<String> LABELS = List.of("drop", "damage", "survey");
    private RouteStops() {}

    record Stop(String address, String externalJobId) {
        Stop {
            address = RouteStops.address(address);
            externalJobId = RouteStops.externalJobId(externalJobId);
        }
    }

    static void add(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                    long revision, List<String> rawAddresses) throws Exception {
        add(db, admin, teamId, day, routeId, revision, rawAddresses, "", false);
    }

    static void add(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                    long revision, List<String> rawAddresses, String label, boolean urgent) throws Exception {
        requireAdmin(admin);
        addEntries(db, admin, teamId, day, routeId, revision,
            rawAddresses == null ? null : rawAddresses.stream().map(value -> new Stop(value, "")).toList(), label, urgent);
    }

    static void addEntries(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                           long revision, List<Stop> stops, String label, boolean urgent) throws Exception {
        requireAdmin(admin);
        if (stops == null || stops.isEmpty()) throw new Problem(422, "Enter at least one address.");
        if (stops.size() > 1000) throw tooManyStops();
        String stopLabel = stopLabel(label);
        transaction(db, true, () -> {
            checkRoute(db, teamId, day, routeId, revision);
            long activeCount = one(db, "SELECT count(*) AS n FROM active_route_jobs WHERE route_id=?", routeId).path("n").asLong();
            if (activeCount + stops.size() > 1000) throw tooManyStops();
            long sequence = one(db, "SELECT coalesce(max(position),0) AS n FROM jobs WHERE route_id=?", routeId).path("n").asLong();
            insert(db, routeId, sequence, stops, stopLabel, urgent);
            updateRoute(db, routeId);
            return null;
        });
    }

    static void classify(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                         long revision, long jobId, String label, boolean urgent) throws SQLException {
        classify(db, admin, teamId, day, routeId, revision, jobId, label, urgent, null);
    }

    static void classify(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                         long revision, long jobId, String label, boolean urgent, String externalId) throws SQLException {
        requireAdmin(admin);
        String stopLabel = stopLabel(label);
        transaction(db, true, () -> {
            checkRoute(db, teamId, day, routeId, revision);
            var job = one(db, "SELECT stop_label,urgent,external_job_id FROM active_route_jobs WHERE id=? AND route_id=?", jobId, routeId);
            if (job == null) throw new Problem(404, "Stop not found on this route.");
            String oldId = job.get("external_job_id").textValue();
            String newId = externalId == null ? oldId : externalJobId(externalId);
            boolean idChanged = !oldId.equals(newId);
            if (idChanged || !stopLabel.equals(job.get("stop_label").asText()) || urgent != job.get("urgent").asBoolean()) {
                // The report's address editor can also change the ID and uses the job revision.
                change(db, "UPDATE jobs SET stop_label=?,urgent=?,external_job_id=?,revision=revision+? WHERE id=?",
                    stopLabel, urgent ? 1 : 0, newId, idChanged ? 1 : 0, jobId);
                change(db, "UPDATE routes SET revision=revision+1 WHERE id=?", routeId);
            }
            return null;
        });
    }

    static void remove(Connection db, ObjectNode admin, long teamId, String day, long routeId,
                       long revision, long jobId) throws Exception {
        requireAdmin(admin);
        transaction(db, true, () -> {
            checkRoute(db, teamId, day, routeId, revision);
            // Jobs keep their original sequence. Record the displayed number for the archived report.
            int removed = change(db, """
                INSERT INTO job_removals(job_id,position,removed_at)
                SELECT j.id,(SELECT count(*) FROM active_route_jobs a
                    WHERE a.route_id=j.route_id AND a.position<=j.position),?
                FROM active_route_jobs j WHERE j.id=? AND j.route_id=?
                """, Instant.now().toString(), jobId, routeId);
            if (removed != 1) throw new Problem(404, "Stop not found on this route.");
            change(db, "UPDATE jobs SET revision=revision+1 WHERE id=?", jobId);
            updateRoute(db, routeId);
            return null;
        });
    }

    private static void checkRoute(Connection db, long teamId, String day, long routeId, long revision) throws SQLException {
        if (revision < 0) throw StoreInput.invalid("revision");
        var route = one(db, "SELECT revision FROM routes WHERE id=? AND team_id=? AND date=?", routeId, teamId, StoreInput.date(day));
        if (route == null) throw new Problem(404, "Route not found for this team and date.");
        if (route.path("revision").asLong() != revision)
            throw new Problem(409, "The route changed. Review its current stops before trying again.");
    }

    // Must run inside the same write transaction as the stop changes or address correction.
    static void updateRoute(Connection db, long routeId) throws Exception {
        List<String> addresses = new ArrayList<>();
        for (JsonNode row : rows(db, "SELECT address FROM active_route_jobs WHERE route_id=? ORDER BY position", routeId))
            addresses.add(row.path("address").asText());
        // Empty routes have no dispatchable itinerary and can be refilled independently.
        String hash = addresses.isEmpty() ? "empty:" + routeId : Store.fingerprint(addresses);
        uniqueChange(db, "This team already has that route on this date.",
            "UPDATE routes SET address_hash=?,revision=revision+1 WHERE id=?", hash, routeId);
    }

    static void insert(Connection db, long routeId, long sequence, List<Stop> stops,
                       String stopLabel, boolean urgent) throws SQLException {
        try (var stmt = db.prepareStatement("INSERT INTO jobs(route_id,position,address,external_job_id,stop_label,urgent) VALUES(?,?,?,?,?,?)")) {
            for (Stop stop : stops) {
                bind(stmt, routeId, ++sequence, stop.address(), stop.externalJobId(), stopLabel, urgent ? 1 : 0);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    static String stopLabel(String raw) {
        String label = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!label.isEmpty() && !LABELS.contains(label)) throw new Problem(422, "Choose Drop, Damage, Survey, or no label.");
        return label;
    }

    static String address(String raw) {
        String address = raw == null ? "" : raw.strip();
        if (address.isEmpty() || address.codePointCount(0, address.length()) > 500)
            throw new Problem(422, "Enter an address of up to 500 characters for each stop.");
        return address;
    }

    static String externalJobId(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.codePointCount(0, value.length()) > 120) throw StoreInput.invalid("external_job_id");
        return value;
    }

    private static void requireAdmin(ObjectNode person) {
        if (!person.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
    }

    private static Problem tooManyStops() { return new Problem(422, "A route can have up to 1,000 active stops."); }
}
