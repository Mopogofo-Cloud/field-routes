package fieldops;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static fieldops.StoreSql.change;
import static org.junit.jupiter.api.Assertions.*;

class MainlineExportTest {
    @Test void selectAllOrSpecificDailiesAndEmbedOnlyTheirImages() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Db.initialize(db);
            var admin = App.JSON.createObjectNode().put("is_admin",true);
            change(db,"""
                INSERT INTO people(id,username,name,password_hash,is_admin) VALUES
                (1,'worker','Crew Member','unused',0),(2,'other','Other Member','unused',0)
                """);
            change(db,"INSERT INTO mainline_projects(id,name,state) VALUES(1,'Build & Survey','In Progress'),(2,'Other','Not Started')");
            change(db,"""
                INSERT INTO mainline_dailies(id,project_id,date,footage_hundredths,expenses,comments) VALUES
                (1,1,'2026-11-01',1250,'[{"type":"Hotel","cost":120.50}]','Under drive'),
                (2,1,'2026-11-02',500,'[]','Excluded comment'),(3,2,'2026-11-02',0,'[]','Other project')
                """);
            change(db,"""
                INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver,crew_role) VALUES
                (1,1,'Crew Member',1,'Machine Operator'),(2,1,'Crew Member',0,'Locator')
                """);
            change(db,"INSERT INTO mainline_daily_members(daily_id,person_id,name,is_driver) VALUES(2,2,'Other Member',0)");
            FleetImport.run(db,App.JSON.createArrayNode().add(FleetImportTest.vehicle("09012","N/A")),true);
            long imported = StoreSql.one(db,"SELECT vehicle_id FROM vehicle_source_profiles WHERE source_record_id='09012'")
                .path("vehicle_id").asLong();
            long manual = StoreSql.insertId(db,"INSERT INTO vehicles(name) VALUES('Local unit')");
            Mainline.updateVehicles(db,admin,1,Set.of(imported,manual),0);
            var original = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16,8,BufferedImage.TYPE_INT_RGB),"png",original);
            byte[] bytes = original.toByteArray();
            for (int i = 1; i <= 13; i++) change(db,
                "INSERT INTO mainline_media(id,daily_id,category,mime,byte_count,content) VALUES(?,?,?,'image/png',?,?)",
                i, i==13 ? 2 : 1, i==1 ? "receipts" : "pictures", bytes.length, bytes);

            assertEquals(403, assertThrows(Problem.class, () -> MainlineExport.load(db,
                App.JSON.createObjectNode().put("id",1),1,null,true)).status);
            assertEquals(422,assertThrows(Problem.class,() -> MainlineExport.load(db,admin,1,Set.of(1L,3L),true)).status);
            assertEquals(422,assertThrows(Problem.class,() -> MainlineExport.load(db,admin,1,Set.of(),false)).status);
            var selected = MainlineExport.load(db,admin,1,Set.of(1L),true);
            var out = new ByteArrayOutputStream();
            MainlineExport.write(out,selected,(daily,id) -> {
                assertEquals(1L,daily);
                assertNotEquals(13L,id);
                return new Store.Photo("image/png",bytes);
            });
            var entries = unpack(out.toByteArray());
            assertEquals(12,entries.keySet().stream().filter(e -> e.startsWith("xl/media/")).count());
            assertArrayEquals(bytes,entries.get("xl/media/sheet6-photo1.png"));
            assertTrue(xml(entries,"xl/worksheets/sheet1.xml").contains("Build &amp; Survey"));
            String daily = xml(entries,"xl/worksheets/sheet2.xml");
            assertTrue(daily.contains("Under drive"));
            assertFalse(daily.contains("Excluded comment"));
            assertTrue(daily.contains("<v>12.5</v>"));
            var firstCrew = List.of("1","2026-11-01","Crew Member","Yes","Assigned","Machine Operator");
            var crewHeader = List.of("Daily ID","Date","Crew member","Driver","Crew status","Daily role");
            assertEquals(List.of(crewHeader,firstCrew),sheetRows(entries,3));
            assertEquals(List.of(
                List.of("Daily ID","Date","Vehicle","Brand","Plate or VIN","Record ID"),
                List.of("1","2026-11-01","FORD F150","FORD","","09012"),
                List.of("1","2026-11-01","Local unit","","","")),sheetRows(entries,4));
            assertTrue(xml(entries,"xl/worksheets/sheet5.xml").contains("<v>120.5"));
            assertTrue(xml(entries,"xl/worksheets/sheet6.xml").contains("Receipts"));
            assertTrue(xml(entries,"xl/worksheets/_rels/sheet6.xml.rels").contains("../drawings/drawing6.xml"));
            assertTrue(xml(entries,"xl/drawings/_rels/drawing6.xml.rels").contains("../media/sheet6-photo1.png"));
            String drawing = xml(entries,"xl/drawings/drawing6.xml");
            assertEquals(12,drawing.split("<xdr:oneCellAnchor>",-1).length-1);
            assertTrue(drawing.contains("cx='2324100' cy='1162050'"));
            out.reset();
            MainlineExport.write(out, MainlineExport.load(db,admin,1,null,false), (d,i)->fail("Photos were disabled"));
            entries = unpack(out.toByteArray());
            assertEquals(List.of(crewHeader,List.of("2","2026-11-02","Crew Member","No","Assigned","Locator"),
                List.of("2","2026-11-02","Other Member","No","Assigned",""),firstCrew),sheetRows(entries,3));
            assertTrue(xml(entries,"xl/worksheets/sheet2.xml").contains("Excluded comment"));
            assertFalse(entries.containsKey("xl/drawings/drawing6.xml"));
            assertFalse(xml(entries,"xl/workbook.xml").contains("Images"));
        }
    }

    private static Map<String,byte[]> unpack(byte[] workbook) throws Exception {
        var result = new HashMap<String,byte[]>();
        var parser = DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                byte[] data = zip.readAllBytes();
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels"))
                    parser.newDocumentBuilder().parse(new ByteArrayInputStream(data));
                assertNull(result.put(entry.getName(),data));
            }
        }
        return result;
    }

    private static List<List<String>> sheetRows(Map<String,byte[]> workbook,int sheetNumber) throws Exception {
        var sheet = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(workbook.get("xl/worksheets/sheet" + sheetNumber + ".xml")));
        var result = new ArrayList<List<String>>();
        var rows = sheet.getElementsByTagName("row");
        for (int i=0; i<rows.getLength(); i++) {
            var cells = ((org.w3c.dom.Element) rows.item(i)).getElementsByTagName("c");
            var row = new ArrayList<String>();
            for (int j=0; j<cells.getLength(); j++) row.add(cells.item(j).getTextContent());
            result.add(row);
        }
        return result;
    }

    private static String xml(Map<String,byte[]> entries, String key) {
        return new String(entries.get(key), StandardCharsets.UTF_8);
    }
}
