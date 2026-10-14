package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

public class DocxParser implements DocumentParser {
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*(\\d)");

    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            TextBuilder tb = new TextBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    paragraph(tb, p);
                } else if (el instanceof XWPFTable t) {
                    // Tables are flattened: one block per row, cells joined with " | ".
                    for (XWPFTableRow row : t.getRows()) {
                        StringBuilder sb = new StringBuilder();
                        for (XWPFTableCell c : row.getTableCells()) {
                            if (!sb.isEmpty()) sb.append(" | ");
                            sb.append(c.getText());
                        }
                        tb.block(Kind.TABLE_ROW, sb.toString());
                    }
                }
            }
            return DocumentParser.nonEmpty(tb, "DOCX");
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new UnsupportedDocumentException("Password-protected documents are not supported");
        } catch (org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException e) {
            throw new UnsupportedDocumentException("Password-protected or legacy Word files are not supported");
        }
    }

    private static void paragraph(TextBuilder tb, XWPFParagraph p) {
        String text = p.getText();
        String style = p.getStyle();
        if (style != null) {
            Matcher m = HEADING_STYLE.matcher(style);
            if (m.find()) {
                tb.heading(Math.max(1, Integer.parseInt(m.group(1))), text);
                return;
            }
            if (style.equalsIgnoreCase("Title")) {
                tb.heading(1, text);
                return;
            }
        }
        tb.block(p.getNumID() != null ? Kind.LIST_ITEM : Kind.PARAGRAPH, text);
    }
}
