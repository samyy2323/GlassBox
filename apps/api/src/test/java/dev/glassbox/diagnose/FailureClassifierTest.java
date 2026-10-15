package dev.glassbox.diagnose;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.diagnose.FailureClassifier.Diagnosis;
import dev.glassbox.diagnose.FailureClassifier.Input;
import dev.glassbox.diagnose.FailureClassifier.Stage;
import dev.glassbox.diagnose.FailureClassifier.Sub;
import dev.glassbox.diagnose.FailureClassifier.SpanTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One case for every row of the taxonomy table (SPEC 9), plus the cases that decide which row wins. */
class FailureClassifierTest {
    // a span the search lost, at each stage (cover 1.0: a chunk could hold it)
    static final SpanTrace FOUND = SpanTrace.found();
    static final SpanTrace TOO_SMALL_CHUNKS = new SpanTrace(0.5, false, false, false);
    static final SpanTrace NOT_RETURNED = new SpanTrace(1.0, false, false, false);
    static final SpanTrace RANKED_LOW = new SpanTrace(1.0, true, false, false);
    static final SpanTrace DROPPED = new SpanTrace(1.0, true, true, false);

    static Input retrieval(String type, SpanTrace... spans) {
        return new Input(type, List.of(spans), false, false, null, null, false);
    }

    static Input answered(SpanTrace span, boolean abstained, Double correctness, Double faithfulness, boolean flaggedBad) {
        return new Input("FACTUAL", List.of(span), true, abstained, correctness, faithfulness, flaggedBad);
    }

    static Diagnosis of(Stage stage) {
        return new Diagnosis(stage, null, null);
    }

    // ------------------------------------------------------------ one row each

    @Test
    void chunkingMissWhenNoSingleChunkCoversEightyPercent() {
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", TOO_SMALL_CHUNKS))).isEqualTo(of(Stage.CHUNKING_MISS));
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", new SpanTrace(0.79, true, true, false)))).as("covering chunk found but under 80%").isEqualTo(of(Stage.CHUNKING_MISS));
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", new SpanTrace(0.80, false, false, false)))).as("exactly 80% is enough").isEqualTo(of(Stage.RETRIEVAL_MISS));
    }

    @Test
    void retrievalMissWhenACoveringChunkExistsButWasNotReturned() {
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", NOT_RETURNED))).isEqualTo(of(Stage.RETRIEVAL_MISS));
    }

    @Test
    void rankingMissWhenReturnedButBelowTheFinalTopN() {
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", RANKED_LOW))).isEqualTo(of(Stage.RANKING_MISS));
    }

    @Test
    void contextMissWhenInTheTopNButDroppedByBudgetOrDedup() {
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", DROPPED))).isEqualTo(of(Stage.CONTEXT_MISS));
    }

    @Test
    void partialRetrievalWhenAMultiSpanQuestionGotOnlySomeSpansIntoThePrompt() {
        Diagnosis d = FailureClassifier.classify(retrieval("MULTI_HOP", FOUND, RANKED_LOW));
        assertThat(d.stage()).isEqualTo(Stage.PARTIAL_RETRIEVAL);
        assertThat(d.lostAt()).as("the recommendation needs to know where the lost span was lost").isEqualTo(Stage.RANKING_MISS);
        assertThat(FailureClassifier.classify(retrieval("COMPARISON", NOT_RETURNED, FOUND)).lostAt()).isEqualTo(Stage.RETRIEVAL_MISS);
    }

    @Test
    void generationMissAndItsFourSubLabels() {
        assertThat(FailureClassifier.classify(answered(FOUND, true, 0.0, null, false))).isEqualTo(new Diagnosis(Stage.GENERATION_MISS, Sub.FALSE_ABSTENTION, null));
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.0, 0.5, false))).isEqualTo(new Diagnosis(Stage.GENERATION_MISS, Sub.UNFAITHFUL, null));
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.5, 1.0, false))).isEqualTo(new Diagnosis(Stage.GENERATION_MISS, Sub.INCOMPLETE, null));
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.0, 1.0, false))).isEqualTo(new Diagnosis(Stage.GENERATION_MISS, Sub.WRONG_REASONING, null));
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.0, null, false))).as("no claims to check: wrong reasoning").isEqualTo(new Diagnosis(Stage.GENERATION_MISS, Sub.WRONG_REASONING, null));
    }

    @Test
    void hallucinatedAnswerWhenAnUnanswerableQuestionIsAnswered() {
        Input answeredIt = new Input("UNANSWERABLE", List.of(), true, false, null, null, false);
        assertThat(FailureClassifier.classify(answeredIt)).isEqualTo(of(Stage.HALLUCINATED_ANSWER));
    }

    @Test
    void badTestQuestionWhenTheEvidenceWasThereTheAnswerIsWrongAndTheQuestionIsFlagged() {
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.0, 1.0, true))).isEqualTo(of(Stage.BAD_TEST_QUESTION));
        assertThat(FailureClassifier.classify(answered(FOUND, false, 0.5, 0.4, true))).isEqualTo(of(Stage.BAD_TEST_QUESTION));
    }

    // ------------------------------------------------------------ what is not a failure

    @Test
    void aQuestionThatPassedHasNoLabel() {
        assertThat(FailureClassifier.classify(retrieval("FACTUAL", FOUND))).as("retrieval-only, evidence in the prompt").isNull();
        assertThat(FailureClassifier.classify(answered(FOUND, false, 1.0, 1.0, false))).isNull();
        assertThat(FailureClassifier.classify(answered(FOUND, false, 1.0, 0.5, false))).as("correct answer; faithfulness alone is not a failure").isNull();
        assertThat(FailureClassifier.classify(answered(FOUND, false, null, null, false))).as("not judged").isNull();
        assertThat(FailureClassifier.classify(retrieval("MULTI_HOP", FOUND, FOUND))).isNull();
    }

    @Test
    void anUnanswerableQuestionThatWasRefusedPassedAndOneInARetrievalRunCannotFail() {
        assertThat(FailureClassifier.classify(new Input("UNANSWERABLE", List.of(), true, true, null, null, false))).isNull();
        assertThat(FailureClassifier.classify(new Input("UNANSWERABLE", List.of(), false, false, null, null, false))).isNull();
    }

    // ------------------------------------------------------------ which row wins

    @Test
    void theFirstFailingStageWinsWhenSeveralSpansWereLostAtDifferentStages() {
        assertThat(FailureClassifier.classify(retrieval("MULTI_HOP", DROPPED, NOT_RETURNED))).isEqualTo(of(Stage.RETRIEVAL_MISS));
        assertThat(FailureClassifier.classify(retrieval("MULTI_HOP", RANKED_LOW, TOO_SMALL_CHUNKS))).isEqualTo(of(Stage.CHUNKING_MISS));
    }

    @Test
    void aChunkingThatCannotHoldAMissingSpanOutranksPartialRetrieval() {
        assertThat(FailureClassifier.classify(retrieval("MULTI_HOP", FOUND, TOO_SMALL_CHUNKS))).isEqualTo(of(Stage.CHUNKING_MISS));
    }

    @Test
    void aRetrievalFailureIsNeverBlamedOnTheQuestionOrTheModel() {
        Input flaggedButLost = new Input("FACTUAL", List.of(NOT_RETURNED), true, true, 0.0, null, true);
        assertThat(FailureClassifier.classify(flaggedButLost)).isEqualTo(of(Stage.RETRIEVAL_MISS));
    }

    @Test
    void refusingWithTheEvidenceInThePromptIsAFalseAbstentionEvenForAFlaggedQuestion() {
        assertThat(FailureClassifier.classify(answered(FOUND, true, 0.0, null, true)).sub()).isEqualTo(Sub.FALSE_ABSTENTION);
    }
}
