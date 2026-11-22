package fieldops;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Reads the Vehicles table in the WG iDig backup index without following any workbook links. */
public final class FleetWorkbook {
    private FleetWorkbook() {}
    private static final Pattern CELL = Pattern.compile("([A-Z]+)([1-9][0-9]*)");
    private static final Map<String,String> FILE_COLUMNS = Map.of(
        "Record type","record_type","Record ID","record_id","Name / company code","company_code",
        "File type","file_type","Result","result","Open file","open_file","Original filename","original_filename",
        "Bytes","bytes","Notes","notes");
    private static final Pattern LINK = Pattern.compile("HYPERLINK\\(\\s*\"((?:[^\"]|\"\")*)\"\\s*,\\s*\"((?:[^\"]|\"\")*)\"\\s*\\)",Pattern.CASE_INSENSITIVE);

    private static Element xml(ZipFile file, String path) throws Exception {
        var entry = file.getEntry(path);
        if (entry == null || entry.getSize()<0 || entry.getSize()>32L*1024*1024)
            throw new IllegalArgumentException("Missing or oversized workbook part: "+path);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        try (var in=file.getInputStream(entry)) { return factory.newDocumentBuilder().parse(in).getDocumentElement(); }
    }

    private static List<Element> elements(Element parent,String tag) {
        NodeList nodes=parent.getElementsByTagName(tag);
        var list=new ArrayList<Element>(nodes.getLength());
        for(int i=0;i<nodes.getLength();i++) list.add((Element)nodes.item(i));
        return list;
    }

    private static String text(Element parent,String tag) {
        var value=new StringBuilder();
        for(var part:elements(parent,tag)) value.append(part.getTextContent());
        return value.toString();
    }

    private static String sheetPart(ZipFile file, String name) throws Exception {
        for (var sheet : elements(xml(file,"xl/workbook.xml"),"sheet")) {
            if (!name.equals(sheet.getAttribute("name"))) continue;
            for (var rel:elements(xml(file,"xl/_rels/workbook.xml.rels"),"Relationship")) {
                if (!sheet.getAttribute("r:id").equals(rel.getAttribute("Id")) ||
                    !rel.getAttribute("Type").endsWith("/worksheet")) continue;
                String target=rel.getAttribute("Target");
                if(target.matches("worksheets/sheet[0-9]+\\.xml")) return "xl/"+target;
                if(target.matches("/xl/worksheets/sheet[0-9]+\\.xml")) return target.substring(1);
            }
        }
        throw new IllegalArgumentException("Missing sheet: "+name);
    }

    static ArrayNode read(Path path) throws Exception {
        try(var file=new ZipFile(path.toFile())) {
            var shared=new ArrayList<String>();
            if(file.getEntry("xl/sharedStrings.xml")!=null)
                for(var entry:elements(xml(file,"xl/sharedStrings.xml"),"si")) shared.add(text(entry,"t"));
            var columns=new HashMap<String,String>();
            for (int i=0; i<FleetImport.HEADERS.size(); i++) columns.put(FleetImport.HEADERS.get(i),FleetImport.KEYS.get(i));
            var records=readTable(xml(file,sheetPart(file,"Vehicles")),shared,columns);
            var byId=new HashMap<String,ArrayNode>();
            for(var row:records) {
                var vehicle=(ObjectNode)row;
                if(byId.put(vehicle.get("record_id").asText(),vehicle.putArray("source_files"))!=null)
                    throw new IllegalArgumentException("Duplicate vehicle record ID");
            }
            var files=readTable(xml(file,sheetPart(file,"Files")),shared,FILE_COLUMNS);
            for(var row:files) {
                if(!row.path("record_type").asText().equalsIgnoreCase("vehicle")) continue;
                var entries=byId.get(row.path("record_id").asText());
                if(entries==null) throw new IllegalArgumentException("Vehicle document references an unknown vehicle");
                var document=App.JSON.createObjectNode();
                for(String key:List.of("file_type","result","open_file","original_filename","bytes","notes"))
                    document.put(key,row.get(key).asText());
                var links=row.path("links");
                String target=links.path("open_file").asText();
                if(links.size()>0 && (target.isEmpty() || !links.path("original_filename").asText(target).equals(target)
                        || links.size()>(links.has("original_filename") ? 2 : 1)))
                    throw new IllegalArgumentException("Vehicle file has inconsistent links");
                document.put("target",target);
                entries.add(document);
            }
            for(var row:records) {
                int saved=0;
                for(var document:row.path("source_files")) {
                    if("Saved".equalsIgnoreCase(document.get("result").asText())) saved++;
                }
                if(!Integer.toString(saved).equals(row.path("files_saved").asText()))
                    throw new IllegalArgumentException("File count disagrees with Vehicles row "+row.get("record_id").asText());
            }
            return records;
        }
    }

    private static ArrayNode readTable(Element sheet,List<String> shared,Map<String,String> headers) {
        var columns=new HashMap<String,String>();
        var records=App.JSON.createArrayNode();
        for(var row:elements(sheet,"row")) {
            var values=new HashMap<String,String>();
            var links=new HashMap<String,String>();
            for(var cell:elements(row,"c")) {
                var address=CELL.matcher(cell.getAttribute("r"));
                if(!address.matches()) throw new IllegalArgumentException("Invalid cell address");
                String column=address.group(1), value=text(cell,"v");
                if("s".equals(cell.getAttribute("t"))) value=shared.get(Integer.parseInt(value));
                if("inlineStr".equals(cell.getAttribute("t"))) value=text(cell,"t");
                String formula=text(cell,"f");
                if(!formula.isEmpty() && !columns.isEmpty()) {
                    var match=LINK.matcher(formula);
                    if(!match.matches()) throw new IllegalArgumentException("Unexpected formula in "+address.group());
                    links.put(column,match.group(1).replace("\"\"","\""));
                    if(value.isEmpty()) value=match.group(2).replace("\"\"","\"");
                }
                if(!value.isEmpty()) values.put(column,value);
            }
            if(columns.isEmpty()) {
                if(!values.containsValue("Record ID")) continue;
                values.forEach((column,label)->{
                    String key=headers.get(label);
                    if(key==null || columns.containsValue(key)) throw new IllegalArgumentException("Unrecognized or duplicate fleet header");
                    columns.put(column,key);
                });
                if(columns.size()!=headers.size()) throw new IllegalArgumentException("Incomplete workbook table header");
            } else if(!values.isEmpty()) {
                if(!columns.keySet().containsAll(values.keySet())) throw new IllegalArgumentException("Unmapped data in Vehicles sheet");
                ObjectNode record=records.addObject();
                columns.forEach((column,key)-> record.put(key,values.getOrDefault(column,"")));
                if(record.path("record_id").asText().isBlank()) throw new IllegalArgumentException("Vehicle row missing its record ID");
                var targets=record.putObject("links");
                links.forEach((column,target)->targets.put(columns.get(column),target));
            }
        }
        if(columns.isEmpty() || records.isEmpty()) throw new IllegalArgumentException("Empty Vehicles table");
        if(!elements(sheet,"hyperlink").isEmpty()) throw new IllegalArgumentException("Unexpected non-formula hyperlinks in Vehicles sheet");
        return records;
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Usage: FleetWorkbook workbook.xlsx output.json");
        var records=read(Path.of(args[0]));
        Path output=Files.createFile(Path.of(args[1]),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(var out=Files.newOutputStream(output)) {
            App.JSON.writeValue(out,records);
        } catch (java.io.IOException err) {
            Files.deleteIfExists(output);
            throw err;
        }
        System.out.println("Read "+records.size()+" vehicle records.");
    }
}
