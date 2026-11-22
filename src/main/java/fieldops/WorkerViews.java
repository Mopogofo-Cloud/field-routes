package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static fieldops.Views.*;

final class WorkerViews {
    private WorkerViews() {}

    static void list(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user) throws Exception {
        ArrayNode routes = Store.routes(db, user, null);
        ArrayNode assignments = Mainline.workerAssignments(db, user);
        ArrayNode projects = Mainline.supervisedProjects(db, user);
        StringBuilder cards = new StringBuilder();
        int count = 0, submitted = 0;
        for (JsonNode route : routes) {
            JsonNode jobs = route.path("jobs");
            cards.append("<section class='panel worker-route'><div class='worker-route-team'><h2>")
                .append(h(value(route, "team_name"))).append("</h2><p class='muted'>")
                .append(h(value(route, "entity_name"))).append("</p>").append(urgentLink(jobs)).append("</div>");
            for (JsonNode job : jobs) {
                count++;
                if (value(job, "status").equals("submitted")) submitted++;
                cards.append("<div class='worker-stop").append(stopClasses(job)).append("' id='stop-")
                    .append(job.path("id").asLong()).append("'><div class='worker-stop-body'><span class='worker-stop-kicker'>Stop ")
                    .append(h(value(job, "position"))).append("</span><strong>").append(mapAddress(value(job, "address")))
                    .append("</strong>").append(jobIdLine(job)).append(stopBadges(job));
                int noteCount = job.path("note_count").asInt();
                if (noteCount > 0) cards.append(link(noteCount == 1 ? "1 admin note" : noteCount + " admin notes",
                    "/jobs/" + job.path("id").asLong() + "#job-notes", "worker-note-link"));
                cards.append("</div><div class='worker-stop-actions'>").append(badge(job))
                    .append(link("Open report", "/jobs/" + job.path("id").asLong(), "btn secondary")).append("</div></div>");
            }
            cards.append("</section>");
        }
        String day = LocalDate.parse(value(user,"today")).format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US));
        String progress = count == 0 && !assignments.isEmpty() ? "" : count == 0 ?
            "<section class='panel empty'><h2>No routes shown</h2><p class='muted'>You have no route assigned for today.</p></section>" :
            "<div class='worker-progress'><strong>" + submitted + " of " + count + " stops submitted</strong><progress max='" +
                count + "' value='" + submitted + "' aria-label='Stops submitted'></progress></div>";
        page(res, user, "My route", "<div class='worker'><header class='worker-route-header'><p class='eyebrow'>" + h(day) +
            "</p><div class='worker-section-title'><h1>My route</h1>" + link("Refresh route", "/", "btn secondary") +
            "</div></header>" + (param(req,"saved").equals("1") ? notice("Report saved.") : "") +
            supervisedProjects(projects) + progress + cards + mainlineAssignments(assignments) + "</div>");
    }

    private static String mainlineAssignments(ArrayNode assignments) {
        if (assignments.isEmpty()) return "";
        var out = new StringBuilder("<section class='stack'><h2>Mainline assignments</h2>");
        for (var daily : assignments) {
            var crew = new java.util.StringJoiner(", ");
            for (var name : daily.path("crew_names")) crew.add(name.asText());
            out.append("<article class='panel stack'><h3>").append(h(value(daily, "project_name"))).append("</h3><p>")
                .append(h(value(daily, "date"))).append(" · ").append(h(crew.toString())).append("</p>");
            if (!daily.path("documents").isEmpty() || daily.path("can_report").asBoolean()) {
                out.append("<div class='actions'>");
                if (daily.path("can_report").asBoolean())
                    out.append(link("Open daily report", "/mainline/dailies/" + daily.path("id").asLong(), "btn"));
                for (var doc : daily.path("documents")) {
                    String kind = doc.asText();
                    String label = Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " PDF";
                    out.append(link(label, "/mainline/projects/" + daily.path("project_id").asLong() + "/documents/" + kind,
                        "btn secondary"));
                }
                out.append("</div>");
            }
            out.append("</article>");
        }
        return out.append("</section>").toString();
    }

    private static String supervisedProjects(ArrayNode projects) {
        if (projects.isEmpty()) return "";
        var cards = new StringBuilder("<section class='stack'><h2>Projects I supervise</h2>");
        for (JsonNode project : projects) {
            String location = value(project, "location");
            cards.append("<a class='panel route-section' href='").append(MainlineViews.projectPath(project))
                .append("'><strong>").append(h(value(project, "name"))).append("</strong><span>")
                .append(h(value(project, "state"))).append(location.isEmpty() ? "" : " · " + h(location))
                .append("</span></a>");
        }
        return cards.append("</section>").toString();
    }

    private static String urgentLink(JsonNode jobs) {
        long first = 0;
        int count = 0;
        for (JsonNode job : jobs) {
            if (job.path("urgent").asBoolean()) {
                if (count == 0) first = job.path("id").asLong();
                count++;
            }
        }
        if (count == 0) return "";
        return link(count + (count == 1 ? " urgent stop" : " urgent stops"), "#stop-" + first, "urgent-jump");
    }

    static void job(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode user, long id) throws Exception {
        // Load the authorized job before parsing fields or multipart attachments.
        ObjectNode context = JobDetails.get(db, user, id);
        JsonNode route = context.path("route"), job = context.path("job");
        boolean admin = user.path("is_admin").asBoolean();
        String path = "/jobs/" + id;
        String teamLink = teamRoutesPath(route.path("team_id").asLong(), value(route,"date"));
        ObjectNode draft = App.JSON.createObjectNode();
        for (String key : ReportForm.FIELDS) draft.set(key, job.path(key).deepCopy());
        boolean photos = false;
        String problem = "";
        if (req.getMethod().equals("POST")) {
            String step = param(req, "step");
            if (step.equals("note") || step.equals("address")) {
                if (!admin) throw new Problem(403, "Administrator access required.");
                try {
                    if (step.equals("note")) {
                        JobDetails.addNote(db, user, id, param(req, "note"));
                        redirect(res, path + "?saved=note#job-notes");
                    } else {
                        JobDetails.updateAddress(db, user, id, param(req, "address"), id(param(req, "revision")), req.getParameter("external_job_id"));
                        redirect(res, path + "?saved=address");
                    }
                    return;
                } catch (Problem err) {
                    res.setStatus(err.status);
                    problem = err.getMessage();
                }
            } else if (step.equals("save") || step.equals("measurements")) {
                draft = ReportForm.readDraft(req, job);
                photos = step.equals("save");
                if (photos || "1".equals(param(req, "photo_selection"))) ReportForm.readPhotoSelection(req, draft);
                if (photos) {
                    try {
                        List<Part> files = new ArrayList<>();
                        for (Part part : req.getParts()) {
                            String file = part.getSubmittedFileName();
                            if (file == null || file.isEmpty()) continue;
                            if (!part.getName().equals("photos")) throw new Problem(422, "Unknown attachment.");
                            files.add(part);
                        }
                        Store.report(db, user, id, draft, files);
                        redirect(res, admin ? teamLink + "&saved=report" : "/?saved=1");
                        return;
                    } catch (Problem error) {
                        res.setStatus(error.status);
                        problem = error.getMessage();
                        if (error.status == 409) problem += " Open the latest report to review your teammate’s changes.";
                        else problem += " Reselect any new photos before submitting again.";
                    } catch (IllegalStateException error) {
                        res.setStatus(413);
                        problem = "An upload was too large. Choose photos up to 12 MiB each.";
                    }
                }
            } else {
                draft = ReportForm.read(req, job);
                try {
                    if (step.equals("photos")) {
                        ReportForm.validate(draft);
                        photos = true;
                    } else {
                        ReportForm.changeRows(draft, step);
                    }
                } catch (Problem error) {
                    res.setStatus(error.status);
                    problem = error.getMessage();
                }
            }
        }
        String address = value(job, "address");
        String status = admin && job.hasNonNull("removed_at") ? "<span class='pill'>Removed from route</span>" : badge(job);
        List<String> crew = new ArrayList<>();
        for (JsonNode member : route.path("crew_names")) crew.add(member.asText());
        String crewLine = crew.isEmpty() ? "" : "<p class='helper'>Crew · " + h(String.join(", ", crew)) + "</p>";
        String header = link(admin ? "← Team routes" : "← My route", admin ? teamLink : "/", "worker-back worker-text-button") +
            "<header class='worker-job-header" + stopClasses(job) + "'><p class='eyebrow'>Stop " + h(value(job,"position")) + " · " + h(value(route,"team_name")) +
            " · " + h(value(route,"date")) + "</p><h1>" + mapAddress(address) +
            "</h1>" + jobIdLine(job) + "<p class='helper'>" + h(value(route, "entity_name")) + "</p>" + crewLine + "<div class='worker-job-meta'>" + stopBadges(job) + status + "</div>" + history(job) + "</header>";
        String body = photos ? photoForm(path, job, draft) : ReportForm.render(path, draft, ReportCatalog.FIBER_TYPES, ReportCatalog.items(db)) + savedPhotos(job);
        String latest = res.getStatus() == 409 ? link("Open latest report", path, "btn secondary") : "";
        String feedback = switch(param(req, "saved")) {
            case "note" -> notice("Note added."); case "address" -> notice("Stop details updated."); default -> "";
        };
        boolean addressError = !problem.isEmpty() && "address".equals(param(req, "step"));
        String tools = admin && !photos ? addressForm(path, job, addressError, addressError && res.getStatus() == 422 ? req : null) : "";
        String notes = notePanel(path, job, admin && !photos, problem.isEmpty() ? "" : param(req, "note"));
        page(res, user, address, "<div class='worker'>" + header + feedback + alert(problem) + latest + tools + notes + body + "</div>");
    }

    private static String history(JsonNode job) {
        if (value(job,"submitted_by").isEmpty()) return "";
        String out = "<p class='helper'>Submitted by " + h(value(job,"submitted_by")) + " · " + timestamp(value(job,"submitted_at")) + "</p>";
        if (!value(job,"last_edited_by").isEmpty()) out += "<p class='helper'>Last edited by " +
            h(value(job,"last_edited_by")) + " · " + timestamp(value(job,"last_edited_at")) + "</p>";
        return out;
    }

    private static String savedPhotos(JsonNode job) {
        if (job.path("photos").isEmpty()) return "";
        StringBuilder out = new StringBuilder("<section class='panel stack'><h2>Saved photos</h2><div class='worker-photos'>");
        int index = 0;
        for (JsonNode photo : job.path("photos")) {
            String url = "/api/photos/" + photo.path("id").asLong();
            out.append("<a href='").append(url).append("' target='_blank' rel='noopener'><img src='")
                .append(url).append("' alt='Job photo ").append(++index).append("' loading='lazy'></a>");
        }
        return out.append("</div></section>").toString();
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d, uuuu · h:mm a z", Locale.US)
        .withZone(ZoneId.of(System.getenv().getOrDefault("FIELDOPS_TZ", "America/New_York")));

    private static String timestamp(String value) {
        try {
            return "<time datetime='" + h(value) + "'>" + h(STAMP.format(Instant.parse(value))) + "</time>";
        } catch (DateTimeParseException ignored) { return h(value); }
    }

    private static String addressForm(String path, JsonNode job, boolean open, HttpServletRequest draft) {
        String address = draft == null ? value(job, "address") : param(draft, "address");
        String externalId = draft == null ? value(job, "external_job_id") : param(draft, "external_job_id");
        return "<details class='panel job-address-form'" + (open ? " open" : "") +
            "><summary>Edit address and Job ID</summary><form method='post' class='stack' action='" + path + "'>" +
            hidden("revision", value(job, "revision")) + field("Job address", "address", address, "required maxlength='500'") +
            field("Job ID (optional)", "external_job_id", externalId, "maxlength='120'") +
            "<button name='step' value='address'>Save stop details</button></form></details>";
    }

    private static String notePanel(String path, JsonNode job, boolean add, String draftNote) {
        JsonNode notes = job.path("notes");
        if (notes.isEmpty() && !add) return "";
        StringBuilder out = new StringBuilder("<section class='panel stack job-notes' id='job-notes'><h2>Admin notes</h2>");
        for (JsonNode note : notes) {
            out.append("<article><p class='note-text'>").append(h(value(note,"text"))).append("</p><p class='helper'>")
                .append(h(value(note,"author"))).append(" · ").append(timestamp(value(note,"created_at"))).append("</p></article>");
        }
        if (add) out.append("<form method='post' class='stack' action='").append(path)
            .append("'><div><label for='job-note'>Note for the crew</label><textarea id='job-note' name='note' required maxlength='4000'>")
            .append(h(draftNote)).append("</textarea></div><button name='step' value='note'>Add note</button></form>");
        return out.append("</section>").toString();
    }

    private static String photoForm(String path, JsonNode job, ObjectNode draft) {
        StringBuilder out = new StringBuilder("<section class='panel stack'><h2>Report review</h2>");
        out.append(ReportForm.summary(draft));
        String draftInput = hidden("draft", draft.toString());
        out.append("<button form='report-photos' class='secondary' name='step' value='measurements' ")
            .append("formenctype='application/x-www-form-urlencoded'>Edit measurements</button></section>")
            .append("<form id='report-photos' method='post' enctype='multipart/form-data' class='panel stack' action='")
            .append(path).append("'>").append(draftInput).append(hidden("photo_selection", "1")).append("<h2>Job photos</h2>");
        List<String> kept = new ArrayList<>();
        for (JsonNode id : draft.path("keep_photo_ids")) kept.add(id.asText());
        if (!job.path("photos").isEmpty()) {
            out.append("<p class='helper'>Uncheck a saved photo to remove it when you submit.</p><div class='worker-photos'>");
            int n = 0;
            for (JsonNode photo : job.path("photos")) {
                String id = value(photo,"id");
                out.append("<figure><a href='/api/photos/").append(h(id)).append("' target='_blank' rel='noopener'><img src='/api/photos/")
                    .append(h(id)).append("' alt='Job photo ").append(++n).append("'></a><label><input type='checkbox' name='keep' value='")
                    .append(h(id)).append("'").append(!draft.has("keep_photo_ids") || kept.contains(id) ? " checked" : "")
                    .append("> Keep photo ").append(n).append("</label></figure>");
            }
            out.append("</div>");
        }
        out.append("<p class='helper'>Up to 10 photos total. JPG, PNG or WebP, up to 12 MiB each.</p>")
            .append("<label>Add photos<input type='file' name='photos' multiple accept='image/jpeg,image/png,image/webp'></label>")
            .append("<details><summary>Add photos from separate selections</summary><div class='stack'>");
        for (int slot=2; slot<=10; slot++) out.append("<label>Additional photo ").append(slot)
            .append("<input type='file' name='photos' accept='image/jpeg,image/png,image/webp'></label>");
        return out.append("</div></details><button name='step' value='save'>").append(job.hasNonNull("submitted_at") ? "Update report" : "Submit report")
            .append("</button></form>").toString();
    }
}

