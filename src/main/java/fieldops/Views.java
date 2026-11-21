package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Browser entry points and shared server-rendered HTML controls. */
final class Views {
    private Views() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode person) throws Exception {
        req.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        String path = req.getRequestURI();
        boolean post = req.getMethod().equals("POST");
        if (!post && !req.getMethod().equals("GET") && !req.getMethod().equals("HEAD"))
            throw new Problem(405, "Method not allowed.");
        if (path.equals("/login") && post) {
            String returnTo = returnPath(req.getParameter("return_to"));
            try {
                ObjectNode signedIn = App.signIn(req, res, db, param(req, "username"), param(req, "password"));
                String destination = returnTo.isEmpty() ? "/" : returnTo;
                redirect(res, signedIn.path("must_change_password").asBoolean()
                    ? "/change-password" + (returnTo.isEmpty() ? "" : "?return_to=" + url(returnTo)) : destination);
            } catch (Problem error) {
                res.setStatus(error.status);
                login(res, param(req, "username"), error.getMessage(), false, returnTo);
            }
            return;
        }
        if (person == null) {
            if (post) res.setStatus(401);
            boolean changed = !post && path.equals("/login") && "1".equals(req.getParameter("changed"));
            String returnTo = returnPath(path.equals("/login") ? req.getParameter("return_to") : path);
            login(res, "", post ? "Sign in to continue." : "", changed, returnTo);
            return;
        }
        if (path.equals("/logout") && post) {
            App.signOut(req, res, db);
            redirect(res, "/");
            return;
        }
        if (path.equals("/change-password")) {
            passwordChange(req, res, db, person, post);
            return;
        }
        if (R2Setup.PATH.equals(path)) {
            R2Setup.handle(req, res, person);
            return;
        }
        if (LoginSheet.PATH.equals(path)) {
            LoginSheet.serve(req, res, person);
            return;
        }
        if (path.equals("/login") && !post) {
            redirect(res, "/");
            return;
        }
        boolean admin = person.path("is_admin").asBoolean();
        if (path.equals("/") && !post) {
            if (admin) redirect(res, "/routes");
            else WorkerViews.list(req, res, db, person);
        } else if (path.equals("/mainline") || path.startsWith("/mainline/")) {
            MainlineViews.handle(req, res, db, person, path, post);
        } else if (path.matches("/jobs/[0-9]+")) {
            WorkerViews.job(req, res, db, person, id(path.substring(6)));
        } else if (path.equals(JobReportExport.PATH) || path.equals(JobReportExport.DOWNLOAD) || path.equals(JobReportExport.SAMPLE)) {
            JobReportExport.handle(req, res, db, person);
        } else if (path.equals(PayrollChecks.SAMPLE_DOWNLOAD) || path.matches("/payroll(?:/export(?:/details|/workbook)?|/statement/[0-9]+|/checks(?:/[0-9]+)?|/check-settings)?")) {
            if (!admin) throw new Problem(403, "Administrator access required.");
            String nav = AdminViews.payrollNav();
            if (path.startsWith("/payroll/check")) PayrollChecks.handle(req, res, db, person, path, post, nav);
            else WeeklyPayrollViews.handle(req, res, db, person, path, post, nav);
        } else if (path.matches("/(routes|people|roles|teams|entities|items|vehicles)(/(new|[0-9]+))?") ||
                path.matches("/entities/[0-9]+/(?:payroll|rates)") || path.matches("/routes/team/[0-9]+") || path.equals("/routes/drops") || path.equals(TeamRouteWizard.PATH)) {
            if (!admin) throw new Problem(403, "Administrator access required.");
            AdminViews.handle(req, res, db, person, path, post);
        } else {
            throw new Problem(404, "Page not found.");
        }
    }

    static String teamRoutesPath(long teamId, String day) {
        return "/routes/team/" + teamId + "?date=" + url(day);
    }

    static String returnPath(String target) {
        if (JobReportExport.SAMPLE.equals(target)) return target;
        if (JobReportExport.PATH.equals(target) || JobReportExport.DOWNLOAD.equals(target)) return JobReportExport.PATH;
        if ("/payroll".equals(target) || "/payroll/check-settings".equals(target) || PayrollChecks.SAMPLE_DOWNLOAD.equals(target)) return target;
        if (target != null && target.matches("/entities(?:/[0-9]+(?:/(?:payroll|rates))?)?")) return target;
        if (target != null && (target.matches("/mainline(?:/projects(?:/new|/[0-9]+(?:/edit|/dailies/new)?)?|/dailies/[0-9]+(?:/photos)?)?") ||
            target.equals("/routes/drops"))) return target;
        return LoginSheet.PATH.equals(target) || R2Setup.PATH.equals(target) ? target : "";
    }

    private static void passwordChange(HttpServletRequest req, HttpServletResponse res, Connection db,
                                       ObjectNode person, boolean post) throws Exception {
        if (!person.path("is_admin").asBoolean()) throw new Problem(403, "Your administrator manages your password.");
        String error = "";
        String returnTo = returnPath(req.getParameter("return_to"));
        if (post) {
            try {
                String newPassword = param(req, "new_password");
                if (!newPassword.equals(param(req, "confirmation"))) throw new Problem(422, "The new passwords must match.");
                App.changePassword(req, res, db, param(req, "current_password"), newPassword);
                redirect(res, "/login?changed=1" + (returnTo.isEmpty() ? "" : "&return_to=" + url(returnTo)));
                return;
            } catch (Problem problem) {
                res.setStatus(problem.status);
                error = problem.getMessage();
            }
        }
        String newPasswordAttributes = "type='password' autocomplete='new-password' required maxlength='256' minlength='" +
            Auth.MIN_PASSWORD_LENGTH + "'";
        page(res, person, "Change password",
            "<form class='login panel stack' method='post' action='/change-password'><h1>Change password</h1>" +
            (returnTo.isEmpty() ? "" : hidden("return_to", returnTo)) +
            (person.path("must_change_password").asBoolean() ? "<p class='muted'>Change your temporary password to finish signing in.</p>" : "") +
            alert(error) + field("Current password", "current_password", "", "type='password' autocomplete='current-password' required") +
            field("New password (" + Auth.MIN_PASSWORD_LENGTH + " characters minimum)", "new_password", "", newPasswordAttributes) +
            field("Confirm new password", "confirmation", "", newPasswordAttributes) +
            "<button>Change password</button></form>");
    }

    private static void login(HttpServletResponse res, String username, String error, boolean changed, String returnTo) throws IOException {
        page(res, null, "Sign in",
            "<form class='login panel stack' method='post' action='/login'><div class='eyebrow'>Field operations</div>" +
            "<h1>Sign in</h1>" + (changed ? notice("Password changed. Sign in with your new password.") :
                "<p class='muted'>Use the account assigned to you by your administrator.</p>") +
            (returnTo.isEmpty() ? "" : hidden("return_to", returnTo)) +
            field("Username", "username", username, "required autocomplete='username' autocapitalize='none' spellcheck='false'") +
            field("Password", "password", "", "required type='password' autocomplete='current-password'") +
            alert(error) + "<button>Sign in</button></form>");
    }

    static void page(HttpServletResponse res, ObjectNode person, String title, String content) throws IOException {
        res.setContentType("text/html;charset=UTF-8");
        String account = person == null ? "" : "<span>" + h(value(person, "name")) +
            "</span>" + (person.path("is_admin").asBoolean() && !person.path("must_change_password").asBoolean()
                ? link("Change password", "/change-password", "storage-link") : "") +
            (R2Setup.owner(person) ? link("Photo storage", R2Setup.PATH, "storage-link") : "") +
            "<form method='post' action='/logout'><button>Sign out</button></form>";
        res.getWriter().write("<!doctype html><html lang='en'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'><meta name='theme-color' content='#142d2a'>" +
            "<title>" + h(title) + " · Field Routes</title><link rel='stylesheet' href='/app.css'>" +
            "<link rel='stylesheet' href='/worker.css'></head><body><header class='masthead'>" +
            "<a href='/' class='brand' aria-label='Field Routes home'><span class='mark' aria-hidden='true'>" +
            "<i></i><i></i><i></i></span>Field Routes</a><div id='account'>" + account +
            "</div></header><main>" + content + "</main></body></html>");
    }

    static void redirect(HttpServletResponse res, String target) {
        res.setStatus(303);
        res.setHeader("Location", target);
    }

    static String h(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String jobIdLine(JsonNode job) {
        String id = value(job, "external_job_id");
        return id.isEmpty() ? "" : "<p class='helper job-reference'>Job ID · " + h(id) + "</p>";
    }

    static String vehicleLine(JsonNode route) {
        var names = new java.util.ArrayList<String>();
        for (var vehicle : route.path("vehicles")) names.add(value(vehicle, "name"));
        return names.isEmpty() ? "" : "<p class='helper'>Vehicles · " + h(String.join(", ", names)) + "</p>";
    }

    static String mapAddress(String address) {
        return "<a class='map-address' target='_blank' rel='noopener noreferrer' title='Open directions in Google Maps' " +
            "href='https://www.google.com/maps/dir/?api=1&amp;destination=" + url(address) + "'>" + h(address) + "</a>";
    }

    static String value(JsonNode item, String key) {
        return item == null ? "" : item.path(key).asText("");
    }

    static String param(HttpServletRequest req, String name) {
        String value = req.getParameter(name);
        return value == null ? "" : value;
    }

    static List<String> params(HttpServletRequest req, String name) {
        String[] values = req.getParameterValues(name);
        return values == null ? List.of() : Arrays.asList(values);
    }

    static long id(String raw) {
        try { return Long.parseLong(raw); }
        catch (NumberFormatException error) { throw new Problem(422, "Invalid record number."); }
    }

    static ObjectNode item(ArrayNode items, long id) {
        for (JsonNode entry : items) if (entry.path("id").asLong() == id) return (ObjectNode)entry;
        throw new Problem(404, "Record not found.");
    }

    static String alert(String message) {
        return message == null || message.isEmpty() ? "" : "<p class='error' role='alert'>" + h(message) + "</p>";
    }

    static String notice(String message) {
        return "<p class='notice' role='status'>" + h(message) + "</p>";
    }

    static String field(String label, String name, String value, String attributes) {
        return "<label>" + h(label) + "<input name='" + h(name) + "' value='" + h(value) + "' " + attributes + "></label>";
    }

    static String hidden(String name, String value) {
        return "<input type='hidden' name='" + h(name) + "' value='" + h(value) + "'>";
    }

    static String option(String key, String label, String current) {
        return "<option value='" + h(key) + "'" + (key.equals(current) ? " selected" : "") + ">" + h(label) + "</option>";
    }

    static String link(String title, String target, String classes) {
        return "<a class='" + classes + "' href='" + h(target) + "'>" + h(title) + "</a>";
    }

    static String badge(JsonNode job) {
        return switch (value(job, "status")) {
            case "submitted" -> "<span class='pill done'>Submitted</span>";
            case "pending" -> "<span class='pill pending'>Pending</span>";
            case "cancel" -> "<span class='pill canceled'>Canceled</span>";
            default -> "<span class='pill'>Assigned</span>";
        };
    }

    static String stopLabelTitle(String label) {
        String key = label.toLowerCase(Locale.ROOT);
        return RouteStops.LABELS.contains(key) ? Character.toUpperCase(key.charAt(0)) + key.substring(1) : "";
    }

    static String stopClasses(JsonNode job) {
        String label = stopLabelTitle(value(job, "stop_label"));
        String category = label.isEmpty() ? "" : " stop-" + label.toLowerCase(Locale.ROOT);
        return category + (job.path("urgent").asBoolean() ? " stop-urgent" : "");
    }

    static String stopBadges(JsonNode job) {
        String label = stopLabelTitle(value(job, "stop_label"));
        boolean urgent = job.path("urgent").asBoolean();
        if (label.isEmpty() && !urgent) return "";
        return "<span class='stop-badges'>" +
            (urgent ? "<span class='stop-tag urgent-tag'>Urgent</span>" : "") +
            (label.isEmpty() ? "" : "<span class='stop-tag'>" + h(label) + "</span>") + "</span>";
    }
}

