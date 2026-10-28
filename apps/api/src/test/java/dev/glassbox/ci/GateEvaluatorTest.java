package dev.glassbox.ci;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.ci.GateEvaluator.Check;
import dev.glassbox.ci.GateEvaluator.Spec;
import dev.glassbox.ci.GateEvaluator.Stat;
import dev.glassbox.eval.Comparisons;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** The decision table of the gate (SPEC 14): a drop blocks a merge only when it is both large enough and statistically significant. */
class GateEvaluatorTest {
    private static Comparisons.Row row(String metric, double baseline, double candidate, double pAdjusted) {
        return new Comparisons.Row(metric, baseline, candidate, candidate - baseline, -0.1, 0.1, pAdjusted, pAdjusted, "mcnemar", null, "", 0.05, 40);
    }

    private static Spec regression(double allowedDrop, double alpha) {
        return new Spec("RETRIEVAL", java.util.UUID.randomUUID(), null, Map.of("recall@5", allowedDrop), false, alpha);
    }

    private static Check only(List<Check> checks) {
        assertThat(checks).hasSize(1);
        return checks.get(0);
    }

    @Test
    void aDropThatIsBothLargeAndSignificantBlocks() {
        Check c = only(GateEvaluator.evaluate(regression(0.02, 0.05), Map.of(), Map.of("recall@5", row("recall@5", 0.90, 0.84, 0.01))));
        assertThat(c.passed()).isFalse();
        assertThat(c.reason()).contains("Dropped 6.0 points").contains("allowed 2.0 points").contains("significant").contains("blocked");
        assertThat(c.baseline()).isEqualTo(0.90);
        assertThat(c.delta()).isCloseTo(-0.06, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void aLargeDropThatCannotBeToldFromNoiseDoesNotBlock() {
        Check c = only(GateEvaluator.evaluate(regression(0.02, 0.05), Map.of(), Map.of("recall@5", row("recall@5", 0.90, 0.84, 0.30))));
        assertThat(c.passed()).isTrue();
        assertThat(c.reason()).contains("cannot be told from noise").contains("not blocked");
    }

    @Test
    void aSignificantDropWithinTheAllowanceDoesNotBlock() {
        Check c = only(GateEvaluator.evaluate(regression(0.05, 0.05), Map.of(), Map.of("recall@5", row("recall@5", 0.90, 0.87, 0.001))));
        assertThat(c.passed()).isTrue();
        assertThat(c.reason()).contains("within the 5.0 points allowed");
    }

    @Test
    void theAllowanceIsInclusiveAndAnImprovementIsReportedAsOne() {
        assertThat(only(GateEvaluator.evaluate(regression(0.05, 0.05), Map.of(), Map.of("recall@5", row("recall@5", 0.90, 0.85, 0.0001)))).passed()).as("exactly at the limit").isTrue();
        Check better = only(GateEvaluator.evaluate(regression(0.0, 0.05), Map.of(), Map.of("recall@5", row("recall@5", 0.80, 0.90, 0.0001))));
        assertThat(better.passed()).isTrue();
        assertThat(better.reason()).startsWith("No drop (+10.0 points)");
    }

    @Test
    void theSignificanceLevelIsTheCallers() {
        Map<String, Comparisons.Row> rows = Map.of("recall@5", row("recall@5", 0.90, 0.80, 0.03));
        assertThat(only(GateEvaluator.evaluate(regression(0.02, 0.05), Map.of(), rows)).passed()).as("p=0.03 is significant at 0.05").isFalse();
        assertThat(only(GateEvaluator.evaluate(regression(0.02, 0.01), Map.of(), rows)).passed()).as("but not at 0.01").isTrue();
    }

    @Test
    void aMissingComparisonIsAFailureNotASilentPass() {
        assertThat(only(GateEvaluator.evaluate(regression(0.02, 0.05), Map.of(), Map.of())).passed()).isFalse();
        assertThat(only(GateEvaluator.evaluate(regression(0.02, 0.05), Map.of(), null)).reason()).contains("no recall@5 in common");
    }

    @Test
    void aMinimumComparesTheValueOrWithStrictTheLowerEndOfTheInterval() {
        Map<String, Stat> measured = Map.of("recall@5", new Stat(0.88, 0.80, 0.95, 40));
        Spec lax = new Spec("RETRIEVAL", null, Map.of("recall@5", 0.85), null, false, 0.05);
        Spec strict = new Spec("RETRIEVAL", null, Map.of("recall@5", 0.85), null, true, 0.05);
        assertThat(only(GateEvaluator.evaluate(lax, measured, null)).passed()).isTrue();
        Check s = only(GateEvaluator.evaluate(strict, measured, null));
        assertThat(s.passed()).isFalse();
        assertThat(s.reason()).contains("lower end of the interval is 80.0%").contains("below 85.0%");
        assertThat(only(GateEvaluator.evaluate(lax, Map.of("recall@5", new Stat(0.85, 0.7, 0.9, 40)), null)).passed()).as("exactly the minimum").isTrue();
        assertThat(only(GateEvaluator.evaluate(lax, Map.of(), null)).reason()).contains("did not measure recall@5");
    }

    @Test
    void theGatePassesOnlyWhenEveryCheckDoesAndAnEmptyGatePassesNothing() {
        Spec both = new Spec("RETRIEVAL", java.util.UUID.randomUUID(), Map.of("mrr", 0.5), Map.of("recall@5", 0.02), false, 0.05);
        Map<String, Stat> measured = Map.of("mrr", new Stat(0.40, 0.3, 0.5, 40));
        var checks = GateEvaluator.evaluate(both, measured, Map.of("recall@5", row("recall@5", 0.9, 0.9, 1.0)));
        assertThat(checks).extracting(Check::kind).containsExactlyInAnyOrder("minimum", "regression");
        assertThat(GateEvaluator.passed(checks)).isFalse();
        assertThat(GateEvaluator.passed(List.of())).isFalse();
    }

    @Test
    void theMarkdownTableAndTheJunitFileSayTheSameThingAndCannotBeBrokenByTheirContent() throws Exception {
        Check hostile = new Check("minimum", "mrr", false, "mrr | <b>x</b> [click](http://evil.example)", "line one\nline \"two\" <script>", 0.4, 0.3, 0.5, 0.5, null, null, null, 40);
        Check fine = new Check("regression", "recall@5", true, "recall@5 holds", "No drop (+0.0 points).", 0.9, null, null, 0.02, 0.9, 0.0, 1.0, 40);
        String md = GateReport.markdown("PR 12 on set v3", "FAILED", List.of(fine, hostile), null);
        assertThat(md).startsWith("### Glassbox quality gate: **failed**").contains("| **FAIL** |").contains("| pass |");
        assertThat(md.lines().filter(l -> l.startsWith("| ")).count()).as("header, two rows").isEqualTo(3);
        assertThat(md).doesNotContain("<b>").doesNotContain("[click](").doesNotContain("<script>");
        assertThat(md.lines().filter(l -> l.startsWith("| **FAIL**")).findFirst().orElseThrow().split("(?<!\\\\)\\|").length).as("a pipe in a title cannot add a column").isEqualTo(6);

        String xml = GateReport.junit("glassbox: PR 12", "FAILED", List.of(fine, hostile), null);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var suite = doc.getDocumentElement();
        assertThat(suite.getAttribute("tests")).isEqualTo("2");
        assertThat(suite.getAttribute("failures")).isEqualTo("1");
        assertThat(doc.getElementsByTagName("failure").getLength()).isEqualTo(1);

        String error = GateReport.junit("glassbox: PR 12", "ERROR", List.of(), "The run was cancelled");
        var errorDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(error.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(errorDoc.getDocumentElement().getAttribute("errors")).isEqualTo("1");
        assertThat(GateReport.markdown("x", "ERROR", List.of(), "The run was cancelled")).contains("could not be run").contains("The run was cancelled");
    }
}
