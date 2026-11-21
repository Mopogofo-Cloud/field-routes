package fieldops;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streaming OOXML writer for the application's tabular exports. */
final class ExcelWorkbook {
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";

    @FunctionalInterface interface Rows { void write(Object... cells) throws IOException; }
    @FunctionalInterface interface Body { void write(Rows rows) throws IOException; }
    @FunctionalInterface interface Drawing { void write(ZipOutputStream zip, int sheetId) throws IOException; }
    record Sheet(String name, Body body, Drawing drawing) {
        Sheet(String name, Body body) { this(name, body, null); }
    }

    private ExcelWorkbook() {}

    static void write(OutputStream stream, Sheet... sheets) throws IOException {
        // A workbook can be one entry in a larger ZIP. Release its deflater without closing the caller's stream.
        var borrowed = new FilterOutputStream(stream) {
            @Override public void close() throws IOException { flush(); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException { out.write(bytes, offset, count); }
        };
        try (var zip = new ZipOutputStream(borrowed, StandardCharsets.UTF_8)) {
            StringBuilder types = new StringBuilder("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                """);
            StringBuilder book = new StringBuilder("<workbook xmlns='" + MAIN + "' xmlns:r='" + REL + "'><sheets>");
            StringBuilder links = new StringBuilder("<Relationships xmlns='" + RELS + "'>");
            boolean pictureTypes = false;
            for (int i = 0; i < sheets.length; i++) {
                int id = i + 1;
                String file = "worksheets/sheet" + id + ".xml";
                types.append("<Override PartName='/xl/").append(file)
                    .append("' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'/>");
                book.append("<sheet name='").append(Views.h(sheets[i].name())).append("' sheetId='").append(id)
                    .append("' r:id='sheet").append(id).append("'/>");
                links.append("<Relationship Id='sheet").append(id).append("' Type='").append(REL)
                    .append("/worksheet' Target='").append(file).append("'/>");
                Drawing drawing = sheets[i].drawing();
                sheet(zip, "xl/" + file, sheets[i].body(), drawing != null);
                if (drawing != null) {
                    if (!pictureTypes) {
                        types.append("<Default Extension='jpg' ContentType='image/jpeg'/><Default Extension='png' ContentType='image/png'/>")
                            .append("<Default Extension='webp' ContentType='image/webp'/>");
                        pictureTypes = true;
                    }
                    types.append("<Override PartName='/xl/drawings/drawing").append(id)
                         .append(".xml' ContentType='application/vnd.openxmlformats-officedocument.drawing+xml'/>");
                    part(zip, "xl/worksheets/_rels/sheet" + id + ".xml.rels", "<Relationships xmlns='" + RELS +
                         "'><Relationship Id='pictures' Type='" + REL + "/drawing' Target='../drawings/drawing" + id + ".xml'/></Relationships>");
                    drawing.write(zip, id);
                }
            }
            part(zip, "[Content_Types].xml", types.append("</Types>").toString());
            part(zip, "_rels/.rels", "<Relationships xmlns='" + RELS + "'><Relationship Id='book' Type='" + REL +
                "/officeDocument' Target='xl/workbook.xml'/></Relationships>");
            part(zip, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
            part(zip, "xl/_rels/workbook.xml.rels", links.append("<Relationship Id='styles' Type='").append(REL)
                .append("/styles' Target='styles.xml'/></Relationships>").toString());
            part(zip, "xl/styles.xml", "<styleSheet xmlns='" + MAIN + "'>" + """
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                <xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""");
        }
    }

    private static void part(ZipOutputStream zip, String path, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void sheet(ZipOutputStream zip, String file, Body body, boolean pictures) throws IOException {
        zip.putNextEntry(new ZipEntry(file));
        Writer out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        out.write("<worksheet xmlns='" + MAIN + "'><sheetViews><sheetView workbookViewId='0'>" +
            "<pane ySplit='1' topLeftCell='A2' activePane='bottomLeft' state='frozen'/></sheetView></sheetViews>");
        var writer = new SheetRows(out, pictures);
        body.write(writer);
        out.write("</sheetData><autoFilter ref='A1:" + column(writer.columns) + writer.count + "'/>");
        if (pictures) out.write("<drawing xmlns:r='" + REL + "' r:id='pictures'/>");
        out.write("</worksheet>");
        out.flush();
        zip.closeEntry();
    }

    private static final class SheetRows implements Rows {
        private final Writer out;
        private final boolean pictures;
        int count, columns, moneyIndex = -1;
        SheetRows(Writer out, boolean pictures) { this.out = out; this.pictures = pictures; }

        public void write(Object... cells) throws IOException {
            if (count == 0) {
                columns = cells.length;
                out.write("<cols>");
                for (int i = 0; i < columns; i++) {
                    String heading = cells[i].toString();
                    if (heading.equals("Gross pay") || heading.equals("Line gross")) moneyIndex = i;
                    int width = switch (heading) {
                        case "Image" -> 38;
                        case "Quantities and rates", "Review reason", "Expression", "Address", "Assigned crew", "Comments",
                             "Outcome reason", "Note", "Photo folder" -> 48;
                        case "Contractor", "Entity", "Crew", "Formula", "Team", "Contracting entity", "Author",
                             "Submitted by", "Last edited by", "Submitted at (UTC)", "Last edited at (UTC)",
                             "Created at (UTC)", "Removed at (UTC)" -> 26;
                        default -> Math.max(14, heading.length() + 3);
                    };
                    out.write("<col min='" + (i + 1) + "' max='" + (i + 1) + "' width='" + width + "' customWidth='1'/>");
                }
                out.write("</cols><sheetData>");
            }
            if (++count > 1_048_576) throw new IOException("Excel's worksheet row limit was exceeded. Select a shorter export period.");
            out.write("<row r='" + count + "'" + (pictures && count > 1 ? " ht='100' customHeight='1'" : "") + ">");
            for (int i = 0; i < cells.length; i++) {
                Object value = cells[i];
                if (value instanceof JsonNode n) {
                    if (n.isNumber()) value = n.decimalValue().toPlainString();
                    else if (n.isNull() || n.isMissingNode()) continue;
                    else value = n.asText();
                }
                if (value == null) continue;
                String ref = column(i + 1) + count;
                if (cells[i] instanceof Number || cells[i] instanceof JsonNode n && n.isNumber())
                    out.write("<c r='" + ref + "'" + (i == moneyIndex ? " s='2'" : "") + "><v>" + value + "</v></c>");
                else
                    out.write("<c r='" + ref + "' t='inlineStr'" + (count == 1 ? " s='1'" : "") +
                        "><is><t xml:space='preserve'>" + xmlText(value.toString()) + "</t></is></c>");
            }
            out.write("</row>");
        }
    }

    private static String xmlText(String text) {
        StringBuilder clean = new StringBuilder();
        text.codePoints().forEach(c -> clean.appendCodePoint(c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xd7ff
            || c >= 0xe000 && c <= 0xfffd || c >= 0x10000 && c <= 0x10ffff ? c : 0xfffd));
        return Views.h(clean.toString());
    }

    private static String column(int index) {
        StringBuilder name = new StringBuilder();
        for (int i = index; i > 0; i = (i - 1) / 26) name.append((char) ('A' + (i - 1) % 26));
        return name.reverse().toString();
    }
}
