package dev.glassbox.ci;

import dev.glassbox.ci.GateEvaluator.Check;
import java.util.List;
import java.util.Locale;

/** The gate's verdict as text for people and for CI systems: a Markdown table for a pull request comment, and JUnit XML for the test report of the CI run. */
public final class GateReport {
    private GateReport() {}

    public static String markdown(String title, String state, List<Check> checks, String note) {
        StringBuilder sb = new StringBuilder("### Glassbox quality gate: ").append(headline(state)).append("\n\n");
        if (title != null && !title.isBlank()) sb.append(escapeMd(title)).append("\n\n");
        if (note != null && !note.isBlank()) sb.append(escapeMd(note)).append("\n\n");
        if (!checks.isEmpty()) {
            sb.append("| | Check | Value (95% CI) | Against | Why |\n|---|---|---|---|---|\n");
            for (Check c : checks) {
                sb.append("| ").append(c.passed() ? "pass" : "**FAIL**").append(" | ").append(escapeMd(c.title())).append(" | ").append(value(c)).append(" | ").append(against(c))
                        .append(" | ").append(escapeMd(c.reason())).append(" |\n");
            }
        }
        return sb.toString();
    }

    /** One test case per check; a failed check is a {@code failure}, a run that could not be judged is a single {@code error}. */
    public static String junit(String suite, String state, List<Check> checks, String error) {
        int failures = (int) checks.stream().filter(c -> !c.passed()).count();
        boolean errored = "ERROR".equals(state);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(xml(suite)).append("\" tests=\"").append(errored ? 1 : checks.size()).append("\" failures=\"").append(errored ? 0 : failures)
                .append("\" errors=\"").append(errored ? 1 : 0).append("\">\n");
        if (errored) {
            sb.append("  <testcase classname=\"glassbox.gate\" name=\"run\"><error message=\"").append(xml(error == null ? "The run could not be completed." : error)).append("\"/></testcase>\n");
        } else {
            for (Check c : checks) {
                sb.append("  <testcase classname=\"glassbox.gate.").append(xml(c.kind())).append("\" name=\"").append(xml(c.title())).append("\"");
                if (c.passed()) sb.append("/>\n");
                else sb.append(">\n    <failure message=\"").append(xml(c.reason())).append("\"/>\n  </testcase>\n");
            }
        }
        return sb.append("</testsuite>\n").toString();
    }

    private static String headline(String state) {
        return switch (state) {
            case "PASSED" -> "passed";
            case "FAILED" -> "**failed**";
            case "ERROR" -> "**could not be run**";
            default -> "still running";
        };
    }

    private static String value(Check c) {
        if (c.value() == null) return "-";
        String v = pct(c.value());
        return c.ciLow() != null && c.ciHigh() != null ? v + " [" + pct(c.ciLow()) + ", " + pct(c.ciHigh()) + "]" : v;
    }

    private static String against(Check c) {
        if (c.kind().equals("minimum")) return c.threshold() == null ? "-" : "at least " + pct(c.threshold());
        String base = c.baseline() == null ? "-" : "baseline " + pct(c.baseline());
        return c.p() == null ? base : base + ", p=" + GateEvaluator.p(c.p());
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    /** Text from a model or a user must not be able to break the table or inject a link into a pull request comment. */
    static String escapeMd(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("<", "&lt;").replace("[", "\\[").replace("]", "\\]");
    }

    static String xml(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\r", " ").replace("\n", " ");
    }
}
