package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;

/** Rich Text Format, through the JDK's own RTF reader (headless-safe: it only builds a text model). */
public class RtfParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        DefaultStyledDocument doc = new DefaultStyledDocument();
        try {
            new RTFEditorKit().read(new ByteArrayInputStream(data), doc, 0);
            String text = doc.getText(0, doc.getLength());
            TextBuilder tb = new TextBuilder();
            for (String para : text.split("\\R\\s*\\R|\\R")) tb.block(Kind.PARAGRAPH, para);
            return DocumentParser.nonEmpty(tb, "The RTF file");
        } catch (BadLocationException e) {
            throw new UnsupportedDocumentException("The RTF file could not be read");
        }
    }
}
