package fieldops;

import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Excel drawing parts for a sheet of original photos. Only one image is held in memory at a time. */
final class ExcelPictures {
    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/";
    private ExcelPictures() {}

    record Picture(long daily, String date, String category, long id) {}
    @FunctionalInterface interface PhotoReader { Store.Photo read(long daily, long id) throws IOException; }
    private record Anchor(String path, int width, int height) {}

    static ExcelWorkbook.Sheet sheet(List<Picture> images, PhotoReader reader) {
        return new ExcelWorkbook.Sheet("Images", rows -> {
            rows.write("Daily ID", "Date", "Gallery", "Image", "Photo ID");
            for (var image : images) {
                String label = switch (image.category()) {
                    case "map" -> "Map";
                    case "pictures" -> "Pictures";
                    case "receipts" -> "Receipts";
                    default -> throw new IOException("Unknown image gallery.");
                };
                rows.write(image.daily(), image.date(), label, null, image.id());
            }
        }, (zip, sheet) -> write(zip, sheet, images, reader));
    }

    private static void write(ZipOutputStream zip, int sheet, List<Picture> images, PhotoReader reader) throws IOException {
        var anchors = new ArrayList<Anchor>(images.size());
        zip.setLevel(Deflater.NO_COMPRESSION);
        for (var image : images) {
            Store.Photo photo = reader.read(image.daily(), image.id());
            String extension = switch (photo.mime()) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/webp" -> ".webp";
                default -> throw new IOException("Unknown project photo format.");
            };
            String name = "sheet" + sheet + "-photo" + image.id() + extension;
            zip.putNextEntry(new ZipEntry("xl/media/" + name));
            zip.write(photo.content());
            zip.closeEntry();
            Dimension size = dimensions(photo);
            double scale = Math.min(244.0 / size.width, 125.0 / size.height);
            anchors.add(new Anchor(name,Math.max(1,(int)Math.round(size.width * scale)),
                Math.max(1,(int)Math.round(size.height * scale))));
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        zip.putNextEntry(new ZipEntry("xl/drawings/_rels/drawing" + sheet + ".xml.rels"));
        xml(zip, "<Relationships xmlns='" + PACKAGE + "'>");
        for (int i = 0; i < anchors.size(); i++) {
            xml(zip,"<Relationship Id='img" + (i + 1) + "' Type='" + OFFICE + "/image' Target='../media/" + anchors.get(i).path() + "'/>");
        }
        xml(zip, "</Relationships>");
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/drawings/drawing" + sheet + ".xml"));
        xml(zip, "<xdr:wsDr xmlns:xdr='" + DRAWING + "spreadsheetDrawing' xmlns:a='" + DRAWING + "main' xmlns:r='" + OFFICE + "'>");
        for (int i = 0; i < anchors.size(); i++) {
            var anchor = anchors.get(i);
            int id = i + 1;
            // Drawing positions are zero-based; the first worksheet row holds the column headings.
            xml(zip, """
                <xdr:oneCellAnchor><xdr:from><xdr:col>3</xdr:col><xdr:colOff>28575</xdr:colOff>
                <xdr:row>%d</xdr:row><xdr:rowOff>28575</xdr:rowOff></xdr:from>
                <xdr:ext cx='%d' cy='%d'/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id='%d' name='Photo %d'/>
                <xdr:cNvPicPr><a:picLocks noChangeAspect='1'/></xdr:cNvPicPr></xdr:nvPicPr>
                <xdr:blipFill><a:blip r:embed='img%d'/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>
                <xdr:spPr><a:prstGeom prst='rect'><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>
                <xdr:clientData/></xdr:oneCellAnchor>
                """.formatted(id, anchor.width() * 9525L, anchor.height() * 9525L, id, images.get(i).id(), id));
        }
        xml(zip, "</xdr:wsDr>");
        zip.closeEntry();
    }

    private static void xml(ZipOutputStream zip, String xml) throws IOException {
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
    }

    // Read only the canvas dimensions. Report generation must never decompress a full-size field photo.
    private static Dimension dimensions(Store.Photo photo) {
        byte[] bytes = photo.content();
        if ("image/webp".equals(photo.mime()) && bytes.length >= 30) {
            var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            String type = new String(bytes,12,4,StandardCharsets.US_ASCII);
            switch (type) {
                case "VP8X": return new Dimension(1 + (header.getInt(24) & 0xffffff), 1 +
                    (Byte.toUnsignedInt(bytes[27]) | Byte.toUnsignedInt(bytes[28]) << 8 | Byte.toUnsignedInt(bytes[29]) << 16));
                case "VP8L": {
                    int bits = header.getInt(21);
                    return new Dimension(1 + (bits & 0x3fff), 1 + ((bits >>> 14) & 0x3fff));
                }
                case "VP8 ": {
                    int width = header.getShort(26) & 0x3fff, height = header.getShort(28) & 0x3fff;
                    if (width > 0 && height > 0) return new Dimension(width,height);
                }
            }
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input,true,true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width > 0 && height > 0) return new Dimension(width,height);
                } finally { reader.dispose(); }
            }
        } catch (IOException ignored) { /* Preserve the original even if its preview size is unavailable. */ }
        return new Dimension(4,3);
    }
}
