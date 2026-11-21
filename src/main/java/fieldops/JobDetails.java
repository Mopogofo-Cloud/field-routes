package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import static fieldops.StoreSql.*;

/** Stop-level edits and crew-visible dispatch notes. */
final class JobDetails {
    private static final String NOTE_SELECT =
        "SELECT id,text,author_name AS author,created_at FROM job_notes";

    private JobDetails() {}

    static ObjectNode get(Connection db, ObjectNode person, long jobId) throws Exception {
        return transaction(db, false, () -> {
            ObjectNode job = jobWithNotes(db, person, jobId);
            var context = App.JSON.createObjectNode();
            context.set("route", routeForJob(db, jobId, false));
            context.set("job", job);
            return context;
        });
    }

    static ObjectNode jobWithNotes(Connection db, ObjectNode person, long jobId) throws Exception {
        ObjectNode job = Store.jobWithPhotos(db, person, jobId);
        job.set("notes", rows(db, NOTE_SELECT + " WHERE job_id=? ORDER BY id", jobId));
        return job;
    }

    // Read the assignment snapshot after authorizing the job, within the caller's transaction.
    static ObjectNode routeForJob(Connection db, long jobId, boolean includeMembers) throws SQLException {
        ObjectNode route = one(db, """
            SELECT r.id,r.date,r.team_id,r.team_name,r.entity_id,r.entity_name
            FROM routes r JOIN jobs j ON j.route_id=r.id WHERE j.id=?
            """, jobId);
        var members = rows(db, """
            SELECT person_id AS id,name,role FROM assignments WHERE route_id=? ORDER BY person_id
            """, route.get("id").asLong());
        var crew = route.putArray("crew_names");
        for (var member : members) crew.add(member.get("name").asText());
        if (includeMembers) route.set("members", members);
        return route;
    }

    static ObjectNode addNote(Connection db, ObjectNode person, long jobId, String rawText) throws SQLException {
        requireAdmin(person);
        String text = checkedText(rawText, "note", 4000);
        return transaction(db, true, () -> {
            Store.requireJob(db, person, jobId);
            var note = one(db, """
                INSERT INTO job_notes(job_id,author_id,author_name,text,created_at)
                SELECT ?,id,name,?,? FROM people WHERE id=? AND is_admin=1
                RETURNING id,text,author_name AS author,created_at
                """, jobId, text, Instant.now().toString(), person.path("id").asLong());
            if (note == null) throw new Problem(403, "Administrator access required.");
            return note;
        });
    }

    static ObjectNode updateAddress(Connection db, ObjectNode person, long jobId, String rawAddress, long revision) throws Exception {
        return updateAddress(db, person, jobId, rawAddress, revision, null);
    }

    static ObjectNode updateAddress(Connection db, ObjectNode person, long jobId, String rawAddress, long revision,
                                    String externalId) throws Exception {
        requireAdmin(person);
        String address = checkedText(rawAddress, "address", 500);
        if (revision < 0) throw StoreInput.invalid("revision");
        return transaction(db, true, () -> {
            ObjectNode row = one(db, """
                SELECT j.address,j.external_job_id,j.revision,j.route_id,x.removed_at FROM jobs j
                LEFT JOIN job_removals x ON x.job_id=j.id WHERE j.id=?
                """, jobId);
            if (row == null) throw new Problem(404, "Job not found.");
            if (row.get("revision").asLong() != revision) throw new Problem(409, "This stop changed. Open the latest version before editing.");
            boolean addressChanged = !row.get("address").textValue().equals(address);
            String oldId = row.get("external_job_id").textValue();
            String newId = externalId == null ? oldId : RouteStops.externalJobId(externalId);
            if (addressChanged || !oldId.equals(newId)) {
                long routeId = row.get("route_id").asLong();
                change(db, "UPDATE jobs SET address=?,external_job_id=?,revision=revision+1 WHERE id=?", address, newId, jobId);
                if (!row.hasNonNull("removed_at")) {
                    if (addressChanged) RouteStops.updateRoute(db, routeId);
                    else change(db, "UPDATE routes SET revision=revision+1 WHERE id=?", routeId);
                }
            }
            return Store.jobWithPhotos(db, person, jobId);
        });
    }

    private static void requireAdmin(ObjectNode person) {
        if (!person.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
    }

    private static String checkedText(String raw, String field, int max) {
        if (raw == null) throw StoreInput.invalid(field);
        String value = raw.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > max) throw StoreInput.invalid(field);
        return value;
    }
}
