package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

class JobReportWorkbookTest {
    private static ArrayNode reports() throws Exception {
        return (ArrayNode)App.JSON.readTree("""
            [{
                "route":{"id":3,"date":"2026-11-20","team_name":"North","entity_name":"Utility",
                    "crew_names":["Lead","Crew member"]},
                "job":{"id":18,"external_job_id":"000014","address":"=A1","position":2,
                    "stop_label":"damage","urgent":true,"status":"pending","outcome":"pending",
                    "outcome_reason":"Needs access","tech_needed":false,"feet_buried":0,"fiber_type":"Fiber C",
                    "comments":"A < B & C","submitted_by":"Lead","submitted_at":"2026-11-20T08:00:00Z",
                    "last_edited_by":"Dispatcher","last_edited_at":"2026-11-20T12:00:00Z","removed_at":"2026-11-21T16:00:00Z",
                    "bores":[{"type":"sidewalk","feet":5},{"type":"driveway","feet":15.25}],
                    "conduits":[{"type":"existing","feet":4.5},{"type":"existing","feet":2}],
                    "items":[{"item":"Vault","quantity":0.5,"unit":"box"},{"item":"Vault","quantity":8,"unit":"each"}],
                    "notes":[{"id":24,"text":"=HYPERLINK()","author":"Dispatcher","created_at":"2026-11-20T09:00:00Z"}],
                    "photos":[{"id":9},{"id":10}]}
            }]
            """);
    }

    private static Map<String,byte[]> entries(byte[] bytes) throws Exception {
        var result = new HashMap<String,byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                assertNull(result.put(entry.getName(),zip.readAllBytes()));
        }
        return result;
    }

    private static Map<String,Document> sheets(byte[] bytes) throws Exception {
        var parts = entries(bytes);
        var parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var sheetList = parser.parse(new ByteArrayInputStream(parts.get("xl/workbook.xml"))).getElementsByTagName("sheet");
        var result = new HashMap<String,Document>();
        for (int i = 0; i < sheetList.getLength(); i++) {
            String name = ((Element)sheetList.item(i)).getAttribute("name");
            var sheet = parser.parse(new ByteArrayInputStream(parts.get("xl/worksheets/sheet" + (i + 1) + ".xml")));
            assertEquals(0, sheet.getElementsByTagName("f").getLength());
            assertEquals(1, sheet.getElementsByTagName("pane").getLength());
            assertEquals(1, sheet.getElementsByTagName("autoFilter").getLength());
            result.put(name, sheet);
        }
        assertEquals(6, result.size());
        return result;
    }

    private static Element cell(Document sheet, String ref) {
        var cells = sheet.getElementsByTagName("c");
        for (int i=0; i<cells.getLength(); i++) {
            var element = (Element)cells.item(i);
            if (element.getAttribute("r").equals(ref)) return element;
        }
        return null;
    }

    @Test void allReportedValuesAndRepeatedWorkAreLinkedWithoutFormulaEvaluation() throws Exception {
        var data = reports();
        var assigned = ((ObjectNode)data.at("/0/route")).putArray("members");
        assigned.addObject().put("id",101).put("name","Lead").put("role","Driver");
        assigned.addObject().put("id",102).put("name","Crew member").put("role","Helper");
        var extra = data.addObject();
        extra.set("route",data.at("/0/route").deepCopy());
        extra.putObject("job").put("id",19).put("external_job_id","000014").put("position",3);
        var bytes = new ByteArrayOutputStream();
        JobReportWorkbook.write(bytes, data);
        var book = sheets(bytes.toByteArray());
        var jobs = book.get("Jobs");
        assertEquals(List.of("2","18","000014","=A1","North","Utility","Lead, Crew member","damage","Yes",
            "pending","pending","Needs access","No","0","Fiber C","A < B & C","Lead","2026-11-20T08:00:00Z",
            "Dispatcher","2026-11-20T12:00:00Z","Yes","2026-11-21T16:00:00Z","2"),
            java.util.stream.IntStream.rangeClosed('C','Y').mapToObj(ch -> cell(jobs,(char)ch + "2").getTextContent()).toList());
        assertEquals("inlineStr",cell(jobs,"E2").getAttribute("t"));
        assertEquals("inlineStr",cell(jobs,"F2").getAttribute("t"));
        assertEquals("",cell(jobs,"P2").getAttribute("t"));
        assertNull(cell(jobs,"O3"));
        assertNull(cell(jobs,"Z1"));
        assertEquals(3,book.get("Crew").getElementsByTagName("row").getLength());
        assertEquals("101",cell(book.get("Crew"),"F2").getTextContent());
        assertEquals("Helper",cell(book.get("Crew"),"G3").getTextContent());
        for (var name : List.of("Bores","Conduits","Items")) {
            var sheet = book.get(name);
            assertEquals(3,sheet.getElementsByTagName("row").getLength());
            assertEquals("18",cell(sheet,"B3").getTextContent());
            assertEquals("000014",cell(sheet,"C3").getTextContent());
            assertEquals("2",cell(sheet,"G3").getTextContent());
            assertEquals("",cell(sheet,"I3").getAttribute("t"));
        }
        assertEquals("15.25",cell(book.get("Bores"),"I3").getTextContent());
        assertEquals("existing",cell(book.get("Conduits"),"H3").getTextContent());
        assertEquals("0.5",cell(book.get("Items"),"I2").getTextContent());
        assertEquals("each",cell(book.get("Items"),"J3").getTextContent());
        assertEquals("Dispatcher",cell(book.get("Notes"),"I2").getTextContent());
        assertEquals("=HYPERLINK()",cell(book.get("Notes"),"J2").getTextContent());
    }

    @Test void nestedWorkbookAndOriginalsShareFolderAndCallerRetainsTheZipStream() throws Exception {
        var data = reports();
        var out = new ByteArrayOutputStream();
        String folder = JobReportWorkbook.photoFolder(data.get(0));
        assertEquals("photos/2026-11-20/job-18/",folder);
        try (var outer = new ZipOutputStream(out)) {
            outer.putNextEntry(new ZipEntry("reports.xlsx"));
            JobReportWorkbook.write(outer, data, true);
            outer.closeEntry();
            outer.putNextEntry(new ZipEntry(folder + "9.png"));
            outer.write(new byte[]{1,2,3});
            outer.closeEntry();
        }
        var files = entries(out.toByteArray());
        assertArrayEquals(new byte[]{1,2,3},files.get(folder + "9.png"));
        var jobs = sheets(files.get("reports.xlsx")).get("Jobs");
        assertEquals("Photo folder",cell(jobs,"Z1").getTextContent());
        assertEquals(folder,cell(jobs,"Z2").getTextContent());
    }

    @Test void preservesEveryNameWhenCrewExceedsExcelsCellLimit() throws Exception {
        var data = reports();
        var crew = ((ObjectNode)data.at("/0/route")).putArray("crew_names");
        for (int i=0; i<500; i++) crew.add("Contractor " + i + " " + "x".repeat(75));
        var bytes = new ByteArrayOutputStream();
        JobReportWorkbook.write(bytes,data);
        var book = sheets(bytes.toByteArray());
        assertEquals("See Crew sheet (500 members)",cell(book.get("Jobs"),"I2").getTextContent());
        assertEquals(501,book.get("Crew").getElementsByTagName("row").getLength());
        assertEquals(crew.get(499).textValue(),cell(book.get("Crew"),"E501").getTextContent());
    }
}
