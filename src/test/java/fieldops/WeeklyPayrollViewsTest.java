package fieldops;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyPayrollViewsTest {
    private static ObjectNode snapshot() {
        var run = App.JSON.createObjectNode().put("pay_date","2026-11-20").put("start_date","2026-11-09")
            .put("end_date","2026-11-15").put("run_status","partial");
        var workers = run.putArray("workers");
        var worker = workers.addObject().put("person_id",1).put("name","=Alpha, \"A\"").put("status","ready")
            .put("statement_status","generated").put("gross",new BigDecimal("98.55")).put("days_worked",2)
            .put("feet_buried",25).put("source_changed",true).put("check_number",7001);
        var lines = worker.putArray("details");
        var line = lines.addObject().put("date","2026-11-09").put("entity_name","@TEST").put("team_name"," \t=hidden")
            .put("basis","production").put("formula"," Earned").put("expression","feet_buried * rate")
            .put("amount",new BigDecimal("100.00")).put("feet_buried",25).put("credited_feet_buried",25)
            .put("footage_credit","full").put("crew_size",2);
        line.putObject("inputs").put("feet_buried",25).put("rate",4);
        line.putArray("reported_items").addObject().put("item","Vault").put("quantity",3).put("unit","");
        lines.addObject().put("date","2026-11-10").put("entity_name","Bore & Co").put("team_name","West")
            .put("formula","Adjustment").put("expression","-fee").put("amount",new BigDecimal("-1.45"));
        var held = workers.addObject().put("person_id",2).put("name","<Other>").put("status","needs_review")
            .put("statement_status","draft").putNull("gross").put("reason","Missing <rate>");
        held.putArray("details").addObject().put("reason","Missing\nrate");
        workers.addObject().put("person_id",3).put("name","Zero Contractor").put("status","ready")
            .put("statement_status","generated").put("gross",BigDecimal.ZERO).putArray("details");
        return run;
    }

    @Test void summaryAndDetailCsvAreExcelSafeAndKeepNegativeAmountsNumeric() throws Exception {
        var run = snapshot();
        var summary = new StringWriter();
        WeeklyPayrollViews.writeCsv(new PrintWriter(summary), run, false);
        String text = summary.toString();
        assertTrue(text.startsWith("\ufeff\"Pay date\","));
        assertTrue(text.contains("\"'=Alpha, \"\"A\"\"\""));
        assertTrue(text.contains("\"98.55\",\"Generated\",\"7001\""));
        assertTrue(text.endsWith("\r\n"));
        var detail = new StringWriter();
        WeeklyPayrollViews.writeCsv(new PrintWriter(detail), run, true);
        text = detail.toString();
        assertTrue(text.contains("\"'@TEST\",\"' \t=hidden\""));
        assertTrue(text.contains("\"'-fee\""));
        assertTrue(text.contains("\"-1.45\""));
        assertTrue(text.contains("feet_buried = 25 · rate = 4"));
        assertTrue(text.contains("\"Vault = 3\""));
        assertTrue(text.contains("\"Missing\nrate\""));
        assertFalse(text.contains("98.55"));
    }

    @Test void workbookProducesTwoValidSheetsWithLiteralStringsAndNumericAmounts() throws Exception {
        var run = snapshot();
        var output = new ByteArrayOutputStream();
        PayrollWorkbook.write(output, run);
        var parts = new HashMap<String, org.w3c.dom.Document>();
        var parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                parts.put(entry.getName(), parser.parse(new ByteArrayInputStream(zip.readAllBytes())));
        }
        var tabs = parts.get("xl/workbook.xml").getElementsByTagName("sheet");
        assertEquals("Summary", tabs.item(0).getAttributes().getNamedItem("name").getNodeValue());
        assertEquals("Pay detail", tabs.item(1).getAttributes().getNamedItem("name").getNodeValue());
        for (var path : List.of("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")) {
            var sheet = parts.get(path);
            assertEquals(0, sheet.getElementsByTagName("f").getLength());
            assertTrue(sheet.getDocumentElement().getTextContent().contains("=Alpha, \"A\""));
            assertEquals(1, sheet.getElementsByTagName("pane").getLength());
            assertEquals(1, sheet.getElementsByTagName("autoFilter").getLength());
        }
        var values = parts.get("xl/worksheets/sheet2.xml").getElementsByTagName("v");
        boolean negativeNumber = false;
        for (int i = 0; i < values.getLength(); i++) {
            String numeric = values.item(i).getTextContent();
            new BigDecimal(numeric);
            negativeNumber |= numeric.equals("-1.45");
            assertNotEquals("98.55", numeric);
        }
        assertTrue(negativeNumber);
    }

    @Test void heldOrZeroStatementsHaveNoCheckLinkAndFrozenAmountsStayIdentifiable() {
        var run = snapshot();
        String page = WeeklyPayrollViews.dashboard(run, true);
        assertTrue(page.contains("/payroll/checks/1?pay_date="));
        assertFalse(page.contains("/payroll/checks/2?") || page.contains("/payroll/checks/3?"));
        assertTrue(page.contains("Source changed since generation") && page.contains("Needs review"));
        assertTrue(page.contains("$98.55") && page.contains("Missing &lt;rate&gt;"));
        String statement = WeeklyPayrollViews.statement(run, run.path("workers").get(0));
        assertTrue(statement.contains("Contractor pay statement") && statement.contains("7001"));
        assertTrue(statement.contains("originally generated amounts") && statement.contains("Credited footage"));
        String draft = WeeklyPayrollViews.statement(run, run.path("workers").get(1));
        assertTrue(draft.contains("Draft payroll preview") && draft.contains("Amount pending review"));
        assertTrue(draft.contains("&lt;Other&gt;") && draft.contains("Missing &lt;rate&gt;"));
    }

    @Test void workerDeniedAtEveryEndpointBeforeAccessingAnyPayData() {
        var headers = new HashMap<String,String>();
        var res = response(headers);
        var worker = App.JSON.createObjectNode().put("is_admin",false);
        for (var path : List.of("/payroll","/payroll/export","/payroll/export/details","/payroll/export/workbook","/payroll/statement/42")) {
            var failure = assertThrows(Problem.class, () -> WeeklyPayrollViews.handle(null, res, null, worker, path, false, ""));
            assertEquals(403, failure.status);
            assertEquals("private, no-store", headers.get("Cache-Control"));
        }
        assertEquals(403, assertThrows(Problem.class,
            () -> WeeklyPayrollViews.handle(null, res, null, worker, "/payroll", true, "")).status);
    }

    @Test void draftShowsOneDecisionPerPendingDayAndFrozenStatementHasNoReviewControls() {
        var run = snapshot();
        var worker = (ObjectNode)run.path("workers").get(1);
        var lines = worker.withArray("details");
        var day = ((ObjectNode)lines.get(0)).put("date","2026-11-11").put("entity_name","Fixture").put("team_name","Crew")
            .put("review_key","day-one").put("review_decision","include").put("pending_jobs",1).put("canceled_jobs",0);
        day.putArray("report_outcomes").addObject().put("job_id",12).put("outcome","pending").put("reason","Needs <access>");
        lines.add(day.deepCopy());
        String draft = WeeklyPayrollViews.statement(run, worker);
        assertEquals(2, draft.split("name='review_key'",-1).length);
        assertTrue(draft.contains("name='previous_decision' value='include'"));
        assertTrue(draft.contains("value='include' disabled") && draft.contains("value='exclude'>Exclude from pay"));
        assertTrue(draft.contains("href='/jobs/12'") && draft.contains("Needs &lt;access&gt;"));
        worker.put("statement_status","generated");
        assertFalse(WeeklyPayrollViews.statement(run, worker).contains("name='review_key'"));
    }

    @Test void futurePayrollCannotBeGeneratedAndPayDatesMustBeFridays() {
        var admin = App.JSON.createObjectNode().put("is_admin",true);
        LocalDate future = PayrollRuns.latestFriday().plusWeeks(2);
        var res = response(new HashMap<>());
        for (var date : List.of(future, future.plusDays(1))) {
            var req = request(Map.of("pay_date", date.toString(), "action", "generate"));
            assertEquals(422, assertThrows(Problem.class,
                () -> WeeklyPayrollViews.handle(req, res, null, admin, "/payroll", true, "")).status);
        }
    }

    private static HttpServletResponse response(Map<String,String> headers) {
        return (HttpServletResponse)Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
            new Class<?>[]{HttpServletResponse.class}, (p,m,a) -> {
                if (m.getName().equals("setHeader")) { headers.put((String)a[0],(String)a[1]); return null; }
                throw new UnsupportedOperationException(m.getName());
            });
    }

    private static HttpServletRequest request(Map<String,String> values) {
        return (HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[]{HttpServletRequest.class}, (p,m,a) -> {
                if (m.getName().equals("getParameter")) return values.get(a[0]);
                throw new UnsupportedOperationException(m.getName());
            });
    }
}
