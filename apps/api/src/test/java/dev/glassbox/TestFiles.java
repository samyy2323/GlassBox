package dev.glassbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Generates small real files in memory so tests never depend on binary fixtures. */
public final class TestFiles {
    private TestFiles() {}

    /** One PDF page per argument; each page's lines are separated by "\n", blank line = new paragraph. */
    public static byte[] pdf(String... pages) {
        return build(null, pages);
    }

    public static byte[] encryptedPdf(String userPassword, String... pages) {
        return build(userPassword, pages);
    }

    private static byte[] build(String userPassword, String... pages) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text.isBlank()) continue; // an image-only page has no text layer
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.setLeading(14);
                    cs.newLineAtOffset(50, 720);
                    for (String line : text.split("\n", -1)) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] docx() {
        try (XWPFDocument doc = new XWPFDocument()) {
            para(doc, "Heading1", "Safety rules");
            para(doc, null, "Always wear a helmet on site.");
            para(doc, "Heading2", "Eyes");
            XWPFParagraph item = para(doc, null, "Goggles are required near grinders.");
            item.setNumID(BigInteger.ONE);
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Zone");
            table.getRow(0).getCell(1).setText("Limit");
            table.getRow(1).getCell(0).setText("Warehouse");
            table.getRow(1).getCell(1).setText("85 dB");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static XWPFParagraph para(XWPFDocument doc, String style, String text) {
        XWPFParagraph p = doc.createParagraph();
        if (style != null) p.setStyle(style);
        p.createRun().setText(text);
        return p;
    }

    public static final String HTML = """
            <html><head><title>t</title><style>p{}</style></head><body>
            <h1>Billing</h1><p>Invoices are issued monthly.</p>
            <h2>Late fees</h2><ul><li>First notice after 7 days</li><li>Second notice after 14 days</li></ul>
            <table><tr><th>Plan</th><th>Price</th></tr><tr><td>Basic</td><td>10</td></tr></table>
            <script>alert('x')</script></body></html>
            """;

    public static final String MARKDOWN = """
            # Guide

            Intro paragraph.

            ## Install

            - first step
            - second step
              - nested step

            | Name | Value |
            |------|-------|
            | a    | 1     |
            """;
}
