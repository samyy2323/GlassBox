package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

public class MarkdownParser implements DocumentParser {
    private final Parser parser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final TextContentRenderer renderer = TextContentRenderer.builder().build();

    /** Emits one TABLE_ROW block per row; cell text is extracted by hand because the text renderer skips table nodes. */
    private static void tableRows(Node parent, TextBuilder tb) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof TableRow) {
                StringBuilder row = new StringBuilder();
                for (Node cell = c.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!row.isEmpty()) row.append(" | ");
                    row.append(plain(cell));
                }
                tb.block(Kind.TABLE_ROW, row.toString());
            } else {
                tableRows(c, tb);
            }
        }
    }

    private static String plain(Node n) {
        StringBuilder sb = new StringBuilder();
        n.accept(new AbstractVisitor() {
            @Override public void visit(org.commonmark.node.Text t) { sb.append(t.getLiteral()); }
            @Override public void visit(org.commonmark.node.Code c) { sb.append(c.getLiteral()); }
            @Override public void visit(org.commonmark.node.SoftLineBreak b) { sb.append(' '); }
            @Override public void visit(org.commonmark.node.HardLineBreak b) { sb.append(' '); }
        });
        return sb.toString();
    }

    @Override
    public ParsedDocument parse(byte[] data) {
        Node root = parser.parse(new String(data, StandardCharsets.UTF_8));
        TextBuilder tb = new TextBuilder();
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading h) {
                tb.heading(h.getLevel(), renderer.render(h));
            }

            @Override
            public void visit(Paragraph p) {
                // Paragraphs inside list items are emitted by the ListItem visitor.
                if (!(p.getParent() instanceof ListItem)) tb.block(Kind.PARAGRAPH, renderer.render(p));
            }

            @Override
            public void visit(ListItem item) {
                StringBuilder text = new StringBuilder();
                for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                    if (!(c instanceof BulletList) && !(c instanceof OrderedList)) text.append(' ').append(renderer.render(c));
                }
                tb.block(Kind.LIST_ITEM, text.toString());
                for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof BulletList || c instanceof OrderedList) c.accept(this); // nested items
                }
            }

            @Override
            public void visit(FencedCodeBlock b) {
                tb.block(Kind.PARAGRAPH, b.getLiteral());
            }

            @Override
            public void visit(IndentedCodeBlock b) {
                tb.block(Kind.PARAGRAPH, b.getLiteral());
            }

            @Override
            public void visit(org.commonmark.node.CustomBlock block) {
                if (block instanceof TableBlock) tableRows(block, tb); else visitChildren(block);
            }
        });
        return DocumentParser.nonEmpty(tb, "Markdown");
    }
}
