package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static fieldops.StoreSql.*;

final class JobReportData {
    private JobReportData() {}

    static void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.getYear() < 1 || end.getYear() > 9999 || end.isBefore(start))
            throw new Problem(422, "Select a valid start and end date in chronological order.");
        LocalDate nextMonth = start.plusMonths(1);
        // When the start day does not exist next month, the range can run through that month's end.
        LocalDate lastDay = nextMonth.getDayOfMonth() == start.getDayOfMonth() ? nextMonth.minusDays(1) : nextMonth;
        if (end.isAfter(lastDay)) throw new Problem(422, "Select a date range of up to one calendar month.");
    }

    static ArrayNode load(Connection db, ObjectNode admin, LocalDate start, LocalDate end) throws Exception {
        if (admin == null || !admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        validateRange(start, end);
        return transaction(db, false, () -> {
            var result = App.JSON.createArrayNode();
            Map<Long,ObjectNode> photos = new HashMap<>();
            ObjectNode route = null;
            for (var row : rows(db, """
                    SELECT j.id,j.route_id FROM routes r JOIN jobs j ON j.route_id=r.id
                    WHERE r.date BETWEEN ? AND ? AND j.submitted_at IS NOT NULL
                    ORDER BY r.date,lower(r.team_name),r.id,j.position,j.id
                    """, start.toString(), end.toString())) {
                long jobId = row.get("id").longValue();
                ObjectNode job = JobDetails.jobWithNotes(db, admin, jobId);
                job.put("archived", job.hasNonNull("removed_at"));
                if (route == null || route.get("id").longValue() != row.get("route_id").longValue())
                    route = JobDetails.routeForJob(db, jobId, true);
                ObjectNode report = result.addObject();
                report.set("route", route);
                report.set("job", job);
                for (var photo : job.get("photos")) photos.put(photo.get("id").longValue(), (ObjectNode) photo);
            }
            if (!photos.isEmpty()) {
                // SQLite gets the length of a BLOB from its record header without loading its contents.
                try (var stmt = db.prepareStatement("""
                        SELECT f.id,f.mime,coalesce(o.byte_count,length(f.content)) AS byte_count
                        FROM routes r JOIN jobs j ON j.route_id=r.id JOIN photos f ON f.job_id=j.id
                        LEFT JOIN photo_objects o ON o.photo_id=f.id
                        WHERE r.date BETWEEN ? AND ? AND j.submitted_at IS NOT NULL
                        """)) {
                    bind(stmt, start.toString(), end.toString());
                    try (var metadata = stmt.executeQuery()) {
                        while (metadata.next()) {
                            ObjectNode photo = photos.get(metadata.getLong("id"));
                            photo.put("mime", metadata.getString("mime"));
                            photo.put("byte_count", metadata.getLong("byte_count"));
                        }
                    }
                }
            }
            return result;
        });
    }
}
