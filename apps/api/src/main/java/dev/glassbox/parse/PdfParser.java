package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** One page at a time so page numbers map to offsets; paragraphs are blank-line separated runs. */
public class PdfParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            TextBuilder tb = new TextBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                tb.startPage();
                for (String para : stripper.getText(doc).split("\\R\\s*\\R")) {
                    tb.block(Kind.PARAGRAPH, para);
                }
            }
            if (tb.isEmpty()) {
                throw new UnsupportedDocumentException(
                        "PDF has no extractable text (scanned PDFs need OCR, which is not supported)");
            }
            return tb.build();
        } catch (InvalidPasswordException e) {
            throw new UnsupportedDocumentException("Password-protected PDFs are not supported");
        }
    }
}
