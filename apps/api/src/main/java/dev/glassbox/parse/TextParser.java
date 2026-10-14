package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;

/** Plain text, logs, notes, source code and config files. Paragraphs are blank-line separated runs. */
public class TextParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) {
        TextBuilder tb = new TextBuilder();
        for (String para : TextDecoding.decode(data).split("\\R\\s*\\R")) tb.block(Kind.PARAGRAPH, para);
        return DocumentParser.nonEmpty(tb, "The file");
    }
}
