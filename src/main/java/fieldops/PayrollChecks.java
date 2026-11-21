package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import static fieldops.Views.*;

final class PayrollChecks {
    private static final String CHECKS = "/payroll/checks", SETTINGS = "/payroll/check-settings";
    private static final String SAMPLE_FILENAME = "Field-Routes-VOID-revised.pdf";
    static final String SAMPLE_DOWNLOAD = SETTINGS + "/" + SAMPLE_FILENAME;
    private static final String STYLES = "<link rel='stylesheet' href='/payroll-checks.css'>" +
        "<link rel='stylesheet' href='/payroll/check-settings?alignment=css'>";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE M/d", Locale.US);
    private static final String[] SMALL = {"zero","one","two","three","four","five","six","seven","eight","nine","ten",
        "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};
    private static final String[] TENS = {"","","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
    private PayrollChecks() {}

    static void handle(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode admin,
                       String path, boolean post, String nav) throws Exception {
        if (admin == null || !admin.path("is_admin").asBoolean()) throw new Problem(403, "Administrator access required.");
        res.setHeader("Cache-Control", "private, no-store");
        if (path.equals(SAMPLE_DOWNLOAD)) {
            if (post) throw new Problem(405, "Open the download link to get the PDF.");
            try (var file = PayrollChecks.class.getResourceAsStream("/web/" + SAMPLE_FILENAME)) {
                if (file == null) throw new java.io.IOException("Missing VOID alignment PDF");
                byte[] pdf = file.readAllBytes();
                res.setCharacterEncoding((String) null);
                res.setContentType("application/pdf");
                res.setHeader("Content-Disposition", "attachment; filename=\"Field-Routes-VOID-stock-aligned.pdf\"");
                res.setContentLength(pdf.length);
                if (!req.getMethod().equals("HEAD")) res.getOutputStream().write(pdf);
            }
            return;
        }
        if (path.equals(SETTINGS)) {
            settings(req, res, db, admin, post, nav);
            return;
        }
        if (!path.equals(CHECKS) && !path.matches("/payroll/checks/[0-9]+")) throw new Problem(404, "Check page not found.");
        Long personId = path.equals(CHECKS) ? null : id(path.substring(CHECKS.length() + 1));
        String raw = param(req, "pay_date");
        LocalDate friday = !post && raw.isEmpty() ? PayrollRuns.latestFriday() : LocalDate.parse(StoreInput.date(raw));
        if (friday.getDayOfWeek() != DayOfWeek.FRIDAY) throw new Problem(422, "Select a Friday pay date.");
        String error = "";
        if (post) {
            try {
                if (!param(req, "action").equals("number")) throw new Problem(422, "Choose a check action.");
                PayrollRuns.numberChecks(db, friday, personId, id(param(req, "first_number")));
                redirect(res, path + "?pay_date=" + friday + "&numbered=1");
                return;
            } catch (Problem problem) {
                res.setStatus(problem.status);
                error = problem.getMessage();
            }
        }
        ObjectNode run = PayrollRuns.view(db, friday);
        List<JsonNode> selected = new ArrayList<>(), numbered = new ArrayList<>(), remaining = new ArrayList<>();
        for (JsonNode worker : run.path("workers")) {
            if (personId != null && personId != worker.path("person_id").asLong()) continue;
            selected.add(worker);
            if (ineligible(worker).isEmpty()) {
                if (worker.hasNonNull("check_number")) numbered.add(worker);
                else remaining.add(worker);
            }
        }
        if (personId != null && selected.isEmpty()) throw new Problem(404, "Contractor statement not found for this pay date.");
        numbered.sort(Comparator.comparingLong(w -> w.path("check_number").asLong()));
        boolean contiguous = true;
        for (int i = 1; i < numbered.size(); i++)
            if (numbered.get(i).path("check_number").asLong() != numbered.get(i - 1).path("check_number").asLong() + 1) contiguous = false;
        boolean printable = remaining.isEmpty() && !numbered.isEmpty() && contiguous;
        String heading = personId == null ? "Payroll checks" : "Check for " + value(selected.getFirst(), "name");
        StringBuilder screen = new StringBuilder("<div class='check-controls'>").append(nav)
            .append(link("← Weekly payroll", "/payroll?pay_date=" + friday, "worker-back worker-text-button"))
            .append("<header class='section-head'><div><p class='eyebrow'>Pay date · ").append(friday)
            .append("</p><h1>").append(h(heading)).append("</h1></div>")
            .append(link("Check alignment", SETTINGS + "?pay_date=" + friday, "btn secondary")).append("</header><div class='stack'>")
            .append(alert(error));
        if (!post && "1".equals(param(req, "numbered"))) screen.append(notice("Paper check numbers assigned."));
        if (!remaining.isEmpty()) {
            int count = remaining.size();
            screen.append("<form class='panel stack' method='post' action='").append(path).append("'>")
                .append(hidden("pay_date", friday.toString())).append("<h2>Assign paper check numbers</h2>")
                .append("<p>Enter the number on the first sheet of stock. ").append(count == 1 ? "One check needs a number." :
                    count + " checks need consecutive numbers in contractor-name order.")
                .append("</p>").append(field("First paper check number", "first_number", param(req, "first_number"),
                    "inputmode='numeric' pattern='[0-9]{1,9}' maxlength='9' required"))
                .append("<div><button name='action' value='number'>Assign check numbers</button></div></form>");
        }
        if (printable) {
            long first = numbered.getFirst().path("check_number").asLong(), last = numbered.getLast().path("check_number").asLong();
            screen.append("<div class='panel stack'><h2>Ready to print</h2><p>Load ")
                .append(first == last ? "paper check #" + first : "checks #" + first + " through #" + last + ", lowest number first")
                .append(". Print on US Letter in portrait using the same scale and margins as your successful plain-paper test.</p></div>");
        } else if (!contiguous) {
            screen.append("<p class='error' role='alert'>These check numbers are not consecutive. Open each check below and load its matching numbered sheet.</p>");
        } else if (remaining.isEmpty()) {
            screen.append("<p>No generated checks are eligible to print for this selection.</p>");
        }
        if (personId == null && (!numbered.isEmpty() || !remaining.isEmpty())) {
            screen.append("<section class='panel'><h2>Checks for this Friday</h2>");
            for (JsonNode worker : numbered) screen.append(checkLink(worker, friday));
            for (JsonNode worker : remaining) screen.append(checkLink(worker, friday));
            screen.append("</section>");
        }
        int skipped = selected.size() - numbered.size() - remaining.size();
        if (skipped > 0) {
            screen.append("<details class='panel'").append(personId == null ? "" : " open").append("><summary>Excluded from printing (")
                .append(skipped).append(")</summary><ul>");
            for (JsonNode worker : selected) {
                String why = ineligible(worker);
                if (!why.isEmpty()) screen.append("<li>").append(h(value(worker, "name"))).append(" · ").append(h(why)).append("</li>");
            }
            screen.append("</ul></details>");
        }
        screen.append("</div></div>");
        StringBuilder pages = new StringBuilder("<div class='check-pages'>");
        if (printable) for (JsonNode worker : numbered) pages.append(sheet(run, worker, false));
        page(res, admin, heading, STYLES + screen + pages.append("</div>"));
    }

    private static String checkLink(JsonNode worker, LocalDate friday) {
        String number = worker.hasNonNull("check_number") ? "Check #" + worker.path("check_number").asLong() : "Awaiting number";
        return "<div class='list-row'><div><strong>" + h(value(worker, "name")) + "</strong><p class='muted'>" +
            number + " · " + h(money(worker.get("gross").decimalValue())) + "</p></div>" +
            link("Open check", CHECKS + "/" + worker.path("person_id").asLong() + "?pay_date=" + friday, "btn secondary") + "</div>";
    }

    private static String ineligible(JsonNode worker) {
        if (!"generated".equals(value(worker, "statement_status"))) return "Pay statement has not been generated.";
        if (!worker.path("gross").isNumber()) return "Gross amount unavailable.";
        BigDecimal amount = worker.get("gross").decimalValue();
        if (amount.signum() <= 0) return "Gross must be positive.";
        if (amount.compareTo(PayrollRuns.MAX_CHECK) > 0) return "Gross exceeds the check limit of $999,999,999.99.";
        if (amount.stripTrailingZeros().scale() > 2) return "Gross has a fractional cent.";
        return "";
    }

    private static void settings(HttpServletRequest req, HttpServletResponse res, Connection db, ObjectNode admin,
                                 boolean post, String nav) throws Exception {
        ObjectNode current = PayrollRuns.printSettings(db);
        if ("css".equals(param(req, "alignment")) && !post) {
            res.setContentType("text/css;charset=UTF-8");
            res.getWriter().write(".check-sheet .paper-content{transform:translate(" +
                current.get("x_mm").decimalValue().toPlainString() + "mm," + current.get("y_mm").decimalValue().toPlainString() + "mm)}");
            return;
        }
        String rawDay = param(req, "pay_date");
        LocalDate day = rawDay.isBlank() ? PayrollRuns.latestFriday() : LocalDate.parse(StoreInput.date(rawDay));
        if (day.getDayOfWeek() != DayOfWeek.FRIDAY) throw new Problem(422, "Select a Friday pay date.");
        String error = "", x = value(current, "x_mm"), y = value(current, "y_mm");
        if (post) {
            x = param(req, "x_mm"); y = param(req, "y_mm");
            try {
                if (!param(req, "action").equals("offsets")) throw new Problem(422, "Choose an alignment action.");
                PayrollRuns.savePrintSettings(db, new BigDecimal(x), new BigDecimal(y));
                redirect(res, SETTINGS + "?pay_date=" + day + "&saved=1");
                return;
            } catch (NumberFormatException e) {
                res.setStatus(422);
                error = "Enter both offsets in millimeters.";
            } catch (Problem e) {
                res.setStatus(e.status);
                error = e.getMessage();
            }
        }
        String attr = "type='number' min='-25' max='25' step='0.1' required";
        String screen = "<div class='check-controls'>" + nav + link("← Payroll checks", CHECKS + "?pay_date=" + day, "worker-back worker-text-button") +
            "<header class='section-head'><h1>Check alignment</h1></header><form method='post' class='panel stack' action='" +
            SETTINGS + "'>" + hidden("pay_date", day.toString()) + alert(error) +
            (!post && param(req, "saved").equals("1") ? notice("Printer alignment saved.") : "") +
            "<h2>Plain-paper test</h2><p>Print the VOID sample on plain US Letter paper and hold it against a blank check sheet. " +
            "When comparing with an earlier print, use the same PDF viewer, scale and margins. For a new printer, start at actual size (100%) " +
            "with margins, headers and footers turned off. The scale mark below the second stub should measure one inch.</p>" +
            "<div>" + link("Download revised VOID check PDF", SAMPLE_DOWNLOAD, "btn secondary") + "</div>" +
            "<p>The download uses zero offsets. Print this page to test your saved offsets.</p>" +
            "<p>If every field needs the same adjustment, enter a positive offset to move right or down, or a negative offset to move left or up.</p>" +
            "<div class='two'>" + field("Horizontal offset (mm)", "x_mm", x, attr) + field("Vertical offset (mm)", "y_mm", y, attr) +
            "</div><div><button name='action' value='offsets'>Save printer alignment</button></div></form></div>";
        ObjectNode run = App.JSON.createObjectNode().put("pay_date", day.toString()).put("start_date", day.minusDays(11).toString())
            .put("end_date", day.minusDays(5).toString());
        ObjectNode worker = App.JSON.createObjectNode().put("name", "VOID · Alignment sample").put("gross", new BigDecimal("12.34"))
            .put("mailing_address", "123 Sample Lane\nSample City, ST 00000")
            .put("days_worked", 1).put("feet_buried", 12);
        worker.putArray("details").addObject().put("date", value(run, "start_date")).put("amount", new BigDecimal("12.34"));
        page(res, admin, "Check alignment", STYLES + screen + "<div class='check-pages'>" + sheet(run, worker, true) + "</div>");
    }

    private static String sheet(JsonNode run, JsonNode worker, boolean sample) {
        LocalDate pay = LocalDate.parse(value(run, "pay_date"));
        String name = value(worker, "name"), period = formatDate(run, "start_date") + " – " + formatDate(run, "end_date");
        BigDecimal gross = worker.get("gross").decimalValue().setScale(2, RoundingMode.UNNECESSARY);
        String legal = amountWords(gross);
        String memo = sample ? "VOID · Alignment sample" : "Contractor pay · " + formatDate(run, "end_date");
        String nameStyle = name.codePointCount(0, name.length()) > 40 ? " compact" : "";
        String mailing = value(worker, "mailing_address").strip();
        boolean compactMail = mailing.length() > 100 || mailing.lines().count() > 4;
        if (compactMail) mailing = mailing.replaceAll("\\s+", " ");
        String stub = stub(worker, gross, period, pay, memo, sample);
        String scaleMark = sample ? "<div class='test-scale'><span aria-hidden='true'></span>1 inch at actual size<br>Stock alignment test</div>" : "";
        return "<article class='check-sheet' aria-label='" + (sample ? "Void alignment sample" : "Check " + worker.path("check_number").asLong()) +
            "'><div class='stock-guides' aria-hidden='true'><span class='caption-payee'>Pay to the order of</span>" +
            "<span class='caption-memo'>Memo</span></div>" + scaleMark +
            "<div class='paper-content'><div class='check-face'><span class='face-date'>" + DATE.format(pay) +
            "</span><strong class='face-payee" + nameStyle + "'>" + h(name) + "</strong><span class='face-amount'>" +
            h(money(gross).substring(1)) + "</span><span class='face-words" + (legal.length() > 75 ? " compact" : "") + "'>" + h(legal) +
            "</span><span class='face-mail" + (compactMail ? " compact" : "") + "'>" + h(mailing) + "</span><span class='face-memo'>" + h(memo) +
            "</span></div><div class='stub-first'>" + stub + "</div><div class='stub-second'>" + stub + "</div></div></article>";
    }

    private static String stub(JsonNode worker, BigDecimal gross, String period, LocalDate pay, String memo, boolean sample) {
        StringBuilder amounts = new StringBuilder();
        var daily = new TreeMap<String,BigDecimal>();
        for (JsonNode detail : worker.path("details"))
            if (detail.hasNonNull("date") && detail.path("amount").isNumber())
                daily.merge(value(detail, "date"), detail.get("amount").decimalValue(), BigDecimal::add);
        if (!daily.isEmpty() && daily.size() <= 7) {
            amounts.append("<div class='stub-days'>");
            daily.forEach((date, amount) -> amounts.append("<span>").append(SHORT_DATE.format(LocalDate.parse(date)))
                .append("<br>").append(h(money(amount))).append("</span>"));
            amounts.append("</div>");
        }
        return "<section class='check-stub'><div class='stub-title'><strong>Pay stub</strong><span>" +
            (sample ? "VOID · Sample" : "Check #" + worker.path("check_number").asLong()) +
            "</span></div><div class='stub-row stub-date'><b>Date</b><span>" + DATE.format(pay) +
            "</span></div><div class='stub-row'><b>Payee</b><span>" + h(value(worker, "name")) +
            "</span></div><div class='stub-row'><b>Gross pay</b><strong>" + h(money(gross)) +
            "</strong></div><div class='stub-row stub-memo'><b>Memo</b><span>" + h(memo) +
            "</span></div><div class='stub-stats'><b>Work period</b> " + period + " &nbsp; Days worked · " + h(value(worker, "days_worked")) +
            " &nbsp; Crew feet buried · " + h(value(worker, "feet_buried")) + "</div>" + amounts + "</section>";
    }

    static String amountWords(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(PayrollRuns.MAX_CHECK) > 0)
            throw new Problem(422, "Amount is outside the printable check range.");
        long cents;
        try { cents = amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new Problem(422, "A check must use an exact amount in cents."); }
        int dollars = Math.toIntExact(cents / 100);
        List<String> groups = new ArrayList<>();
        if (dollars >= 1_000_000) groups.add(group(dollars / 1_000_000) + " million");
        if ((dollars / 1000) % 1000 != 0) groups.add(group((dollars / 1000) % 1000) + " thousand");
        if (dollars % 1000 != 0 || groups.isEmpty()) groups.add(group(dollars % 1000));
        String words = String.join(" ", groups);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + " and " + String.format(Locale.US, "%02d/100", cents % 100);
    }

    private static String group(int n) {
        if (n < 20) return SMALL[n];
        if (n < 100) return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + SMALL[n % 10]);
        return SMALL[n / 100] + " hundred" + (n % 100 == 0 ? "" : " " + group(n % 100));
    }

    private static String formatDate(JsonNode item, String key) { return DATE.format(LocalDate.parse(value(item, key))); }
    private static String money(BigDecimal amount) { return NumberFormat.getCurrencyInstance(Locale.US).format(amount); }
}
