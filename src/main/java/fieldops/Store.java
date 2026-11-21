package fieldops;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Part;
import java.io.BufferedInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import static fieldops.StoreInput.*;
import static fieldops.StoreSql.*;

public final class Store {
    private Store() {}

    private static final String PROFILES = """
        SELECT id,name,role,pay_basis,username,email,phone,address,start_date,status,
          wgidig_employee_id,wgidig_account_type,wgidig_availability,
          rate_cents / 100.0 AS pay_rate FROM people WHERE is_admin=0
        """;
    private static final List<String> CONTACTS = List.of("email","phone","address","start_date","status");
    private static final String TEAM_ROWS = """
        SELECT t.id,t.name,t.entity_id,e.name AS entity_name
        FROM teams t JOIN entities e ON e.id=t.entity_id
        """;
    private static final String JOB_FIELDS = """
        j.id,j.address,j.external_job_id,j.stop_label,j.urgent,j.feet_buried,j.fiber_type,j.submitted_at,j.revision,
        j.bores,j.conduits,j.items,j.tech_needed,j.comments,p.name AS submitted_by,j.last_edited_at,
        CASE WHEN j.submitted_at IS NOT NULL THEN coalesce(j.outcome,'done') END AS outcome,j.outcome_reason,
        (SELECT editor.name FROM people editor WHERE editor.id=j.last_edited_by) AS last_edited_by,
        (SELECT COUNT(*) FROM job_notes n WHERE n.job_id=j.id) AS note_count,
        CASE WHEN j.submitted_at IS NULL THEN 'assigned' WHEN j.outcome IN ('pending','cancel') THEN j.outcome
             ELSE 'submitted' END AS status
        """;
    private static final String WORKER_ROUTE = """
        AND r.date=? AND EXISTS(SELECT 1 FROM assignments a WHERE a.route_id=r.id AND a.person_id=?)
        AND NOT EXISTS(SELECT 1 FROM job_removals x WHERE x.job_id=j.id)
        """;
    private static final int PHOTO_LIMIT = 12 * 1024 * 1024;
    private static final Set<String> BORE_TYPES = Set.of("driveway", "sidewalk", "road");
    private static final Set<String> CONDUIT_TYPES = Set.of("driveway", "sidewalk", "road", "existing");
    private static final ObjectWriter DIGEST_WRITER = App.JSON.writer()
        .with(JsonWriteFeature.ESCAPE_NON_ASCII).without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);

    public static ArrayNode profiles(Connection db) throws SQLException {
        return rows(db, PROFILES + " ORDER BY name,id");
    }

    public static ObjectNode saveProfile(Connection db, ObjectNode input, Long id) throws Exception {
        fields(input, "name","role","pay_basis","pay_rate","username","password","email","phone","address","start_date","status");
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("name", text(input, "name", 1, 120));
        values.put("role", text(input, "role", id == null ? 1 : 0, 120));
        String username = text(input, "username", 1, 150);
        if (!username.matches("[a-zA-Z0-9@_.+\\-]+")) throw invalid("username");
        values.put("username", username);
        String basis = text(input, "pay_basis", 1, 20);
        if (!Set.of("daily","production").contains(basis)) throw invalid("pay_basis");
        values.put("pay_basis", basis);
        if (!input.has("pay_rate")) throw invalid("pay_rate");
        values.put("rate_cents", input.hasNonNull("pay_rate") ? decimal(input, "pay_rate").movePointRight(2).longValueExact() : null);
        for (String field : CONTACTS) {
            if (input.has(field)) values.put(field, input.hasNonNull(field) ? text(input, field, 0, 500) : null);
        }
        if (id == null || input.hasNonNull("password")) {
            JsonNode value = input.path("password");
            if (!value.isTextual()) throw invalid("password");
            String password = value.textValue();
            int length = password.codePointCount(0, password.length());
            if (length < Auth.MIN_PASSWORD_LENGTH || length > 256) throw invalid("password");
            values.put("password_hash", Auth.hash(password));
        }
        return transaction(db, true, () -> {
            String conflict = "This login name is already in use.";
            long personId;
            if (id == null) {
                String marks = String.join(",", Collections.nCopies(values.size(), "?"));
                String sql = "INSERT INTO people(" + String.join(",", values.keySet()) + ") VALUES(" + marks + ")";
                personId = uniqueInsertId(db, conflict, sql, values.values().toArray());
            } else {
                StringJoiner assignments = new StringJoiner(",");
                values.keySet().forEach(key -> assignments.add(key + "=?"));
                var args = new ArrayList<>(values.values());
                args.add(id);
                if (uniqueChange(db, conflict, "UPDATE people SET " + assignments + " WHERE id=? AND is_admin=0", args.toArray()) == 0)
                    throw new Problem(404, "Subcontractor not found.");
                if (values.containsKey("password_hash")) change(db, "DELETE FROM sessions WHERE person_id=?", id);
                personId = id;
            }
            return one(db, PROFILES + " AND id=?", personId);
        });
    }

    public static ArrayNode entities(Connection db) throws SQLException {
        return rows(db, """
            SELECT e.id,e.name,w.wgidig_id FROM entities e LEFT JOIN wgidig_entities w ON w.entity_id=e.id
            ORDER BY lower(e.name),e.id
            """);
    }

    public static ArrayNode roles(Connection db) throws SQLException {
        return rows(db, "SELECT id,name FROM roles ORDER BY lower(name),id");
    }

    public static ObjectNode addRole(Connection db, ObjectNode input) throws Exception {
        fields(input, "name");
        String name = text(input, "name", 1, 120);
        return transaction(db, true, () -> {
            long id = uniqueInsertId(db, "This role already exists.", "INSERT INTO roles(name) VALUES(?)", name);
            return App.JSON.createObjectNode().put("id", id).put("name", name);
        });
    }

    public static ObjectNode addEntity(Connection db, ObjectNode input) throws Exception {
        fields(input, "name");
        String name = text(input, "name", 1, 120);
        return transaction(db, true, () -> {
            long id = uniqueInsertId(db, "This contracting entity already exists.", "INSERT INTO entities(name) VALUES(?)", name);
            return App.JSON.createObjectNode().put("id", id).put("name", name);
        });
    }

    public static ArrayNode teams(Connection db) throws Exception {
        return transaction(db, false, () -> {
            ArrayNode teams = rows(db, TEAM_ROWS + " ORDER BY lower(t.name),t.id");
            Map<Long,ObjectNode> byId = index(teams);
            byId.values().forEach(team -> team.putArray("member_ids"));
            for (JsonNode member : rows(db, "SELECT team_id,person_id FROM team_members ORDER BY team_id,person_id")) {
                byId.get(member.get("team_id").longValue()).withArray("member_ids").add(member.get("person_id"));
            }
            return teams;
        });
    }

    private record TeamSelection(String name, long entityId, Set<Long> members) {
        static TeamSelection read(ObjectNode input) {
            return new TeamSelection(text(input, "name", 1, 120), integer(input.get("entity_id"), "entity_id"),
                ids(input, "member_ids", 1, Integer.MAX_VALUE));
        }
    }

    public static ObjectNode saveTeam(Connection db, ObjectNode input, Long id) throws Exception {
        fields(input, "name","entity_id","member_ids");
        var selection = TeamSelection.read(input);
        return transaction(db, true, () -> writeTeam(db, selection, id));
    }

    private static ObjectNode writeTeam(Connection db, TeamSelection selection, Long id) throws SQLException {
        if (one(db, "SELECT id FROM entities WHERE id=?", selection.entityId()) == null)
            throw new Problem(422, "Select an existing contracting entity.");
        String conflict = "This team name is already in use.";
        long teamId;
        if (id == null) {
            teamId = uniqueInsertId(db, conflict, "INSERT INTO teams(name,entity_id) VALUES(?,?)", selection.name(), selection.entityId());
        } else {
            if (uniqueChange(db, conflict, "UPDATE teams SET name=?,entity_id=? WHERE id=?", selection.name(), selection.entityId(), id) == 0)
                throw new Problem(404, "Team not found.");
            change(db, "DELETE FROM team_members WHERE team_id=?", id);
            teamId = id;
        }
        selectedMembers(db, "INSERT INTO team_members(team_id,person_id) SELECT ?,id FROM people WHERE id=? AND is_admin=0",
            teamId, selection.members());
        ObjectNode team = one(db, TEAM_ROWS + " WHERE t.id=?", teamId);
        ArrayNode memberIds = team.putArray("member_ids");
        selection.members().stream().sorted().forEach(value -> memberIds.add(value.longValue()));
        return team;
    }

    private static void selectedMembers(Connection db, String insertSql, long targetId, Set<Long> members) throws SQLException {
        try (var insert = db.prepareStatement(insertSql)) {
            for (long member : members) {
                bind(insert, targetId, member);
                if (insert.executeUpdate() != 1) throw new Problem(422, "Select existing subcontractors for this team.");
            }
        }
    }

    static ObjectNode createTeamRoute(Connection db, ObjectNode team, ObjectNode route) throws Exception {
        fields(team, "name","entity_id","member_ids","source_team_id");
        fields(route, "date","addresses","stop_label","urgent","vehicle_ids");
        var selection = TeamSelection.read(team);
        Long sourceId = team.has("source_team_id") ? integer(team.get("source_team_id"), "source_team_id") : null;
        return transaction(db, true, () -> {
            if (sourceId != null) {
                var previous = one(db, "SELECT name FROM teams WHERE id=?", sourceId);
                if (previous == null) throw new Problem(422, "The previous team is no longer available.");
                if (selection.name().equals(previous.get("name").textValue()))
                    return writeRoute(db, route, sourceId, selection);
            }
            var savedTeam = writeTeam(db, selection, null);
            return writeRoute(db, route, savedTeam.get("id").longValue(), null);
        });
    }

    private static Map<Long,ObjectNode> index(ArrayNode items) {
        Map<Long,ObjectNode> byId = new HashMap<>();
        for (JsonNode item : items) byId.put(item.get("id").longValue(), (ObjectNode) item);
        return byId;
    }

    public static ArrayNode routes(Connection db, ObjectNode session, String date) throws Exception {
        String today = Db.today();
        String day = date == null ? today : date(date);
        boolean admin = session.path("is_admin").asBoolean();
        if (!admin && !day.equals(today)) return App.JSON.createArrayNode();
        String filter = " WHERE r.date=?";
        if (!admin) filter += """
             AND EXISTS(SELECT 1 FROM assignments a WHERE a.route_id=r.id AND a.person_id=?)
             AND EXISTS(SELECT 1 FROM active_route_jobs active WHERE active.route_id=r.id)
            """;
        String scope = filter;
        Object[] args = admin ? new Object[]{day} : new Object[]{day, session.get("id").longValue()};
        return transaction(db, false, () -> routeData(db, scope, args, admin));
    }

    static ArrayNode dailyTeamSummaries(Connection db, ObjectNode admin, String day) throws SQLException {
        if (!admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        return rows(db, """
            SELECT team_id,team_name,entity_name FROM routes WHERE date=?
            GROUP BY team_id,team_name,entity_name ORDER BY min(id)
            """, date(day));
    }

    static ArrayNode teamRoutes(Connection db, ObjectNode admin, long teamId, String day) throws Exception {
        if (!admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        Object[] args = {date(day), teamId};
        return transaction(db, false, () -> routeData(db, " WHERE r.date=? AND r.team_id=?", args, true));
    }

    private static ArrayNode routeData(Connection db, String scope, Object[] args, boolean admin) throws Exception {
        ArrayNode routes = rows(db, """
            SELECT r.id,r.date,r.team_id,r.team_name,r.entity_id,r.entity_name,r.revision FROM routes r
            """ + scope + " ORDER BY r.id", args);
        if (routes.isEmpty()) return routes;
        Map<Long,ObjectNode> byRoute = index(routes);
        for (ObjectNode route : byRoute.values()) {
            route.putArray("jobs");
            route.putArray("vehicles");
            route.putArray("member_ids");
            if (admin) {
                route.putArray("members");
                route.putArray("removed_jobs");
            }
        }
        for (var node : rows(db, """
                SELECT v.route_id,v.vehicle_id AS id,v.name,v.brand,v.plate FROM route_vehicles v JOIN routes r ON r.id=v.route_id
                """ + scope + " ORDER BY v.route_id,v.name,v.vehicle_id", args)) {
            var vehicle = (ObjectNode) node;
            byRoute.get(vehicle.remove("route_id").asLong()).withArray("vehicles").add(vehicle);
        }
        String memberFields = admin ? "a.name,a.role,a.pay_basis,a.rate_cents/100.0 AS pay_rate," : "";
        for (JsonNode node : rows(db, "SELECT " + memberFields + """
                a.person_id AS id,a.route_id FROM assignments a JOIN routes r ON r.id=a.route_id
                """ + scope + " ORDER BY a.route_id,a.person_id", args)) {
            ObjectNode member = (ObjectNode) node;
            ObjectNode route = byRoute.get(member.remove("route_id").longValue());
            route.withArray("member_ids").add(member.get("id"));
            if (admin) route.withArray("members").add(member);
        }
        ArrayNode jobs = rows(db, "SELECT j.route_id," + JOB_FIELDS + """
            ,row_number() OVER(PARTITION BY j.route_id ORDER BY j.position) AS position
            FROM active_route_jobs j JOIN routes r ON r.id=j.route_id LEFT JOIN people p ON p.id=j.submitted_by
            """ + scope + " ORDER BY j.route_id,j.position", args);
        Map<Long,ObjectNode> byJob = index(jobs);
        for (JsonNode node : jobs) {
            ObjectNode job = prepareJob((ObjectNode) node);
            byRoute.get(job.remove("route_id").longValue()).withArray("jobs").add(job);
        }
        for (JsonNode photo : rows(db, """
                SELECT f.id,f.job_id FROM photos f JOIN active_route_jobs j ON j.id=f.job_id JOIN routes r ON r.id=j.route_id
                """ + scope + " ORDER BY f.job_id,f.id", args)) {
            addPhoto(byJob.get(photo.get("job_id").longValue()), photo.get("id").longValue());
        }
        if (admin) {
            for (JsonNode node : rows(db, """
                SELECT j.route_id,j.id,j.address,j.external_job_id,j.stop_label,j.urgent,x.position,x.removed_at FROM job_removals x
                JOIN jobs j ON j.id=x.job_id JOIN routes r ON r.id=j.route_id
                """ + scope + " ORDER BY j.route_id,x.removed_at,j.id", args)) {
                ObjectNode removed = (ObjectNode) node;
                removed.put("urgent", removed.get("urgent").asBoolean());
                byRoute.get(removed.remove("route_id").longValue()).withArray("removed_jobs").add(removed);
            }
        }
        return routes;
    }

    private static ObjectNode prepareJob(ObjectNode job) throws Exception {
        for (String key : List.of("bores", "conduits", "items"))
            job.set(key, App.JSON.readTree(job.get(key).textValue()));
        if (job.hasNonNull("tech_needed")) job.put("tech_needed", job.get("tech_needed").asBoolean());
        job.put("urgent", job.get("urgent").asBoolean());
        job.putArray("photos");
        return job;
    }

    private static void addPhoto(ObjectNode job, long photoId) {
        job.withArray("photos").addObject().put("id", photoId).put("url", "/api/photos/" + photoId);
    }

    public static ObjectNode createRoute(Connection db, ObjectNode input) throws Exception {
        fields(input, "team_id","date","addresses","stop_label","urgent","vehicle_ids");
        long teamId = integer(input.get("team_id"), "team_id");
        return transaction(db, true, () -> writeRoute(db, input, teamId, null));
    }

    private static ObjectNode writeRoute(Connection db, ObjectNode input, long teamId, TeamSelection override) throws Exception {
        String day = date(text(input, "date", 10, 10));
        if (input.hasNonNull("stop_label") && !input.get("stop_label").isTextual()) throw invalid("stop_label");
        String stopLabel = RouteStops.stopLabel(input.path("stop_label").textValue());
        if (input.has("urgent") && !input.get("urgent").isBoolean()) throw invalid("urgent");
        boolean urgent = input.path("urgent").asBoolean();
        List<RouteStops.Stop> stops = new ArrayList<>();
        for (JsonNode item : array(input, "addresses", 1, 1000)) {
            if (item.isTextual()) {
                stops.add(new RouteStops.Stop(item.textValue(), ""));
            } else if (item instanceof ObjectNode entry) {
                fields(entry, "address", "external_job_id");
                String externalId = entry.has("external_job_id") ? text(entry, "external_job_id", 0, 120) : "";
                stops.add(new RouteStops.Stop(entry.path("address").textValue(), externalId));
            } else throw invalid("addresses");
        }
        String fingerprint = fingerprint(stops.stream().map(RouteStops.Stop::address).toList());
        Set<Long> vehicleIds = input.has("vehicle_ids") ? ids(input, "vehicle_ids", 0, 100) : Set.of();
        ObjectNode team = one(db, TEAM_ROWS + " WHERE t.id=?", teamId);
        if (team == null) throw new Problem(422, "Select an existing team.");
        long entityId = team.get("entity_id").longValue();
        String entityName = team.get("entity_name").textValue();
        if (override != null) {
            entityId = override.entityId();
            var entity = one(db, "SELECT name FROM entities WHERE id=?", entityId);
            if (entity == null) throw new Problem(422, "Select an existing contracting entity.");
            entityName = entity.get("name").textValue();
        }
        long routeId = uniqueInsertId(db, "This team already has this route on that date.", """
            INSERT INTO routes(date,team_id,team_name,entity_id,entity_name,address_hash) VALUES(?,?,?,?,?,?)
            """, day, teamId, team.get("name").textValue(), entityId, entityName, fingerprint);
        if (override == null) {
            change(db, """
                INSERT INTO assignments(route_id,person_id,name,role,pay_basis,rate_cents)
                SELECT ?,p.id,p.name,p.role,p.pay_basis,p.rate_cents FROM team_members m
                JOIN people p ON p.id=m.person_id WHERE m.team_id=?
                """, routeId, teamId);
        } else {
            selectedMembers(db, """
                INSERT INTO assignments(route_id,person_id,name,role,pay_basis,rate_cents)
                SELECT ?,id,name,role,pay_basis,rate_cents FROM people WHERE id=? AND is_admin=0
                """, routeId, override.members());
        }
        RouteStops.insert(db, routeId, 0, stops, stopLabel, urgent);
        Vehicles.assign(db, routeId, day, vehicleIds);
        return (ObjectNode) routeData(db, " WHERE r.id=?", new Object[]{routeId}, true).get(0);
    }

    // Match the previous Python json.dumps representation, including ASCII escapes and separator spaces.
    static String fingerprint(List<String> addresses) throws Exception {
        StringJoiner list = new StringJoiner(", ", "[", "]");
        for (String address : addresses) {
            String json = DIGEST_WRITER.writeValueAsString(caseFold(address));
            list.add(json.replace(Character.toString(127), "\\u007f"));
        }
        byte[] bytes = list.toString().getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String caseFold(String value) {
        // The original keys use Unicode full case folding. Per-code-point casing avoids contextual
        // final sigma, and upper/lower expansion handles ligatures. Cherokee folds to uppercase.
        StringBuilder folded = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (cp < 128 || cp == 0x131) folded.appendCodePoint(Character.toLowerCase(cp));
            else if (cp == 0x1e9e) folded.append("ss");
            else if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.CHEROKEE)
                folded.appendCodePoint(Character.toUpperCase(cp));
            else folded.append(Character.toString(cp).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
        });
        return folded.toString();
    }

    private static Object[] accessArgs(ObjectNode session, long id) {
        if (session.path("is_admin").asBoolean()) return new Object[]{id};
        return new Object[]{id, Db.today(), session.get("id").longValue()};
    }

    private static ObjectNode allowedJob(Connection db, ObjectNode session, long id) throws SQLException {
        Object[] args = accessArgs(session, id);
        String sql = "SELECT " + JOB_FIELDS + """
                ,x.removed_at,coalesce(x.position,(SELECT count(*) FROM active_route_jobs active
                    WHERE active.route_id=j.route_id AND active.position<=j.position)) AS position
                FROM jobs j JOIN routes r ON r.id=j.route_id LEFT JOIN people p ON p.id=j.submitted_by
                LEFT JOIN job_removals x ON x.job_id=j.id
                WHERE j.id=?
                """;
        if (args.length > 1) sql += WORKER_ROUTE;
        ObjectNode result = one(db, sql, args);
        if (result == null) throw new Problem(404, "Job not found.");
        return result;
    }

    public static void requireJob(Connection db, ObjectNode session, long jobId) throws SQLException {
        allowedJob(db, session, jobId);
    }

    static ObjectNode jobWithPhotos(Connection db, ObjectNode session, long jobId) throws Exception {
        ObjectNode job = prepareJob(allowedJob(db, session, jobId));
        for (JsonNode row : rows(db, "SELECT id FROM photos WHERE job_id=? ORDER BY id", jobId))
            addPhoto(job, row.get("id").longValue());
        return job;
    }

    public record Photo(String mime, byte[] content) {}
    private record ObjectPhoto(String mime, String destination, String key, int size, String hash) {}

    public static Photo photo(Connection db, ObjectNode session, long photoId) throws SQLException {
        return photo(db, session, photoId, PhotoStorage::active);
    }

    static Photo photo(Connection db, ObjectNode session, long photoId,
                       java.util.function.Supplier<S3Photos> provider) throws SQLException {
        Object[] args = accessArgs(session, photoId);
        String sql = """
            SELECT f.mime,CASE WHEN o.photo_id IS NULL THEN f.content END AS content,
                o.destination,o.object_key,o.byte_count,o.sha256
            FROM photos f LEFT JOIN photo_objects o ON o.photo_id=f.id JOIN jobs j ON j.id=f.job_id
            JOIN routes r ON r.id=j.route_id WHERE f.id=?
            """;
        if (args.length > 1) sql += WORKER_ROUTE;
        ObjectPhoto metadata;
        try (var stmt = db.prepareStatement(sql)) {
            bind(stmt, args);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new Problem(404, "Photo not found.");
                String mime = rs.getString("mime");
                String destination = rs.getString("destination");
                if (destination == null) return new Photo(mime, rs.getBytes("content"));
                metadata = new ObjectPhoto(mime, destination, rs.getString("object_key"), rs.getInt("byte_count"), rs.getString("sha256"));
            }
        }
        S3Photos store = provider.get();
        if (store == null || !metadata.destination().equals(store.destination)) throw S3Photos.unavailable();
        return new Photo(metadata.mime(), store.get(metadata.key(), metadata.size(), metadata.hash()));
    }

    public static ObjectNode report(Connection db, ObjectNode session, long jobId, ObjectNode input, List<Part> photos)
            throws Exception {
        return report(db, session, jobId, input, photos, photos.isEmpty() ? null : PhotoStorage.active());
    }

    static ObjectNode report(Connection db, ObjectNode session, long jobId, ObjectNode input, List<Part> photos, S3Photos store)
            throws Exception {
        fields(input, "feet_buried","fiber_type","bores","conduits","items","tech_needed","comments",
            "outcome","outcome_reason","keep_photo_ids","revision");
        Double feet = input.hasNonNull("feet_buried") ? decimal(input, "feet_buried").doubleValue() : null;
        String fiber = input.hasNonNull("fiber_type") ? text(input, "fiber_type", 1, 120) : null;
        Long revision = input.hasNonNull("revision") ? integer(input.get("revision"), "revision") : null;
        if (revision != null && revision < 0) throw invalid("revision");
        Set<Long> requested = input.hasNonNull("keep_photo_ids") ? ids(input, "keep_photo_ids", 0, 10) : null;
        String bores = input.has("bores") ? surfaceRuns(input, "bores", BORE_TYPES).toString() : null;
        String conduits = input.has("conduits") ? surfaceRuns(input, "conduits", CONDUIT_TYPES).toString() : null;
        String items = input.has("items") ? reportItems(input).toString() : null;
        String comments = input.has("comments") ? text(input, "comments", 0, 4000) : null;
        JsonNode tech = input.get("tech_needed");
        if (tech != null && !tech.isNull() && !tech.isBoolean()) throw invalid("tech_needed");
        Integer techNeeded = tech == null || tech.isNull() ? null : (tech.booleanValue() ? 1 : 0);
        if (photos.size() > 10) throw new Problem(422, "Each job can have up to 10 photos.");
        for (Part photo : photos) {
            if (photo.getSize() < 1 || photo.getSize() > PHOTO_LIMIT)
                throw new Problem(422, "Each photo must be between 1 byte and 12 MiB.");
        }
        List<PhotoStorage.Upload> uploads;
        if (store != null && !photos.isEmpty()) {
            transaction(db, false, () -> reportChanges(db, session, jobId, input, revision, requested, photos.size()));
            uploads = PhotoStorage.stage(db, store, photos);
        } else uploads = List.of();
        return transaction(db, true, () -> {
            var changes = reportChanges(db, session, jobId, input, revision, requested, photos.size());
            for (long id : changes.removedPhotos())
                change(db, "DELETE FROM photos WHERE id=? AND job_id=?", id, jobId);
            if (store != null) PhotoStorage.publish(db, jobId, store, uploads);
            else try (var stmt = db.prepareStatement("INSERT INTO photos(job_id,mime,content) VALUES(?,?,?)")) {
                for (Part photo : photos) {
                    try (var stream = new BufferedInputStream(photo.getInputStream())) {
                        stream.mark(16);
                        String mime = mime(stream.readNBytes(12));
                        stream.reset();
                        stmt.setLong(1, jobId);
                        stmt.setString(2, mime);
                        stmt.setBinaryStream(3, stream, Math.toIntExact(photo.getSize()));
                        stmt.executeUpdate();
                    }
                }
            }
            long actor = session.get("id").longValue();
            String now = Instant.now().toString();
            change(db, """
                UPDATE jobs SET feet_buried=CASE WHEN ? THEN ? ELSE feet_buried END,
                    fiber_type=CASE WHEN ? THEN ? ELSE fiber_type END,bores=coalesce(?,bores),conduits=coalesce(?,conduits),
                    items=coalesce(?,items),comments=coalesce(?,comments),
                    tech_needed=CASE WHEN ? THEN ? ELSE tech_needed END,outcome=?,outcome_reason=?,
                    last_edited_by=CASE WHEN submitted_at IS NOT NULL THEN ? ELSE last_edited_by END,
                    last_edited_at=CASE WHEN submitted_at IS NOT NULL THEN ? ELSE last_edited_at END,
                    submitted_by=COALESCE(submitted_by,?),submitted_at=COALESCE(submitted_at,?),revision=revision+1
                WHERE id=?
                """, input.has("feet_buried"), feet, input.has("fiber_type"), fiber, bores, conduits, items, comments,
                tech != null, techNeeded, changes.outcome(), changes.reason(), actor, now, actor, now, jobId);
            return jobWithPhotos(db, session, jobId);
        });
    }

    private static ArrayNode surfaceRuns(ObjectNode input, String key, Set<String> allowedTypes) {
        ArrayNode runs = App.JSON.createArrayNode();
        for (JsonNode entry : array(input, key, 0, 500)) {
            if (!(entry instanceof ObjectNode row)) throw invalid(key);
            fields(row, "type", "feet");
            String surface = text(row, "type", 1, 20);
            if (!allowedTypes.contains(surface)) throw invalid(key + ".type");
            runs.addObject().put("type", surface).put("feet", decimal(row, "feet"));
        }
        return runs;
    }

    private static ArrayNode reportItems(ObjectNode input) {
        ArrayNode items = App.JSON.createArrayNode();
        for (JsonNode entry : array(input, "items", 0, 500)) {
            if (!(entry instanceof ObjectNode row)) throw invalid("items");
            fields(row, "item", "quantity", "unit");
            String item = text(row, "item", 1, 250);
            String unit = text(row, "unit", 0, 80);
            BigDecimal quantity = decimal(row, "quantity");
            if (quantity.signum() == 0) throw invalid("quantity");
            items.addObject().put("item", item).put("quantity", quantity).put("unit", unit);
        }
        return items;
    }

    private record ReportChanges(String outcome, String reason, Set<Long> removedPhotos) {}

    private static ReportChanges reportChanges(Connection db, ObjectNode session, long jobId, ObjectNode input,
                                               Long revision, Set<Long> requested, int additions) throws SQLException {
        ObjectNode job = allowedJob(db, session, jobId);
        if (revision != null && revision != job.get("revision").longValue())
            throw new Problem(409, "This report was updated. Reload it before saving.");
        String previousOutcome = job.path("outcome").textValue();
        String outcome = input.has("outcome") ? text(input, "outcome", 1, 20)
            : java.util.Objects.requireNonNullElse(previousOutcome, "done");
        String reason = input.hasNonNull("outcome_reason") ? text(input, "outcome_reason", 0, 4000) : null;
        switch (outcome) {
            case "done" -> {
                if (!input.hasNonNull("feet_buried")) throw invalid("feet_buried");
                if (!input.hasNonNull("fiber_type")) throw invalid("fiber_type");
                reason = null;
            }
            case "pending", "cancel" -> {
                if (!input.has("outcome_reason") && outcome.equals(previousOutcome))
                    reason = job.path("outcome_reason").textValue();
                if (reason == null || reason.isBlank()) throw new Problem(422, "Enter a reason for this job outcome.");
            }
            default -> throw invalid("outcome");
        }
        Set<Long> existing = new HashSet<>();
        for (JsonNode row : rows(db, "SELECT id FROM photos WHERE job_id=?", jobId))
            existing.add(row.get("id").longValue());
        Set<Long> keep = requested == null ? existing : requested;
        if (!existing.containsAll(keep)) throw new Problem(409, "The photo selection has changed. Reload the report.");
        if (keep.size() + additions > 10) throw new Problem(422, "Each job can have up to 10 photos.");
        existing.removeAll(keep);
        return new ReportChanges(outcome, reason, existing);
    }

    static String mime(byte[] header) {
        if (header.length >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255)
            return "image/jpeg";
        byte[] png = {(byte)137,80,78,71,13,10,26,10};
        if (header.length >= 8 && Arrays.equals(header,0,8,png,0,8)) return "image/png";
        String magic = new String(header, StandardCharsets.ISO_8859_1);
        if (magic.startsWith("RIFF") && magic.startsWith("WEBP", 8)) return "image/webp";
        throw new Problem(422, "Upload a JPEG, PNG, or WebP photo.");
    }
}
