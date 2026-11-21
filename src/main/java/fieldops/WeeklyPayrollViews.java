package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.StringJoiner;
import static fieldops.Views.*;

/** Administrator review, spreadsheet exports, and printable contractor statements for a pay week. */
final class WeeklyPayrollViews {
    private static final String CSS = "<link rel='stylesheet' href='/payroll-print.css'>";
    private static final String PATH = "/payroll";
    private WeeklyPayrollViews() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode admin,
                       String path, boolean post, String nav) throws Exception {
        res.setHeader("Cache-Control", "private, no-store");
        if (admin == null || !admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        if (!PATH.equals(path) && !path.equals(PATH + "/export") && !path.equals(PATH + "/export/details") && !path.equals(PATH + "/export/workbook")
                && !path.matches("/payroll/statement/[0-9]+")) throw new Problem(404, "Payroll page not found.");
        String rawDay = param(req, "pay_date");
        LocalDate latest = PayrollRuns.latestFriday();
        if (post && rawDay.isBlank()) throw new Problem(422, "Select a Friday pay date.");
        LocalDate day = rawDay.isBlank() ? latest : LocalDate.parse(StoreInput.date(rawDay));
        if (day.getDayOfWeek() != DayOfWeek.FRIDAY) throw new Problem(422, "Select a Friday pay date.");
        if (post) {
            if (path.startsWith(PATH + "/statement/")) {
                long workerId = id(path.substring((PATH + "/statement/").length()));
                String key = param(req, "review_key"), decision = param(req, "decision"), previous = param(req, "previous_decision");
                if (key.isBlank() || !(decision.equals("include") || decision.equals("exclude"))
                        || !(previous.equals("none") || previous.equals("include") || previous.equals("exclude")))
                    throw new Problem(422, "Choose whether to include or exclude the reported work.");
                PayrollRuns.reviewDay(db, day, workerId, key, previous, decision, admin.path("id").asLong());
                redirect(res, pathFor(path, day.toString()));
                return;
            }
            if (!PATH.equals(path)) throw new Problem(405, "Use the weekly payroll page to generate statements.");
            if (!"generate".equals(param(req, "action"))) throw new Problem(422, "Choose a payroll action.");
            if (day.isAfter(latest)) throw new Problem(422, "Generate this payroll on or after its Friday pay date.");
            PayrollRuns.generate(db, day);
            redirect(res, pathFor(PATH, day.toString()));
            return;
        }
        if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) throw new Problem(405, "Method not allowed.");
        ObjectNode run = PayrollRuns.view(db, day);
        if (path.equals(PATH + "/export/workbook")) {
            res.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            res.setHeader("Content-Disposition", "attachment; filename=\"field-routes-payroll-" + day + ".xlsx\"");
            PayrollWorkbook.write(res.getOutputStream(), run);
        } else if (path.startsWith(PATH + "/export")) {
            boolean details = path.endsWith("/details");
            res.setContentType("text/csv;charset=UTF-8");
            res.setHeader("Content-Disposition", "attachment; filename=\"field-routes-payroll-" + day +
                (details ? "-details.csv\"" : "-summary.csv\""));
            writeCsv(res.getWriter(), run, details);
        } else if (path.startsWith(PATH + "/statement/")) {
            long workerId = id(path.substring((PATH + "/statement/").length()));
            for (JsonNode worker : run.path("workers")) {
                if (worker.path("person_id").asLong() != workerId) continue;
                page(res, admin, "Pay statement · " + value(worker, "name"), statement(run, worker));
                return;
            }
            throw new Problem(404, "Contractor statement not found for this pay date.");
        } else {
            page(res, admin, "Weekly payroll", CSS + nav + dashboard(run, !day.isAfter(latest)));
        }
    }

    static String dashboard(ObjectNode run, boolean due) {
        String date = value(run, "pay_date");
        JsonNode workers = run.path("workers");
        int generated = 0, ready = 0, needsReview = 0, checks = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode worker : workers) {
            if (generated(worker)) generated++;
            else if (ready(worker)) ready++;
            else needsReview++;
            if (canPrintCheck(worker)) checks++;
            if ((generated(worker) || ready(worker)) && worker.path("gross").isNumber())
                total = total.add(worker.get("gross").decimalValue());
        }
        var out = new StringBuilder("<div class='weekly-payroll'><div class='section-head'><div><h1>Weekly payroll</h1>")
            .append(period(run)).append("</div></div><form class='row' method='get' action='/payroll'>")
            .append(field("Friday pay date", "pay_date", date, "type='date' step='7' required"))
            .append("<button class='secondary'>View week</button></form>")
            .append("<div>").append(link("Check settings", PATH + "/check-settings", "worker-text-button")).append("</div>");
        if (workers.isEmpty()) return out.append("<section class='panel empty'><h2>No contractor earnings</h2>")
            .append("<p class='muted'>There are no pay lines for this work period.</p></section></div>").toString();
        String phase = switch (value(run, "run_status")) {
            case "generated" -> "Generated"; case "partial" -> "Partially generated"; default -> "Draft";
        };
        out.append("<section class='panel stack'><div class='section-head'><div><h2>Pay statements · ").append(phase).append("</h2>")
            .append("<p class='muted'>").append(generated).append(" generated · ").append(ready).append(" ready · ").append(needsReview)
            .append(" need review</p></div><div><strong>").append(money(total)).append("</strong><br>Calculated gross</div></div>");
        if (needsReview > 0) out.append("<p class='payroll-warning'>Open the statements marked Needs review to see which pay lines are incomplete.</p>");
        if (ready > 0) {
            if (due) out.append("<form method='post' action='/payroll'>").append(hidden("pay_date", date))
                .append("<button name='action' value='generate'>Generate ready statements</button></form>");
            else out.append("<p class='muted'>Ready statements can be generated on ").append(h(date)).append(".</p>");
        }
        out.append("<div class='payroll-links'>").append(link("Download Excel workbook", pathFor(PATH + "/export/workbook", date), "btn"))
            .append(link("Summary CSV", pathFor(PATH + "/export", date), "btn secondary"))
            .append(link("Detail CSV", pathFor(PATH + "/export/details", date), "btn secondary"));
        if (checks > 0) out.append(link("Print generated checks", pathFor(PATH + "/checks", date), "btn secondary"));
        out.append("</div><div class='payroll-scroll'><table class='payroll-table'><thead><tr><th>Contractor</th><th>Days worked</th>")
            .append("<th>Crew feet buried</th><th>Gross pay</th><th>Statement</th></tr></thead><tbody>");
        for (JsonNode worker : workers) {
            String destination = PATH + "/statement/" + worker.path("person_id").asLong();
            out.append("<tr><th scope='row'>").append(link(value(worker, "name"), pathFor(destination, date), "worker-text-button"))
                .append("</th><td>").append(h(number(worker.get("days_worked")))).append("</td><td>")
                .append(h(number(worker.get("feet_buried")))).append("</td><td>").append(gross(worker))
                .append("</td><td>").append(status(worker));
            if (canPrintCheck(worker)) out.append("<br>").append(link("Print check", pathFor(PATH + "/checks/" +
                worker.path("person_id").asLong(), date), "worker-text-button"));
            if (worker.path("source_changed").asBoolean()) out.append("<br><span class='payroll-warning'>Source changed since generation</span>");
            if (!value(worker, "reason").isBlank()) out.append("<br><span class='payroll-warning'>").append(h(value(worker, "reason"))).append("</span>");
            out.append("</td></tr>");
        }
        return out.append("</tbody></table></div></section></div>").toString();
    }

    static String statement(ObjectNode run, JsonNode worker) {
        String date = value(run, "pay_date");
        boolean issued = generated(worker);
        var out = new StringBuilder(CSS).append("<div class='weekly-payroll'><div class='payroll-screen-only row'>")
            .append(link("← Weekly payroll", pathFor(PATH, date), "btn secondary"));
        if (canPrintCheck(worker)) out.append(link("Print check", pathFor(PATH + "/checks/" + worker.path("person_id").asLong(), date), "btn secondary"));
        if (issued) out.append("<p class='helper'>Use your browser’s Print command to print or save this statement as a PDF.</p>");
        out.append("</div>");
        if (worker.path("source_changed").asBoolean()) out.append("<p class='payroll-warning payroll-screen-only'>")
            .append("Work or payroll settings changed after generation. This statement shows the originally generated amounts.</p>");
        out.append("<article class='panel stack pay-statement'><header><div class='eyebrow'>Field Routes</div><h1>")
            .append(issued ? "Contractor pay statement" : "Draft payroll preview").append("</h1><h2>")
            .append(h(value(worker, "name"))).append("</h2>").append(period(run)).append("<p>Pay date · ")
            .append(h(date)).append("</p>");
        if (worker.path("check_number").isIntegralNumber())
            out.append("<p>Check number · ").append(worker.get("check_number").asLong()).append("</p>");
        out.append("</header><div class='payroll-metrics'><div><span>Gross pay</span><strong>")
            .append(gross(worker)).append("</strong></div><div><span>Days worked</span><strong>")
            .append(h(number(worker.get("days_worked")))).append("</strong></div><div><span>Crew feet buried</span><strong>")
            .append(h(number(worker.get("feet_buried")))).append("</strong></div></div>");
        if (!issued) out.append("<p class='payroll-warning'>").append(ready(worker) ?
            "Draft · Ready to generate." : "Draft · Amount pending review.").append("</p>");
        if (!value(worker, "reason").isBlank()) out.append("<p class='payroll-warning'>").append(h(value(worker, "reason"))).append("</p>");
        if (!issued) out.append(dayReviews(worker, date));
        out.append("<h2>Pay detail</h2><div class='payroll-scroll'><table class='payroll-table'><thead><tr>")
            .append("<th>Work date</th><th>Entity and crew</th><th>Calculation</th><th>Gross</th></tr></thead><tbody>");
        for (JsonNode line : worker.path("details")) {
            out.append("<tr><td>").append(h(value(line, "date"))).append("</td><td>").append(h(value(line, "entity_name")))
                .append("<br><span class='muted'>").append(h(value(line, "team_name"))).append("</span></td><td>");
            String formula = value(line, "formula"), basis = basis(line);
            out.append(h(formula.isBlank() ? basis : formula));
            if (!formula.isBlank() && !basis.isBlank()) out.append(" · ").append(h(basis));
            if (line.hasNonNull("expression") && !value(line, "expression").isBlank())
                out.append("<div><code>").append(h(value(line, "expression"))).append("</code></div>");
            String inputs = inputs(line);
            if (!inputs.isEmpty()) out.append("<div class='helper'>").append(h(inputs)).append("</div>");
            if (line.path("credited_feet_buried").isNumber())
                out.append("<div class='helper'>Credited footage · ").append(h(number(line.get("credited_feet_buried"))))
                    .append(" ft").append("split".equals(value(line, "footage_credit")) ? " (crew split)" : "").append("</div>");
            if (!value(line, "reason").isBlank()) out.append("<div class='payroll-warning'>").append(h(value(line, "reason"))).append("</div>");
            out.append("</td><td>").append(line.path("amount").isNumber() ? money(line.get("amount").decimalValue()) : "Pending")
                .append("</td></tr>");
        }
        out.append("</tbody></table></div>");
        return out.append("</article></div>").toString();
    }

    private static String dayReviews(JsonNode worker, String date) {
        var days = new LinkedHashMap<String, JsonNode>();
        for (var line : worker.path("details")) {
            String key = value(line, "review_key");
            if (!key.isEmpty()) days.putIfAbsent(key, line);
        }
        if (days.isEmpty()) return "";
        var out = new StringBuilder("<section class='stack payroll-screen-only'><h2>Review reported work</h2>")
            .append("<p>These workdays have only pending or canceled stops. Choose whether each day counts toward this contractor’s pay.</p>");
        for (var day : days.entrySet()) {
            JsonNode line = day.getValue();
            String previous = value(line, "review_decision");
            String label = switch (previous) {
                case "include" -> "Included in pay"; case "exclude" -> "Excluded from pay"; default -> "Awaiting decision";
            };
            out.append("<form class='panel stack' method='post' action='/payroll/statement/").append(worker.path("person_id").asLong())
                .append("'>").append(hidden("pay_date", date)).append(hidden("review_key", day.getKey()))
                .append(hidden("previous_decision", previous)).append("<h3>").append(h(value(line, "date")))
                .append(" · ").append(h(value(line, "entity_name"))).append(" · ").append(h(value(line, "team_name")))
                .append("</h3><p>").append(label).append(" · ").append(line.path("pending_jobs").asInt()).append(" pending · ")
                .append(line.path("canceled_jobs").asInt()).append(" canceled</p>");
            if (!line.path("report_outcomes").isEmpty()) {
                out.append("<ul>");
                for (var report : line.path("report_outcomes")) {
                    String state = value(report, "outcome").equals("pending") ? "Pending stop" : "Canceled stop";
                    out.append("<li>").append(link(state, "/jobs/" + report.path("job_id").asLong(), "worker-text-button"));
                    if (!value(report, "reason").isBlank()) out.append(" · ").append(h(value(report, "reason")));
                    out.append("</li>");
                }
                out.append("</ul>");
            }
            out.append("<div class='payroll-links'>")
                .append("<button name='decision' value='include'").append(previous.equals("include") ? " disabled" : "")
                .append(">Include reported work</button><button class='secondary' name='decision' value='exclude'")
                .append(previous.equals("exclude") ? " disabled" : "").append(">Exclude from pay</button></div></form>");
        }
        return out.append("</section>").toString();
    }

    private static String pathFor(String path, String day) { return path + "?pay_date=" + url(day); }

    private static String period(JsonNode run) {
        return "<p class='muted'>Work period · " + h(value(run, "start_date")) + " through " + h(value(run, "end_date")) + "</p>";
    }

    private static boolean generated(JsonNode worker) { return value(worker, "statement_status").equals("generated"); }
    private static boolean canPrintCheck(JsonNode worker) {
        if (!generated(worker) || !worker.path("gross").isNumber()) return false;
        BigDecimal gross = worker.get("gross").decimalValue();
        return gross.signum() > 0 && gross.compareTo(PayrollRuns.MAX_CHECK) <= 0;
    }
    private static boolean ready(JsonNode worker) { return value(worker, "status").equals("ready") && worker.path("gross").isNumber(); }
    private static String status(JsonNode worker) { return generated(worker) ? "Generated" : ready(worker) ? "Ready" : "Needs review"; }
    private static String basis(JsonNode line) {
        return switch (value(line, "basis")) {
            case "daily" -> "Daily"; case "production" -> "Production"; default -> value(line, "basis");
        };
    }
    private static String gross(JsonNode worker) {
        return worker.path("gross").isNumber() ? money(worker.get("gross").decimalValue()) : "Pending";
    }
    private static String money(BigDecimal amount) {
        return String.format(Locale.US, "$%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }
    private static String number(JsonNode value) {
        if (value == null || !value.isNumber()) return "";
        return value.decimalValue().stripTrailingZeros().toPlainString();
    }
    private static String inputs(JsonNode line) {
        var out = new StringJoiner(" · ");
        line.path("inputs").fields().forEachRemaining(entry ->
            out.add(entry.getKey() + " = " + number(entry.getValue())));
        return out.toString();
    }
    private static String reportedItems(JsonNode line) {
        var out = new StringJoiner(" · ");
        for (var item : line.path("reported_items")) {
            String unit = value(item, "unit");
            out.add(value(item, "item") + " = " + number(item.get("quantity")) + (unit.isBlank() ? "" : " " + unit));
        }
        return out.toString();
    }

    static void writeCsv(PrintWriter out, ObjectNode run, boolean detail) throws IOException {
        out.write('\ufeff');
        exportRows(run, detail, cells -> csv(out, cells));
        if (out.checkError()) throw new IOException("The payroll export could not be written.");
    }

    @FunctionalInterface
    interface ExportRow { void write(Object... cells) throws IOException; }

    static void exportRows(ObjectNode run, boolean detail, ExportRow out) throws IOException {
        if (detail) out.write("Pay date", "Period start", "Period end", "Contractor ID", "Contractor", "Statement status",
            "Work date", "Entity", "Crew", "Pay basis", "Crew feet buried", "Credited feet buried", "Footage credit", "Crew size",
            "Formula", "Expression", "Quantities and rates", "Reported item quantities",
            "Line gross", "Reported-day decision", "Pending stops", "Canceled stops", "Review reason", "Source changed after generation");
        else out.write("Pay date", "Period start", "Period end", "Contractor ID", "Contractor", "Days worked",
            "Crew feet buried", "Gross pay", "Statement status", "Check number", "Review reason", "Source changed after generation");
        for (JsonNode worker : run.path("workers")) {
            var id = worker.get("person_id");
            String name = value(worker, "name"), state = status(worker), day = value(run, "pay_date"),
                first = value(run, "start_date"), last = value(run, "end_date");
            String changed = worker.path("source_changed").asBoolean() ? "Yes" : "";
            if (detail) {
                for (var line : worker.path("details")) {
                    out.write(day, first, last, id, name, state, value(line, "date"), value(line, "entity_name"),
                        value(line, "team_name"), basis(line), line.get("feet_buried"), line.get("credited_feet_buried"),
                        value(line, "footage_credit"), line.get("crew_size"), value(line, "formula"),
                        value(line, "expression"), inputs(line), reportedItems(line), line.get("amount"), value(line, "review_decision"),
                        line.get("pending_jobs"), line.get("canceled_jobs"), value(line, "reason"), changed);
                }
            } else {
                out.write(day, first, last, id, name, worker.get("days_worked"), worker.get("feet_buried"), worker.get("gross"), state,
                    worker.get("check_number"), value(worker, "reason"), changed);
            }
        }
    }

    private static void csv(PrintWriter out, Object... cells) {
        var row = new StringJoiner(",");
        for (Object cell : cells) {
            String text;
            if (cell instanceof JsonNode n) text = number(n);
            else {
                text = cell == null ? "" : cell.toString();
                if (spreadsheetFormula(text)) text = "'" + text;
            }
            row.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        out.print(row + "\r\n");
    }

    private static boolean spreadsheetFormula(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) return true;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\ufeff') continue;
            return "=+-@＝＋－＠".indexOf(c) >= 0;
        }
        return false;
    }
}
