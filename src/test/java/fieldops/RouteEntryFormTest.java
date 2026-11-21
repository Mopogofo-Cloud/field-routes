package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteEntryFormTest {
    private static HttpServletRequest form(Map<String, String[]> values) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
            new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                String[] input = values.get(args[0]);
                return switch (method.getName()) {
                    case "getParameter" -> input == null ? null : input[0];
                    case "getParameterValues" -> input;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }

    private static ObjectNode bulk(String text) {
        return RouteEntryForm.read(form(Map.of("entry_mode", new String[]{"multiple"}, "entry_bulk", new String[]{text},
            "stop_label", new String[]{"damage"}, "urgent", new String[]{"1"})));
    }

    private static void expectStop(JsonNode array, int i, String address, String id) {
        assertEquals(address, array.get(i).path("address").asText());
        assertEquals(id, array.get(i).path("external_job_id").asText());
    }

    @Test void screenshotPairsKeepInternalAddressCommasAndOptionalIds() {
        var draft = bulk("123 Main St, Richmond, VA 23220: Job1, 2 West St: 001-A,\r\n" +
            "3 East Rd, Apt 2:, 4 North St:Job3,\n" +
            "5 South St, Suite 7 | 000006\n" +
            "6 Dock: rear, Bldg B\tP:45,A\n" +
            "7 Park St, Apt 3, Richmond, VA\n8 Walnut St\t\n\n");
        var entries = RouteEntryForm.stops(draft);
        assertEquals(8, entries.size());
        expectStop(entries, 0, "123 Main St, Richmond, VA 23220", "Job1");
        expectStop(entries, 1, "2 West St", "001-A");
        expectStop(entries, 2, "3 East Rd, Apt 2", "");
        expectStop(entries, 3, "4 North St", "Job3");
        expectStop(entries, 4, "5 South St, Suite 7", "000006");
        expectStop(entries, 5, "6 Dock: rear, Bldg B", "P:45,A");
        expectStop(entries, 6, "7 Park St, Apt 3, Richmond, VA", "");
        expectStop(entries, 7, "8 Walnut St", "");
    }

    @Test void malformedBulkHasLineNumberAndKeepsTheEnteredDraft() {
        for (String malformed : new String[]{"1 A:0001, 2 B", "1 A:0001 2 B:0002", "1 A\t001\textra", "1 A | 1 | 2"}) {
            String text = "Previous street\n" + malformed;
            var draft = bulk(text);
            var ex = assertThrows(Problem.class, () -> RouteEntryForm.stops(draft));
            assertEquals(422, ex.status);
            assertTrue(ex.getMessage().contains("line 2"));
            assertThrows(Problem.class, () -> RouteEntryForm.edit(draft, "entry-individual"));
            assertEquals("multiple", draft.path("mode").asText());
            assertEquals(text, draft.path("bulk").asText());
        }
        var partial = bulk("1 A:01\n:missing-address");
        assertTrue(assertThrows(Problem.class, () -> RouteEntryForm.stops(partial)).getMessage().contains("line 2"));
        RouteEntryForm.edit(partial, "entry-individual");
        expectStop(partial.path("rows"), 1, "", "missing-address");
        assertTrue(assertThrows(Problem.class, () -> RouteEntryForm.stops(partial)).getMessage().contains("row 2"));
    }

    @Test void modeSwitchAndReviewBackPreserveEveryStopAndDispatchSetting() {
        var draft = RouteEntryForm.read(form(Map.of("entry_mode", new String[]{"individual"},
            "entry_address", new String[]{"Front & rear, Apt 5", "Garage: side", "3 | East", "A", ""},
            "entry_job_id", new String[]{"001-A", "", "ID:4,5", "prefix | id", ""},
            "stop_label", new String[]{"drop"}, "urgent", new String[]{"1"})));
        var original = RouteEntryForm.stops(draft);
        RouteEntryForm.edit(draft, "entry-add");
        assertEquals(6, draft.path("rows").size());
        RouteEntryForm.edit(draft, "entry-remove-5");
        assertEquals(5, draft.path("rows").size());
        RouteEntryForm.edit(draft, "entry-multiple");
        assertEquals(original, RouteEntryForm.stops(draft));
        assertTrue(RouteEntryForm.render(draft).contains("Front &amp; rear"));
        var restored = RouteEntryForm.restore(form(Map.of("entry_draft", new String[]{draft.toString()})));
        assertEquals(draft, restored);
        RouteEntryForm.edit(restored, "entry-individual");
        assertEquals(original, RouteEntryForm.stops(restored));
        assertEquals("drop", restored.path("stop_label").asText());
        assertTrue(restored.path("urgent").asBoolean());
    }

    @Test void boundsAndFormIntegrityAreChecked() {
        assertThrows(Problem.class, () -> RouteEntryForm.stops(RouteEntryForm.blank()));
        assertThrows(Problem.class, () -> RouteEntryForm.stops(bulk("Street:" + "x".repeat(121))));
        assertThrows(Problem.class, () -> RouteEntryForm.stops(bulk("x".repeat(501))));
        String thousand = String.join("\n", Collections.nCopies(1000, "St:0001"));
        assertEquals(1000, RouteEntryForm.stops(bulk(thousand)).size());
        assertThrows(Problem.class, () -> RouteEntryForm.stops(bulk(thousand + "\nOne more")));
        assertThrows(Problem.class, () -> RouteEntryForm.read(form(Map.of("entry_mode", new String[]{"individual"},
            "entry_address", new String[]{"One", "Two"}, "entry_job_id", new String[]{"X"}))));
        assertThrows(Problem.class, () -> RouteEntryForm.restore(form(Map.of("entry_draft", new String[]{"[]"}))));
    }
}
