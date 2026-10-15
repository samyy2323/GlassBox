package dev.glassbox.diagnose;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.diagnose.FailureClassifier.Stage;
import dev.glassbox.diagnose.TraceDiagnosis.Marked;
import dev.glassbox.trace.TraceModel.CandidateView;
import dev.glassbox.trace.TraceModel.Disposition;
import dev.glassbox.trace.TraceModel.Status;
import dev.glassbox.trace.TraceModel.Trace;
import dev.glassbox.trace.TraceModel.TraceData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The failure classifier on stored chat traces, with the evidence marked by a person. */
class TraceDiagnosisTest {
    private static final UUID DOC = UUID.randomUUID(), OTHER = UUID.randomUUID();

    private static CandidateView cand(UUID doc, int start, int end, Disposition d) {
        return new CandidateView(UUID.randomUUID(), doc, "Doc", null, start, end, 10, "snippet", 0.1, 1, 0.1, null, null, null, 1, d, null, null);
    }

    private static Trace trace(Status status, String answer, List<CandidateView> candidates, List<String> unknown, boolean cites) {
        var citations = cites ? List.of(new dev.glassbox.generate.CitationParser.Citation("c1", UUID.randomUUID(), UUID.randomUUID(), "Doc", null, 0, 10, "x")) : List.<dev.glassbox.generate.CitationParser.Citation>of();
        var data = new TraceData(null, candidates, null, List.of(), citations, unknown, null, null);
        return new Trace(UUID.randomUUID(), "CHAT", status, "How often is the pump inspected?", answer, "m", 10, List.of(), data, null);
    }

    private static final List<Marked> EVIDENCE = List.of(new Marked(DOC, 100, 200, 1.0));

    @Test
    void aPassageTheContextHeldMeansRetrievalWasNotTheProblem() {
        var r = TraceDiagnosis.diagnose(trace(Status.OK, "Every 14 days.", List.of(cand(DOC, 90, 210, Disposition.IN_CONTEXT)), List.of(), true), EVIDENCE);
        assertThat(r.diagnosis()).isNull();
        assertThat(r.message()).contains("retrieval was not the problem");
    }

    @Test
    void eachWayAPassageCanBeLostIsNamed() {
        assertThat(TraceDiagnosis.diagnose(trace(Status.OK, "a", List.of(cand(DOC, 90, 210, Disposition.DROPPED)), List.of(), true), EVIDENCE).diagnosis().stage())
                .as("ranked high enough but dropped while packing").isEqualTo(Stage.CONTEXT_MISS);
        assertThat(TraceDiagnosis.diagnose(trace(Status.OK, "a", List.of(cand(DOC, 90, 210, Disposition.CUT)), List.of(), true), EVIDENCE).diagnosis().stage())
                .as("retrieved, but below the top n").isEqualTo(Stage.RANKING_MISS);
        assertThat(TraceDiagnosis.diagnose(trace(Status.OK, "a", List.of(cand(OTHER, 90, 210, Disposition.IN_CONTEXT)), List.of(), true), EVIDENCE).diagnosis().stage())
                .as("another document's chunk does not count").isEqualTo(Stage.RETRIEVAL_MISS);
        assertThat(TraceDiagnosis.diagnose(trace(Status.OK, "a", List.of(cand(DOC, 160, 400, Disposition.IN_CONTEXT)), List.of(), true), EVIDENCE).diagnosis().stage())
                .as("a chunk covering under half of the passage is not a hit").isEqualTo(Stage.RETRIEVAL_MISS);
        var small = List.of(new Marked(DOC, 100, 200, 0.4));
        assertThat(TraceDiagnosis.diagnose(trace(Status.OK, "a", List.of(), List.of(), true), small).diagnosis().stage())
                .as("no chunk of the chunking holds the passage whole").isEqualTo(Stage.CHUNKING_MISS);
    }

    @Test
    void severalMarkedPassagesWhereOnlySomeArrivedArePartial() {
        var two = List.of(new Marked(DOC, 100, 200, 1.0), new Marked(DOC, 500, 600, 1.0));
        var t = trace(Status.OK, "a", List.of(cand(DOC, 90, 210, Disposition.IN_CONTEXT), cand(DOC, 480, 620, Disposition.CUT)), List.of(), true);
        var d = TraceDiagnosis.diagnose(t, two).diagnosis();
        assertThat(d.stage()).isEqualTo(Stage.PARTIAL_RETRIEVAL);
        assertThat(d.lostAt()).isEqualTo(Stage.RANKING_MISS);
        assertThat(TraceDiagnosis.retrievalProblem(d)).isTrue();
    }

    @Test
    void anAbstentionWithTheEvidenceInThePromptIsSaidToBeTheModelsRefusal() {
        var r = TraceDiagnosis.diagnose(trace(Status.ABSTAINED, "not in the documents", List.of(cand(DOC, 90, 210, Disposition.IN_CONTEXT)), List.of(), false), EVIDENCE);
        assertThat(r.diagnosis().stage()).isEqualTo(Stage.GENERATION_MISS);
        assertThat(r.diagnosis().sub()).isEqualTo(FailureClassifier.Sub.FALSE_ABSTENTION);
    }

    @Test
    void withoutMarkedEvidenceOnlyWhatTheTraceItselfShowsIsReported() {
        var t = trace(Status.OK, "Yes.", List.of(cand(DOC, 90, 210, Disposition.IN_CONTEXT)), List.of("c7"), false);
        var r = TraceDiagnosis.diagnose(t, List.of());
        assertThat(r.diagnosis()).isNull();
        assertThat(r.message()).contains("Mark the passage");
        assertThat(r.warnings()).anyMatch(w -> w.contains("c7")).anyMatch(w -> w.contains("cites no source"));
        assertThat(TraceDiagnosis.warnings(trace(Status.ABSTAINED, "", List.of(), List.of(), false))).anyMatch(w -> w.contains("not in the documents")).anyMatch(w -> w.contains("Nothing was retrieved"));
    }
}
