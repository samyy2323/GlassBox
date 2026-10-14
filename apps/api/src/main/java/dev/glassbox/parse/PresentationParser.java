package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Notes;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;

/** PowerPoint (.pptx and legacy .ppt) through POI's common slide model: a heading per slide, its text, tables and speaker notes. */
public class PresentationParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        TextBuilder tb = new TextBuilder();
        try (SlideShow<?, ?> show = SlideShowFactory.create(new ByteArrayInputStream(data))) {
            int n = 0;
            for (Slide<?, ?> slide : show.getSlides()) {
                n++;
                String title = slide.getTitle();
                tb.heading(1, "Slide " + n + (title == null || title.isBlank() ? "" : ": " + title));
                shapes(tb, slide.getShapes(), title);
                Notes<?, ?> notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder sb = new StringBuilder();
                    collect(sb, notes.getShapes());
                    if (!sb.isEmpty()) tb.block(Kind.PARAGRAPH, "Speaker notes: " + sb);
                }
            }
        } catch (EncryptedDocumentException e) {
            throw new UnsupportedDocumentException("Password-protected presentations are not supported");
        }
        return DocumentParser.nonEmpty(tb, "The presentation");
    }

    private static void shapes(TextBuilder tb, Iterable<? extends Shape<?, ?>> shapes, String skipText) {
        for (Shape<?, ?> s : shapes) {
            if (s instanceof GroupShape<?, ?> g) {
                shapes(tb, g.getShapes(), skipText);
            } else if (s instanceof TableShape<?, ?> t) {
                for (int r = 0; r < t.getNumberOfRows(); r++) {
                    StringBuilder row = new StringBuilder();
                    for (int c = 0; c < t.getNumberOfColumns(); c++) {
                        var cell = t.getCell(r, c);
                        if (cell == null) continue;
                        if (!row.isEmpty()) row.append(" | ");
                        row.append(cell.getText());
                    }
                    tb.block(Kind.TABLE_ROW, row.toString());
                }
            } else if (s instanceof TextShape<?, ?> ts) {
                String text = ts.getText();
                if (text != null && !text.strip().equals(skipText == null ? null : skipText.strip())) {
                    for (String line : text.split("\\R")) tb.block(Kind.PARAGRAPH, line);
                }
            }
        }
    }

    private static void collect(StringBuilder sb, Iterable<? extends Shape<?, ?>> shapes) {
        for (Shape<?, ?> s : shapes) {
            if (s instanceof TextShape<?, ?> ts && ts.getText() != null && !ts.getText().isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(ts.getText().strip());
            }
        }
    }
}
