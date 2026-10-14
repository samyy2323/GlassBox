package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

/** Legacy binary Word (.doc). Text only: this format carries no reliable heading styles through POI's extractor. */
public class LegacyDocParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data)); WordExtractor ex = new WordExtractor(doc)) {
            TextBuilder tb = new TextBuilder();
            for (String p : ex.getParagraphText()) tb.block(Kind.PARAGRAPH, p);
            return DocumentParser.nonEmpty(tb, "The Word file");
        } catch (EncryptedDocumentException e) {
            throw new UnsupportedDocumentException("Password-protected documents are not supported");
        }
    }
}
