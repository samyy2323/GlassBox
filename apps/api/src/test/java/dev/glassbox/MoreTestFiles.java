package dev.glassbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** In-memory fixtures for spreadsheets, slides, OpenDocument, e-books and RTF, built with the same libraries the parsers use. */
public final class MoreTestFiles {
    private MoreTestFiles() {}

    private static byte[] bytes(Workbook wb) {
        try (wb) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sheet "Prices" (header, two rows and a formula), sheet "Notes" (one line) and a hidden sheet that must be skipped. */
    private static void fill(Workbook wb) {
        Sheet prices = wb.createSheet("Prices");
        String[][] rows = {{"Plan", "Price", "Seats"}, {"Basic", "10", "1"}, {"Team", "40", "5"}};
        for (int r = 0; r < rows.length; r++) {
            Row row = prices.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                if (r > 0 && c > 0) row.createCell(c).setCellValue(Double.parseDouble(rows[r][c]));
                else row.createCell(c).setCellValue(rows[r][c]);
            }
        }
        Row total = prices.createRow(3);
        total.createCell(0).setCellValue("Total");
        total.createCell(1).setCellFormula("SUM(B2:B3)");
        wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("Prices exclude VAT");
        wb.createSheet("Secret").createRow(0).createCell(0).setCellValue("hidden sheet text");
        wb.setSheetHidden(2, true);
    }

    public static byte[] xlsx() {
        XSSFWorkbook wb = new XSSFWorkbook();
        fill(wb);
        return bytes(wb);
    }

    public static byte[] xls() {
        HSSFWorkbook wb = new HSSFWorkbook();
        fill(wb);
        return bytes(wb);
    }

    public static byte[] pptx() {
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.Rectangle(50, 50, 500, 100));
            box.setText("Quarterly safety review");
            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new java.awt.Rectangle(50, 200, 500, 100));
            body.setText("Incidents fell by 40 percent");
            show.createSlide().createTextBox().setText("Second slide text");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] ppt() {
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFSlide slide = show.createSlide();
            HSLFTextBox box = slide.createTextBox();
            box.setText("Legacy slide about forklift training");
            box.setAnchor(new java.awt.Rectangle(50, 50, 400, 100));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (var e : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] odt() {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
                  <office:body><office:text>
                    <text:h text:outline-level="1">Maintenance handbook</text:h>
                    <text:p>Valves are serviced every six months.</text:p>
                    <text:list><text:list-item><text:p>Close the supply line</text:p></text:list-item></text:list>
                    <table:table table:name="Contacts"><table:table-row>
                      <table:table-cell><text:p>Engineer</text:p></table:table-cell><table:table-cell><text:p>Dana</text:p></table:table-cell>
                    </table:table-row></table:table>
                  </office:text></office:body></office:document-content>""";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("mimetype", "application/vnd.oasis.opendocument.text");
        files.put("content.xml", content);
        return zip(files);
    }

    /**
     * Two chapters whose file names sort in the OPPOSITE order of the reading order given by the spine, so a parser that
     * just sorts file names is caught.
     */
    public static byte[] epub() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("mimetype", "application/epub+zip");
        files.put("META-INF/container.xml", """
                <?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""");
        files.put("OEBPS/content.opf", """
                <?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><manifest>
                <item id="one" href="z-first.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="a-second.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="one"/><itemref idref="two"/></spine></package>""");
        files.put("OEBPS/z-first.xhtml", "<html><body><h1>Chapter One</h1><p>It begins in the harbour.</p></body></html>");
        files.put("OEBPS/a-second.xhtml", "<html><body><h1>Chapter Two</h1><p>It ends at the lighthouse.</p></body></html>");
        return zip(files);
    }

    public static final String RTF = "{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Boilers are checked every autumn.\\par\\par "
            + "{\\b Second paragraph} about chimneys.\\par}";
}
