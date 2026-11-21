package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static fieldops.Views.*;

final class JobReportExport {
    static final String PATH = "/job-reports";
    static final String DOWNLOAD = PATH + "/export";
    static final String SAMPLE = PATH + "/sample.zip";
    private static final String SAMPLE_FILENAME = "Field-Routes-Sample-Job-Report.zip";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Semaphore EXPORTS = new Semaphore(2);

    private JobReportExport() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user) throws Exception {
        if (!user.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        res.setHeader("Cache-Control", "private, no-store");
        boolean post = req.getMethod().equals("POST");
        if (SAMPLE.equals(req.getRequestURI())) {
            if (post) throw new Problem(405, "Open the sample ZIP download link.");
            var resource = JobReportExport.class.getResource("/web/" + SAMPLE_FILENAME);
            if (resource == null) throw new IOException("Missing sample job report ZIP.");
            var archive = resource.openConnection();
            res.setCharacterEncoding((String) null);
            res.setContentType("application/zip");
            res.setHeader("Content-Disposition", "attachment; filename=\"" + SAMPLE_FILENAME + "\"");
            if (archive.getContentLengthLong() >= 0) res.setContentLengthLong(archive.getContentLengthLong());
            if (!req.getMethod().equals("HEAD")) {
                try (var file = archive.getInputStream()) { file.transferTo(res.getOutputStream()); }
            }
            return;
        }
        if (PATH.equals(req.getRequestURI()) && !post) {
            var today = LocalDate.parse(Db.today());
            form(res, user, today.withDayOfMonth(1).toString(), today.toString(), false, "");
            return;
        }
        if (!DOWNLOAD.equals(req.getRequestURI()) || !post) throw new Problem(405, "Use the job reports export form.");
        String from = param(req, "start"), through = param(req, "end");
        boolean photos = "1".equals(param(req, "photos"));
        try {
            if (from.isBlank() || through.isBlank()) throw new Problem(422, "Select both work dates.");
            LocalDate start = LocalDate.parse(StoreInput.date(from)), end = LocalDate.parse(StoreInput.date(through));
            if (!EXPORTS.tryAcquire()) throw new Problem(503, "Other exports are in progress. Try again shortly.");
            try {
                ArrayNode reports = JobReportData.load(db, user, start, end);
                if (reports.isEmpty()) {
                    form(res, user, from, through, photos, notice("No submitted job reports for these work dates."));
                    return;
                }
                String name = "field-routes-job-reports-" + start + "-to-" + end;
                download(res, db, user, reports, photos, name);
            } finally {
                EXPORTS.release();
            }
        } catch (Problem problem) {
            res.setStatus(problem.status);
            form(res, user, from, through, photos, alert(problem.getMessage()));
        }
    }

    private static void form(HttpServletResponse res, ObjectNode user, String from, String through,
                             boolean photos, String feedback) throws IOException {
        page(res, user, "Job reports", AdminViews.navForPath(PATH) + "<div class='section-head'><h1>Job reports</h1></div>" +
            "<form class='panel stack editor' method='post' action='" + DOWNLOAD + "'>" + feedback +
            "<p>Select up to one month of route work dates. The workbook includes submitted reports from every team.</p>" +
            "<div class='two'>" + field("Start date", "start", from, "type='date' required") +
            field("End date", "end", through, "type='date' required") + "</div>" +
            "<label class='urgent-check'><input type='checkbox' name='photos' value='1'" + (photos ? " checked" : "") +
            "> Include uploaded photos</label><p class='helper'>With photos, you’ll get a ZIP containing the Excel workbook and the original images organized by job.</p>" +
            "<div class='actions'><button>Export job reports</button>" +
            link("Download sample ZIP", SAMPLE, "btn secondary") + "</div></form>");
    }

    private static void download(HttpServletResponse res, Connection db, ObjectNode user, ArrayNode reports,
                                 boolean photos, String name) throws Exception {
        // Finish the archive before sending headers. A missing cloud photo can then be retried as a whole export.
        var temporary = Files.createTempFile("field-routes-reports-", ".tmp");
        try (var file = FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)) {
            var output = new FilterOutputStream(Channels.newOutputStream(file)) {
                @Override public void write(byte[] bytes, int start, int length) throws IOException { out.write(bytes, start, length); }
                @Override public void close() throws IOException { flush(); }
            };
            if (photos) writeArchive(output, reports, name + ".xlsx", id -> Store.photo(db, user, id));
            else JobReportWorkbook.write(output, reports);
            file.position(0);
            res.setCharacterEncoding((String) null);
            res.setContentType(photos ? "application/zip" : XLSX);
            res.setHeader("Content-Disposition", "attachment; filename=\"" + name + (photos ? ".zip\"" : ".xlsx\""));
            res.setContentLengthLong(file.size());
            Channels.newInputStream(file).transferTo(res.getOutputStream());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    interface PhotoReader { Store.Photo read(long id) throws SQLException; }

    static void writeArchive(OutputStream output, ArrayNode reports, String workbookName, PhotoReader photos) throws Exception {
        try (var zip = new ZipOutputStream(output)) {
            // The spreadsheet and image formats are already compressed.
            zip.setLevel(0);
            zip.putNextEntry(new ZipEntry(workbookName));
            JobReportWorkbook.write(zip, reports, true);
            zip.closeEntry();
            for (var report : reports) {
                for (var ref : report.path("job").path("photos")) {
                    long id = ref.path("id").longValue();
                    Store.Photo image;
                    try {
                        image = photos.read(id);
                    } catch (Problem problem) {
                        if (problem.status == 404) throw new Problem(409, "A job photo changed during export. Export the date range again.");
                        throw problem;
                    }
                    String extension = switch (image.mime()) {
                        case "image/jpeg" -> ".jpg";
                        case "image/png" -> ".png";
                        case "image/webp" -> ".webp";
                        default -> ".bin";
                    };
                    zip.putNextEntry(new ZipEntry(JobReportWorkbook.photoFolder(report) + "photo-" + id + extension));
                    zip.write(image.content());
                    zip.closeEntry();
                }
            }
        }
    }
}
