package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;

final class JobReportWorkbook {
    private static final String[] JOB_COLUMNS = {
        "Work date", "Route ID", "Stop", "Field Routes job ID", "Job ID", "Address", "Team", "Contracting entity",
        "Assigned crew", "Stop type", "Urgent", "Status", "Outcome", "Outcome reason", "Tech needed", "Feet buried",
        "Cable / fiber type", "Comments", "Submitted by", "Submitted at (UTC)", "Last edited by", "Last edited at (UTC)",
        "Removed", "Removed at (UTC)", "Photos"
    };
    private static final String[] DETAIL_COLUMNS = {"Work date", "Field Routes job ID", "Job ID", "Address", "Team", "Contracting entity"};
    private JobReportWorkbook() {}

    static void write(OutputStream out, ArrayNode reports) throws IOException {
        write(out, reports, false);
    }

    static void write(OutputStream out, ArrayNode reports, boolean includePhotoFolders) throws IOException {
        ExcelWorkbook.write(out,
            new ExcelWorkbook.Sheet("Jobs", rows -> jobs(rows, reports, includePhotoFolders)),
            new ExcelWorkbook.Sheet("Crew", rows -> crew(rows, reports)),
            new ExcelWorkbook.Sheet("Bores", rows -> measurements(rows, reports, "bores")),
            new ExcelWorkbook.Sheet("Conduits", rows -> measurements(rows, reports, "conduits")),
            new ExcelWorkbook.Sheet("Items", rows -> items(rows, reports)),
            new ExcelWorkbook.Sheet("Notes", rows -> notes(rows, reports)));
    }

    /** Both the workbook and outer photo ZIP use this path. Only validated application IDs enter archive names. */
    static String photoFolder(JsonNode report) {
        long id = report.path("job").path("id").asLong();
        if (id < 1) throw new IllegalArgumentException("Missing Field Routes job ID.");
        LocalDate day = LocalDate.parse(report.path("route").path("date").asText());
        return "photos/" + day + "/job-" + id + "/";
    }

    private static void jobs(ExcelWorkbook.Rows rows, ArrayNode reports, boolean folders) throws IOException {
        row(rows, JOB_COLUMNS, folders ? new Object[]{"Photo folder"} : new Object[0]);
        for (var report : reports) {
            var route = report.path("route");
            var job = report.path("job");
            int photoCount = job.path("photos").size();
            Object[] cells = {
                route.get("date"), route.path("id").asText(), job.get("position"), job.path("id").asText(),
                job.path("external_job_id").asText(""), job.get("address"), route.get("team_name"), route.get("entity_name"),
                crewSummary(route.path("crew_names")), job.get("stop_label"), yesNo(job.get("urgent")), job.get("status"),
                job.get("outcome"), job.get("outcome_reason"), yesNo(job.get("tech_needed")), job.get("feet_buried"),
                job.get("fiber_type"), job.get("comments"), job.get("submitted_by"), job.get("submitted_at"),
                job.get("last_edited_by"), job.get("last_edited_at"), job.hasNonNull("removed_at") ? "Yes" : "No",
                job.get("removed_at"), photoCount
            };
            if (folders && photoCount > 0) row(rows, cells, photoFolder(report));
            else rows.write(cells);
        }
    }

    private static String yesNo(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return value.asBoolean() ? "Yes" : "No";
    }

    private static String crewSummary(JsonNode names) {
        var summary = new StringBuilder();
        for (var name : names) {
            if (!summary.isEmpty()) summary.append(", ");
            summary.append(name.asText());
            // Excel allows 32,767 characters per cell. The Crew sheet always contains the complete snapshot.
            if (summary.length() > 32767) return "See Crew sheet (" + names.size() + " members)";
        }
        return summary.toString();
    }

    private static void crew(ExcelWorkbook.Rows rows, ArrayNode reports) throws IOException {
        rows.write("Work date", "Route ID", "Team", "Contracting entity", "Contractor", "Contractor ID", "Role");
        var seenRoutes = new HashSet<Long>();
        for (var report : reports) {
            var route = report.path("route");
            if (!seenRoutes.add(route.path("id").asLong())) continue;
            var identity = new Object[]{route.get("date"), route.path("id").asText(), route.get("team_name"), route.get("entity_name")};
            if (route.path("members").isArray()) {
                for (var member : route.get("members"))
                    row(rows, identity, member.get("name"), member.path("id").asText(), member.get("role"));
            } else {
                for (var name : route.path("crew_names")) row(rows, identity, name);
            }
        }
    }

    private static Object[] detailIdentity(JsonNode report) {
        var route = report.path("route");
        var job = report.path("job");
        return new Object[]{route.get("date"), job.path("id").asText(), job.path("external_job_id").asText(""),
            job.get("address"), route.get("team_name"), route.get("entity_name")};
    }

    private static void measurements(ExcelWorkbook.Rows rows, ArrayNode reports, String field) throws IOException {
        row(rows, DETAIL_COLUMNS, "Line", "Type", "Feet");
        for (var report : reports) {
            var identity = detailIdentity(report);
            int line = 0;
            for (var measurement : report.path("job").path(field))
                row(rows, identity, ++line, measurement.get("type"), measurement.get("feet"));
        }
    }

    private static void items(ExcelWorkbook.Rows rows, ArrayNode reports) throws IOException {
        row(rows, DETAIL_COLUMNS, "Line", "Item", "Quantity", "Unit");
        for (var report : reports) {
            var identity = detailIdentity(report);
            int line = 0;
            for (var item : report.path("job").path("items"))
                row(rows, identity, ++line, item.get("item"), item.get("quantity"), item.get("unit"));
        }
    }

    private static void notes(ExcelWorkbook.Rows rows, ArrayNode reports) throws IOException {
        row(rows, DETAIL_COLUMNS, "Note ID", "Created at (UTC)", "Author", "Note");
        for (var report : reports) {
            var identity = detailIdentity(report);
            for (var note : report.path("job").path("notes"))
                row(rows, identity, note.path("id").asText(), note.get("created_at"), note.get("author"), note.get("text"));
        }
    }

    private static void row(ExcelWorkbook.Rows rows, Object[] prefix, Object... detail) throws IOException {
        // Keep an Object[] even when prefix is a String[] of column headings.
        Object[] cells = Arrays.copyOf(prefix, prefix.length + detail.length, Object[].class);
        System.arraycopy(detail, 0, cells, prefix.length, detail.length);
        rows.write(cells);
    }
}
