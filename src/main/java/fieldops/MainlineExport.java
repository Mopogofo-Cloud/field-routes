package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import static fieldops.Views.*;

final class MainlineExport {
    private static final Semaphore EXPORTS = new Semaphore(2);
    private MainlineExport() {}

    static String form(String projectPath, ArrayNode days) {
        var out = new StringBuilder("<form class='panel stack' method='post' action='")
            .append(h(projectPath)).append("/export'><h2>Export project report</h2>")
            .append("<fieldset><legend>Dailies to include</legend>");
        for (var day : days) {
            String date = h(value(day, "date"));
            long id = day.path("id").asLong();
            out.append("<label class='urgent-check'><input type='checkbox' name='daily' value='").append(id)
                .append("'>").append(date).append(" · Daily #").append(id).append(" · ")
                .append(h(value(day,"total_footage"))).append(" ft</label>");
        }
        return out.append("</fieldset><label class='urgent-check'><input type='checkbox' name='images' value='1'>Include images</label>")
            .append("<div class='actions'><button name='scope' value='selected'>Export selected</button>")
            .append("<button class='secondary' name='scope' value='all'>Export all dailies</button></div></form>").toString();
    }

    static void download(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user, long projectId) throws Exception {
        requireAdmin(user);
        String scope = param(req, "scope");
        if (!scope.equals("all") && !scope.equals("selected")) throw new Problem(422, "Choose dailies to export.");
        Set<Long> selected = new HashSet<>();
        if (scope.equals("selected")) for (var raw : params(req, "daily")) selected.add(id(raw));
        if (!EXPORTS.tryAcquire()) throw new Problem(503, "Other project exports are in progress. Try again shortly.");
        try {
            var report = load(db, user, projectId, scope.equals("all") ? null : selected, "1".equals(param(req,"images")));
            var temporary = Files.createTempFile("field-routes-mainline-", ".xlsx");
            try (var file = FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)) {
                write(Channels.newOutputStream(file), report, (daily,photo) -> {
                    try { return MainlineMedia.photo(db, user, daily, photo); }
                    catch (Problem e) {
                        if (e.status == 404) throw new Problem(409,"A selected photo changed. Export the project again.");
                        throw e;
                    }
                    catch (Exception e) { throw new IOException("Could not read a project photo.", e); }
                });
                var name = value(report.project(), "name").replaceAll("[^a-zA-Z0-9_-]+", "_");
                if (name.isBlank() || name.equals("_")) name = "project_" + projectId;
                if (name.length() > 110) name = name.substring(0, 110);
                file.position(0);
                res.setCharacterEncoding((String)null);
                res.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                res.setHeader("Cache-Control", "private, no-store");
                res.setHeader("Content-Disposition", "attachment; filename=\"" + name + "_report.xlsx\"");
                res.setContentLengthLong(file.size());
                Channels.newInputStream(file).transferTo(res.getOutputStream());
            } finally { Files.deleteIfExists(temporary); }
        } finally { EXPORTS.release(); }
    }

    record Report(ObjectNode project, List<JsonNode> days, List<ExcelPictures.Picture> images) {}

    private static void requireAdmin(ObjectNode user) {
        if (user == null || !user.path("is_admin").asBoolean()) throw new Problem(403,"Administrator access required.");
    }

    static Report load(Connection db, ObjectNode user, long projectId, Set<Long> selected, boolean images) throws SQLException {
        requireAdmin(user);
        return StoreSql.transaction(db, false, () -> snapshot(db, projectId, selected, images));
    }

    private static Report snapshot(Connection db, long projectId, Set<Long> selected, boolean images) throws SQLException {
        var project = Mainline.readProject(db, projectId);
        var all = Mainline.readProjectDailies(db, projectId);
        var days = new ArrayList<JsonNode>();
        var ids = new HashSet<Long>();
        for (var day : all) {
            long id = day.path("id").asLong();
            if (selected == null || selected.contains(id)) { days.add(day); ids.add(id); }
        }
        if (selected != null && !ids.containsAll(selected)) throw new Problem(422, "Choose dailies from this project.");
        if (days.isEmpty()) throw new Problem(422, "Select at least one daily to export.");
        List<ExcelPictures.Picture> pictures = new ArrayList<>();
        if (images) {
            // Fetch metadata only. Each original is read and checked independently while the workbook is written.
            try (var stmt = db.prepareStatement("""
                    SELECT m.daily_id,d.date,m.category,m.id FROM mainline_media m JOIN mainline_dailies d ON d.id=m.daily_id
                    WHERE d.project_id=? ORDER BY d.date DESC,d.id DESC,m.category,m.id
                    """)) {
                stmt.setLong(1, projectId);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) if (ids.contains(rs.getLong(1)))
                        pictures.add(new ExcelPictures.Picture(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)));
                }
            }
        }
        return new Report(project, days, pictures);
    }

    static void write(OutputStream out, Report report, ExcelPictures.PhotoReader reader) throws IOException {
        var p = report.project();
        var sheets = new ArrayList<ExcelWorkbook.Sheet>();
        sheets.add(new ExcelWorkbook.Sheet("Project", rows -> {
            rows.write("Project", "Location", "Supervisor", "State", "Lunch provided", "Estimated footage (ft)",
                "Project total (ft)", "Selected footage (ft)", "Driver cost ($)", "Price per foot ($)", "Due date");
            var footage = report.days().stream().map(day -> day.path("total_footage").decimalValue())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            rows.write(p.get("name"),p.get("location"),p.get("supervisor"),p.get("state"),
                p.path("lunch_provided").asBoolean() ? "Yes" : "No",p.get("estimated_footage"),p.get("total_footage"),
                footage,p.get("driver_cost"),p.get("price_per_foot"),p.get("due_date"));
        }));
        sheets.add(new ExcelWorkbook.Sheet("Dailies", rows -> {
            rows.write("Daily ID","Date","Total footage (ft)","Priority","Comments");
            for (var d : report.days()) rows.write(d.get("id"),d.get("date"),d.get("total_footage"),d.get("priority"),d.get("comments"));
        }));
        sheets.add(new ExcelWorkbook.Sheet("Crew", rows -> {
            rows.write("Daily ID","Date","Crew member","Driver");
            for (var d : report.days()) for (var member : d.path("members"))
                rows.write(d.get("id"),d.get("date"),member.get("name"),member.path("is_driver").asBoolean() ? "Yes" : "No");
        }));
        sheets.add(new ExcelWorkbook.Sheet("Vehicles", rows -> {
            rows.write("Daily ID","Date","Vehicle","Brand","Plate or VIN");
            for (var d : report.days()) for (var vehicle : d.path("vehicles"))
                rows.write(d.get("id"),d.get("date"),vehicle.get("name"),vehicle.get("brand"),vehicle.get("plate"));
        }));
        sheets.add(new ExcelWorkbook.Sheet("Expenses", rows -> {
            rows.write("Daily ID","Date","Expense type","Cost ($)");
            for (var d : report.days()) for (var expense : d.path("expenses"))
                rows.write(d.get("id"),d.get("date"),expense.get("type"),expense.get("cost"));
        }));
        if (!report.images().isEmpty()) sheets.add(ExcelPictures.sheet(report.images(), reader));
        ExcelWorkbook.write(out, sheets.toArray(ExcelWorkbook.Sheet[]::new));
    }
}
