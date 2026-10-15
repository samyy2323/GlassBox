package dev.glassbox.reports;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Draws a report snapshot as a plain PDF (SPEC 11): text, tables of numbers, a bar chart of failures by stage and a quality-against-cost plot. It uses only
 * PDFBox's built-in fonts, so a report needs no font files and renders the same everywhere; characters outside Latin-1 are replaced rather than failing.
 */
@Component
public class ReportPdf {
    private static final float MARGIN = 48, WIDTH = PDRectangle.A4.getWidth(), HEIGHT = PDRectangle.A4.getHeight();
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA), BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final Color ACCENT = new Color(70, 80, 200), MUTED = new Color(110, 110, 120), BAR = new Color(90, 100, 220);

    public byte[] render(JsonNode s) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.text(s.path("title").asText("Report"), BOLD, 20, Color.BLACK, 8);
            w.text("Generated " + s.path("generatedAt").asText("").replace('T', ' ').replaceAll("\\..*", "") + (s.path("redacted").asBoolean() ? " - question texts redacted" : ""), REGULAR, 9, MUTED, 10);
            section(w, "Summary");
            for (JsonNode line : s.path("summary")) w.bullet(line.asText());

            JsonNode ts = s.path("testset");
            if (!ts.isMissingNode()) {
                section(w, "Test set");
                w.text(ts.path("name").asText() + " v" + ts.path("version").asInt() + ": " + ts.path("questions").asInt() + " questions (" + ts.path("accepted").asInt() + " accepted, " + ts.path("edited").asInt()
                        + " edited, " + ts.path("rejected").asInt() + " rejected" + (ts.has("acceptanceRate") ? ", " + pct(ts.path("acceptanceRate").asDouble()) + " kept" : "") + "; " + ts.path("fromFeedback").asInt() + " from feedback).", REGULAR, 10, Color.BLACK, 4);
                StringBuilder types = new StringBuilder();
                ts.path("byType").fields().forEachRemaining(e -> types.append(types.length() == 0 ? "" : ", ").append(e.getKey().toLowerCase().replace('_', '-')).append(" ").append(e.getValue().asInt()));
                if (types.length() > 0) w.text("By type: " + types + ".", REGULAR, 10, Color.BLACK, 4);
            }

            JsonNode headline = s.path("headline");
            if (!headline.isMissingNode()) {
                section(w, "Result");
                w.text(headline.path("winnerLabel").asText(), BOLD, 11, Color.BLACK, 3);
                w.text(headline.path("verdict").asText(), REGULAR, 10, Color.BLACK, 3);
                JsonNode ho = headline.path("heldOut"), bho = headline.path("baselineHeldOut");
                w.text("Held-out: " + pct(ho.path("quality").asDouble()) + " [" + pct(ho.path("ciLow").asDouble()) + ", " + pct(ho.path("ciHigh").asDouble()) + "] on " + ho.path("n").asInt() + " questions; baseline "
                        + pct(bho.path("quality").asDouble()) + " [" + pct(bho.path("ciLow").asDouble()) + ", " + pct(bho.path("ciHigh").asDouble()) + "].", REGULAR, 10, Color.BLACK, 4);
            }

            if (s.path("configs").size() > 0) {
                section(w, "Configurations");
                for (JsonNode c : s.path("configs")) {
                    List<String> parts = new ArrayList<>();
                    c.path("settings").forEach(x -> parts.add(x.asText()));
                    w.text(c.path("label").asText(), BOLD, 10, Color.BLACK, 1);
                    w.text(String.join(", ", parts), REGULAR, 9, MUTED, 5);
                }
            }
            if (s.path("candidates").size() > 0) {
                section(w, "Finalists and the baseline (held-out)");
                for (JsonNode c : s.path("candidates")) {
                    JsonNode h = c.path("heldOut");
                    w.text(c.path("label").asText() + "  [" + c.path("role").asText().toLowerCase() + "]", BOLD, 10, Color.BLACK, 1);
                    w.text("quality " + pct(h.path("quality").asDouble()) + " [" + pct(h.path("ciLow").asDouble()) + ", " + pct(h.path("ciHigh").asDouble()) + "], " + Math.round(h.path("promptTokens").asDouble()) + " prompt tokens, "
                            + Math.round(h.path("latencyMs").asDouble()) + " ms" + (c.has("vsBaseline") ? "; " + c.path("vsBaseline").path("verdict").asText() : ""), REGULAR, 9, Color.BLACK, 5);
                }
            }

            if (s.path("metrics").size() > 0) {
                section(w, "Metrics (95% bootstrap intervals)");
                for (JsonNode run : s.path("metrics")) {
                    w.text(run.path("label").asText(), BOLD, 10, Color.BLACK, 2);
                    for (JsonNode m : run.path("rows")) {
                        String v = m.path("metric").asText().startsWith("latency") ? Math.round(m.path("value").asDouble()) + " ms" : pct(m.path("value").asDouble())
                                + (m.has("ciLow") ? " [" + pct(m.path("ciLow").asDouble()) + ", " + pct(m.path("ciHigh").asDouble()) + "]" : "");
                        w.row(m.path("name").asText() + (m.path("judged").asBoolean() ? " (judged)" : ""), v + "  n=" + m.path("n").asInt());
                    }
                    w.gap(6);
                }
            }
            if (s.path("comparisons").size() > 0) {
                section(w, "Is the difference real?");
                for (JsonNode c : s.path("comparisons")) {
                    w.text(c.path("label").asText(), BOLD, 10, Color.BLACK, 2);
                    for (JsonNode r : c.path("rows")) w.bullet(r.path("name").asText() + ": " + r.path("verdict").asText());
                }
            }

            JsonNode failures = s.path("failures");
            if (failures.size() > 0) {
                section(w, "Where the misses were lost");
                Iterator<Map.Entry<String, JsonNode>> runs = failures.fields();
                while (runs.hasNext()) {
                    var e = runs.next();
                    w.text(e.getKey(), BOLD, 10, Color.BLACK, 2);
                    if (e.getValue().size() == 0) w.text("No failures.", REGULAR, 9, MUTED, 4);
                    int max = 1;
                    for (JsonNode n : e.getValue()) max = Math.max(max, n.asInt());
                    for (Iterator<Map.Entry<String, JsonNode>> st = e.getValue().fields(); st.hasNext(); ) {
                        var x = st.next();
                        w.bar(x.getKey().toLowerCase().replace('_', ' '), x.getValue().asInt(), max);
                    }
                    w.gap(6);
                }
            }
            if (s.path("failedQuestions").size() > 0) {
                section(w, "Failed questions");
                for (JsonNode q : s.path("failedQuestions")) w.bullet(q.path("question").asText() + " (" + q.path("stage").asText().toLowerCase().replace('_', ' ') + ", " + q.path("run").asText() + ")");
            }

            JsonNode points = s.path("pareto").path("points");
            if (points.size() > 0) {
                section(w, "Quality against cost");
                w.plot(points);
            }
            section(w, "Limitations");
            for (JsonNode l : s.path("limitations")) w.bullet(l.asText());
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not draw the report", e);
        }
    }

    private static void section(Writer w, String title) throws IOException {
        w.gap(10);
        w.text(title, BOLD, 13, ACCENT, 5);
    }

    static String pct(double v) {
        return (Math.round(v * 1000) / 10.0) + "%";
    }

    /** Replaces what the built-in fonts cannot draw. */
    static String clean(String text) {
        String t = text.replace('–', '-').replace('—', '-').replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace("…", "...").replace("→", "->").replace(' ', ' ').replace("≥", ">=").replace("≤", "<=");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c < 32 || (c > 126 && c < 160) || c > 255) sb.append('?');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** A cursor down A4 pages: wraps text, starts a new page when it runs out, numbers the pages. */
    private static final class Writer {
        final PDDocument doc;
        PDPageContentStream cs;
        float y;
        int pages;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) footer();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            if (cs != null) cs.close();
            cs = new PDPageContentStream(doc, page);
            pages++;
            y = HEIGHT - MARGIN;
        }

        void footer() throws IOException {
            cs.beginText();
            cs.setFont(REGULAR, 8);
            cs.setNonStrokingColor(MUTED);
            cs.newLineAtOffset(WIDTH - MARGIN - 40, 28);
            cs.showText("Page " + pages);
            cs.endText();
        }

        void close() throws IOException {
            footer();
            cs.close();
        }

        void ensure(float need) throws IOException {
            if (y - need < MARGIN) newPage();
        }

        void gap(float h) {
            y -= h;
        }

        List<String> wrap(String text, PDType1Font font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : clean(text).split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (font.getStringWidth(next) / 1000 * size > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }

        void text(String text, PDType1Font font, float size, Color color, float after) throws IOException {
            writeLines(wrap(text, font, size, WIDTH - 2 * MARGIN), font, size, color, MARGIN, after);
        }

        void bullet(String text) throws IOException {
            List<String> lines = wrap(text, REGULAR, 10, WIDTH - 2 * MARGIN - 12);
            ensure(14);
            cs.beginText();
            cs.setFont(REGULAR, 10);
            cs.setNonStrokingColor(ACCENT);
            cs.newLineAtOffset(MARGIN, y - 10);
            cs.showText("-");
            cs.endText();
            writeLines(lines, REGULAR, 10, Color.BLACK, MARGIN + 12, 3);
        }

        void row(String label, String value) throws IOException {
            ensure(13);
            cs.beginText();
            cs.setFont(REGULAR, 9);
            cs.setNonStrokingColor(Color.BLACK);
            cs.newLineAtOffset(MARGIN + 8, y - 9);
            cs.showText(clean(label));
            cs.endText();
            cs.beginText();
            cs.setFont(REGULAR, 9);
            cs.newLineAtOffset(MARGIN + 190, y - 9);
            cs.showText(clean(value));
            cs.endText();
            y -= 12;
        }

        void bar(String label, int count, int max) throws IOException {
            ensure(14);
            cs.beginText();
            cs.setFont(REGULAR, 9);
            cs.setNonStrokingColor(Color.BLACK);
            cs.newLineAtOffset(MARGIN + 8, y - 9);
            cs.showText(clean(label));
            cs.endText();
            float len = 220f * count / max;
            cs.setNonStrokingColor(BAR);
            cs.addRect(MARGIN + 150, y - 10, Math.max(2, len), 9);
            cs.fill();
            cs.beginText();
            cs.setFont(REGULAR, 9);
            cs.setNonStrokingColor(Color.BLACK);
            cs.newLineAtOffset(MARGIN + 150 + Math.max(2, len) + 6, y - 9);
            cs.showText(String.valueOf(count));
            cs.endText();
            y -= 13;
        }

        /** Held-out quality (up) against prompt tokens (right); squares are configurations, filled ones are on the Pareto frontier. */
        void plot(JsonNode points) throws IOException {
            float w = WIDTH - 2 * MARGIN - 60, h = 170;
            ensure(h + 40);
            double minC = Double.MAX_VALUE, maxC = 0, minQ = 1, maxQ = 0;
            for (JsonNode p : points) {
                minC = Math.min(minC, p.path("cost").asDouble());
                maxC = Math.max(maxC, p.path("cost").asDouble());
                minQ = Math.min(minQ, p.path("quality").asDouble());
                maxQ = Math.max(maxQ, p.path("quality").asDouble());
            }
            if (maxC - minC < 1) maxC = minC + 1;
            minQ = Math.max(0, minQ - 0.05);
            maxQ = Math.min(1, maxQ + 0.05);
            if (maxQ - minQ < 0.05) maxQ = minQ + 0.05;
            float x0 = MARGIN + 40, y0 = y - h - 10;
            cs.setStrokingColor(MUTED);
            cs.addRect(x0, y0, w, h);
            cs.stroke();
            for (JsonNode p : points) {
                float px = x0 + (float) ((p.path("cost").asDouble() - minC) / (maxC - minC)) * (w - 10) + 5;
                float py = y0 + (float) ((p.path("quality").asDouble() - minQ) / (maxQ - minQ)) * (h - 10) + 5;
                boolean frontier = p.path("frontier").asBoolean();
                Color color = p.path("role").asText().equals("BASELINE") ? MUTED : ACCENT;
                cs.addRect(px - 3, py - 3, 6, 6);
                if (frontier) {
                    cs.setNonStrokingColor(color);
                    cs.fill();
                } else {
                    cs.setStrokingColor(color);
                    cs.stroke();
                }
            }
            cs.beginText();
            cs.setFont(REGULAR, 8);
            cs.setNonStrokingColor(MUTED);
            cs.newLineAtOffset(x0, y0 - 11);
            cs.showText("prompt tokens per question: " + Math.round(minC) + " to " + Math.round(maxC) + " (cheaper is left)    quality: " + pct(minQ) + " to " + pct(maxQ));
            cs.endText();
            y = y0 - 20;
        }

        private void writeLines(List<String> lines, PDType1Font font, float size, Color color, float x, float after) throws IOException {
            for (String line : lines) {
                ensure(size + 4);
                cs.beginText();
                cs.setFont(font, size);
                cs.setNonStrokingColor(color);
                cs.newLineAtOffset(x, y - size);
                cs.showText(line);
                cs.endText();
                y -= size + 3;
            }
            y -= after;
        }
    }
}
