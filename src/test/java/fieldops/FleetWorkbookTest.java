package fieldops;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetWorkbookTest {
    @TempDir Path temp;

    @Test void readsOnlyVehicleDocumentsAndPreservesLabelsAndTargets() throws Exception {
        var records = FleetWorkbook.read(workbook("1"));
        assertEquals(2,records.size());
        var first = records.get(0);
        assertEquals("0012",first.path("record_id").asText());
        assertEquals("Unit A",first.path("company_code").asText());
        assertEquals("Cached profile label",first.path("full_profile").asText());
        assertEquals("unit-a/profile.html",first.at("/links/full_profile").asText());
        var files = first.path("source_files");
        assertEquals(2,files.size());
        assertEquals("Open photo",files.get(0).path("open_file").asText());
        assertEquals("unit-a/rack.jpg",files.get(0).path("target").asText());
        assertEquals("rack.jpg",files.get(0).path("original_filename").asText());
        assertEquals("45",files.get(0).path("bytes").asText());
        assertEquals("Unavailable",files.get(1).path("result").asText());
        assertEquals("offline",files.get(1).path("notes").asText());
        assertEquals("34",records.get(1).path("record_id").asText());
        assertEquals(1,records.get(1).path("source_files").size());
        assertEquals("other.pdf",records.get(1).at("/source_files/0/original_filename").asText());
        assertFalse(records.toString().contains("contractor-only.pdf"));
    }

    @Test void countMismatchDoesNotPublishPartialOutput() throws Exception {
        var output = temp.resolve("fleet.json");
        var problem = assertThrows(IllegalArgumentException.class,
            () -> FleetWorkbook.main(new String[]{workbook("2").toString(),output.toString()}));
        assertTrue(problem.getMessage().contains("File count disagrees"));
        assertFalse(Files.exists(output));
    }

    private Path workbook(String savedCount) throws Exception {
        var fileHeaders = List.of("Record type","Record ID","Name / company code","File type","Result",
            "Open file","Original filename","Bytes","Notes");
        var vehicles = sheet(FleetImport.HEADERS,"""
            <row><c r="A2" t="s"><v>0</v></c><c r="B2" t="inlineStr"><is><t>Unit A</t></is></c>
            <c r="I2"><v>%s</v></c><c r="K2" t="str"><f>HYPERLINK("unit-a/profile.html","Display profile")</f><v>Cached profile label</v></c></row>
            <row><c r="A3"><v>34</v></c><c r="I3"><v>1</v></c></row>
            """.formatted(savedCount));
        var docs = sheet(fileHeaders,"""
            <row><c r="A2" t="str"><v>Contractor</v></c><c r="B2" t="s"><v>0</v></c>
            <c r="E2" t="str"><v>Saved</v></c><c r="G2" t="str"><v>contractor-only.pdf</v></c></row>
            <row><c r="A3" t="str"><v>Vehicle</v></c><c r="B3"><v>34</v></c>
            <c r="E3" t="str"><v>Saved</v></c><c r="G3" t="str"><v>other.pdf</v></c></row>
            <row><c r="A4" t="str"><v>Vehicle</v></c><c r="B4" t="s"><v>0</v></c>
            <c r="E4" t="str"><v>Saved</v></c><c r="F4" t="str"><f>HYPERLINK("unit-a/rack.jpg","Open photo")</f></c>
            <c r="G4" t="str"><f>HYPERLINK("unit-a/rack.jpg","rack.jpg")</f></c><c r="H4"><v>45</v></c></row>
            <row><c r="A5" t="str"><v>Vehicle</v></c><c r="B5" t="s"><v>0</v></c>
            <c r="E5" t="str"><v>Unavailable</v></c><c r="I5" t="str"><v>offline</v></c></row>
            """);
        var parts = Map.of(
            "xl/workbook.xml","<workbook xmlns:r='urn:rels'><sheets><sheet name='Files' r:id='a'/><sheet name='Vehicles' r:id='b'/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels", """
                <Relationships><Relationship Id="a" Type="test/worksheet" Target="worksheets/sheet8.xml"/>
                <Relationship Id="b" Type="test/worksheet" Target="/xl/worksheets/sheet3.xml"/></Relationships>
                """,
            "xl/sharedStrings.xml","<sst><si><t>0012</t></si></sst>",
            "xl/worksheets/sheet3.xml",vehicles,
            "xl/worksheets/sheet8.xml",docs);
        Path target = temp.resolve("index.xlsx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }

    private static String sheet(List<String> headers, String body) {
        var row = new StringBuilder("<worksheet><sheetData><row>");
        for (int i=0; i<headers.size(); i++) {
            row.append("<c r='").append((char)('A'+i)).append("1' t='inlineStr'><is><t>")
                .append(headers.get(i)).append("</t></is></c>");
        }
        return row.append("</row>").append(body).append("</sheetData></worksheet>").toString();
    }
}
