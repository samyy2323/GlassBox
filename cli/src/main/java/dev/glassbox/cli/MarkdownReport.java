package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** A report snapshot (the same one the PDF and the share link are made from) as Markdown. Text from the snapshot is escaped so it cannot break a table. */
final class MarkdownReport {
    private MarkdownReport() {}

    static String render(JsonNode s) {
        StringBuilder sb = new StringBuilder("# ").append(text(s.path("title").asText("Report"))).append("\n\n");
        if (s.path("redacted").asBoolean()) sb.append("_Question texts are left out of this report._\n\n");
        section(sb, "Summary", s.path("summary"));
        JsonNode set = s.path("testset");
        if (set.isObject()) {
            sb.append("## Test set\n\n").append(text(set.path("name").asText("")) + " v" + set.path("version").asInt() + ": " + set.path("questions").asInt() + " questions ("
                    + set.path("accepted").asInt() + " accepted, " + set.path("edited").asInt() + " edited, " + set.path("rejected").asInt() + " rejected).\n\n");
        }
        for (JsonNode c : s.path("configs")) {
            sb.append("## Configuration: ").append(text(c.path("label").asText())).append("\n\n");
            for (JsonNode setting : c.path("settings")) sb.append("- ").append(text(setting.asText())).append('\n');
            sb.append('\n');
        }
        for (JsonNode m : s.path("metrics")) {
            sb.append("## Metrics: ").append(text(m.path("label").asText())).append("\n\n| Metric | Value (95% CI) | Questions |\n|---|---|---|\n");
            for (JsonNode r : m.path("rows")) {
                sb.append("| ").append(text(r.path("name").asText(r.path("metric").asText()))).append(r.path("judged").asBoolean() ? " (judged)" : "").append(" | ").append(value(r)).append(" | ")
                        .append(r.path("n").asInt()).append(" |\n");
            }
            sb.append('\n');
        }
        for (JsonNode c : s.path("comparisons")) {
            sb.append("## Against the baseline: ").append(text(c.path("label").asText())).append("\n\n");
            for (JsonNode r : c.path("rows")) sb.append("- ").append(text(r.path("name").asText())).append(": ").append(text(r.path("verdict").asText())).append('\n');
            sb.append('\n');
        }
        section(sb, "Limitations", s.path("limitations"));
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, JsonNode lines) {
        if (!lines.isArray() || lines.isEmpty()) return;
        sb.append("## ").append(title).append("\n\n");
        for (JsonNode l : lines) sb.append("- ").append(text(l.asText())).append('\n');
        sb.append('\n');
    }

    private static String value(JsonNode r) {
        if (!r.hasNonNull("value")) return "-";
        String metric = r.path("metric").asText();
        double v = r.path("value").asDouble();
        if (metric.startsWith("latency")) return String.format(Locale.ROOT, "%.0f ms", v);
        String main = String.format(Locale.ROOT, "%.1f%%", v * 100);
        return r.hasNonNull("ciLow") && r.hasNonNull("ciHigh")
                ? main + String.format(Locale.ROOT, " [%.1f%%, %.1f%%]", r.path("ciLow").asDouble() * 100, r.path("ciHigh").asDouble() * 100) : main;
    }

    private static String text(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("<", "&lt;");
    }
}
