package dev.glassbox.parse;

import java.io.IOException;

public interface DocumentParser {
    ParsedDocument parse(byte[] data) throws IOException;

    static DocumentParser forType(String sourceType) {
        return switch (sourceType) {
            case "PDF" -> new PdfParser();
            case "DOCX" -> new DocxParser();
            case "HTML" -> new HtmlParser();
            case "MD" -> new MarkdownParser();
            default -> throw new UnsupportedDocumentException("Unsupported type " + sourceType);
        };
    }

    /** Result guard shared by every parser. */
    static ParsedDocument nonEmpty(TextBuilder tb, String what) {
        if (tb.isEmpty()) throw new UnsupportedDocumentException(what + " contains no extractable text");
        return tb.build();
    }
}
