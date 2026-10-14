package dev.glassbox.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.dataset.QuestionSampler.Chunk;
import dev.glassbox.dataset.QuestionSampler.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Pure logic of test set generation: evidence selection, quality flags, sampling, reply parsing. */
class DatasetUnitTest {
    static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ EvidenceLocator

    static final String DOC = "Intro line about the plant. Pumps must be inspected every 90 days by a certified engineer. "
            + "Filters are replaced once a year. The cafeteria opens at 8 am.\n\nA second paragraph about safety rules and helmets.";

    @Test
    void evidenceIsTheSentenceThatCoversTheAnswer() {
        var r = EvidenceLocator.locate(DOC, 0, DOC.length(), "Every 90 days by a certified engineer", "How often are pumps inspected?").orElseThrow();
        assertThat(DOC.substring(r.start(), r.end())).isEqualTo("Pumps must be inspected every 90 days by a certified engineer.");
    }

    @Test
    void evidenceStaysInsideTheSampledChunk() {
        int from = DOC.indexOf("Filters");
        int to = DOC.indexOf("The cafeteria");
        var r = EvidenceLocator.locate(DOC, from, to, "Every 90 days", "How often are pumps inspected?").orElseThrow();
        assertThat(r.start()).isGreaterThanOrEqualTo(from);
        assertThat(r.end()).isLessThanOrEqualTo(to);
    }

    @Test
    void evidenceGrowsToNeighbouringSentencesOnlyWhenTheyAddAnswerWords() {
        String text = "Warranty covers parts for two years. Labour is covered for ninety days after installation. Shipping is free.";
        var wide = EvidenceLocator.locate(text, 0, text.length(), "parts for two years and labour for ninety days", "What does the warranty cover?").orElseThrow();
        assertThat(text.substring(wide.start(), wide.end())).isEqualTo("Warranty covers parts for two years. Labour is covered for ninety days after installation.");
        var tight = EvidenceLocator.locate(text, 0, text.length(), "two years", "How long are parts covered?").orElseThrow();
        assertThat(text.substring(tight.start(), tight.end())).isEqualTo("Warranty covers parts for two years.");
    }

    @Test
    void evidenceRangesAreTrimmedAndEmptyChunksHaveNone() {
        var r = EvidenceLocator.locate("   Hello world here.   ", 0, 23, "hello world", "q").orElseThrow();
        assertThat(r.start()).isEqualTo(3);
        assertThat(r.end()).isEqualTo(20);
        assertThat(EvidenceLocator.locate("   ", 0, 3, "a", "b")).isEmpty();
    }

    @Test
    void numbersInTheAnswerAreMatchedLiterally() {
        String text = "The tank holds 500 litres. The pump moves 40 litres a minute.";
        var r = EvidenceLocator.locate(text, 0, text.length(), "40", "What is the flow rate?").orElseThrow();
        assertThat(text.substring(r.start(), r.end())).isEqualTo("The pump moves 40 litres a minute.");
    }

    @Test
    void coverageSaysHowMuchOfTheAnswerARangeHolds() {
        String text = "Pumps must be inspected every 90 days by a certified engineer. Signing in";
        var relevant = EvidenceLocator.locateWithCoverage(text, 0, text.length(), "Every 90 days by a certified engineer", "How often?").orElseThrow();
        assertThat(relevant.coverage()).isEqualTo(1.0);
        var unrelated = EvidenceLocator.locateWithCoverage("Signing in", 0, 10, "Every 90 days by a certified engineer", "How often?").orElseThrow();
        assertThat(unrelated.coverage()).as("a heading that holds none of the answer").isZero();
        assertThat(EvidenceLocator.locateWithCoverage("   ", 0, 3, "a", "b")).isEmpty();
    }

    // ------------------------------------------------------------------ QualityFilters

    @Test
    void copyRiskFlagsQuestionsThatRepeatTheEvidence() {
        String evidence = "Pumps must be inspected every 90 days by a certified engineer.";
        assertThat(QualityFilters.copyRisk("Pumps must be inspected every 90 days by a certified engineer?", List.of(evidence))).isTrue();
        assertThat(QualityFilters.copyRisk("How often are pumps inspected?", List.of(evidence))).isFalse();
        assertThat(QualityFilters.lexicalOverlap("How often are pumps inspected?", evidence)).isLessThan(QualityFilters.COPY_THRESHOLD);
    }

    @Test
    void deicticQuestionsAreRecognised() {
        for (String q : List.of("What does the document say about pumps?", "According to the passage, how often?", "What is the author's view?",
                "What is mentioned above?", "Summarise this section.")) {
            assertThat(QualityFilters.deictic(q)).as(q).isTrue();
        }
        for (String q : List.of("How often must pumps be inspected?", "What does the maintenance policy require?", "Who approves purchases above 500 dollars?")) {
            assertThat(QualityFilters.deictic(q)).as(q).isFalse();
        }
    }

    @Test
    void answerAgreementComparesMeaningfulWords() {
        assertThat(QualityFilters.answerAgreement("Every 90 days", "The pumps are inspected every 90 days.")).isEqualTo(1.0);
        assertThat(QualityFilters.answerAgreement("Every 90 days", "NOT STATED")).isLessThan(QualityFilters.ANSWER_AGREEMENT);
        assertThat(QualityFilters.answerAgreement("a certified engineer", "An engineer who is certified")).isGreaterThanOrEqualTo(QualityFilters.ANSWER_AGREEMENT);
    }

    @Test
    void duplicatesAreFoundByCosineSimilarity() {
        float[] a = {1, 0, 0}, nearA = {0.99f, 0.05f, 0}, other = {0, 1, 0};
        assertThat(QualityFilters.cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(QualityFilters.duplicate(nearA, List.of(a))).isTrue();
        assertThat(QualityFilters.duplicate(other, List.of(a))).isFalse();
        assertThat(QualityFilters.duplicate(a, List.of())).isFalse();
        assertThat(QualityFilters.cosine(new float[] {0, 0}, a)).isZero();
    }

    // ------------------------------------------------------------------ QuestionJson

    @Test
    void replyParsingToleratesFencesProseAndAlternativeKeys() {
        assertThat(QuestionJson.parse(JSON, "{\"question\": \"How often are pumps inspected?\", \"expected_answer\": \"Every 90 days\"}")).isPresent();
        assertThat(QuestionJson.parse(JSON, "```json\n{\"question\": \"How often are pumps inspected?\", \"answer\": \"Every 90 days\"}\n```")).isPresent();
        var p = QuestionJson.parse(JSON, "Sure! Here you go: {\"Question\": \"How often are pumps inspected?\", \"expectedAnswer\": \"Every 90 days\"} Hope it helps.").orElseThrow();
        assertThat(p.question()).isEqualTo("How often are pumps inspected?");
        assertThat(p.expectedAnswer()).isEqualTo("Every 90 days");
    }

    @Test
    void unusableRepliesAreRejected() {
        for (String bad : List.of("", "no json here", "{broken", "{\"question\": \"Short?\", \"answer\": \"x\"}",
                "{\"question\": \"How often are pumps inspected?\"}", "{\"question\": \"How often are pumps inspected?\", \"answer\": \"\"}",
                "{\"question\": \"Same text\", \"answer\": \"same text\"}", "[1, 2]")) {
            assertThat(QuestionJson.parse(JSON, bad)).as(bad).isEmpty();
        }
        assertThat(QuestionJson.parse(JSON, null)).isEmpty();
    }

    // ------------------------------------------------------------------ labels

    @Test
    void numericAnswersMakeNumericQuestions() {
        assertThat(GenerateTestsetHandler.label("FACTUAL", "Every 90 days")).isEqualTo("NUMERIC");
        assertThat(GenerateTestsetHandler.label("NUMERIC", "A certified engineer")).isEqualTo("FACTUAL");
        assertThat(GenerateTestsetHandler.label("MULTI_HOP", "Called 3 times")).isEqualTo("MULTI_HOP");
        assertThat(GenerateTestsetHandler.label("COMPARISON", "Both")).isEqualTo("COMPARISON");
    }

    // ------------------------------------------------------------------ QuestionSampler

    static List<Chunk> corpus(int docs, int chunksPerDoc) {
        List<Chunk> out = new ArrayList<>();
        for (int d = 0; d < docs; d++) {
            UUID doc = new UUID(0, d + 1), version = new UUID(1, d + 1);
            for (int c = 0; c < chunksPerDoc; c++) {
                String number = c % 3 == 0 ? " The limit is " + (c + 10) + " units." : "";
                String text = ("Document " + d + " chunk " + c + " describes how the system handles requests, retries failures, logs events and "
                        + "reports its health to operators every day; it also explains escalation rules clearly." + number);
                out.add(new Chunk(new UUID(2, d * 1000L + c), doc, version, "doc-" + d, c, c * 500, c * 500 + text.length(), null, text));
            }
        }
        return out;
    }

    @Test
    void samplingIsDeterministicForASeedAndDiffersAcrossSeeds() {
        List<Chunk> chunks = corpus(3, 12);
        var a = QuestionSampler.sample(chunks, 20, QuestionSampler.defaultMix(), 7, c -> Optional.empty());
        var b = QuestionSampler.sample(chunks, 20, QuestionSampler.defaultMix(), 7, c -> Optional.empty());
        var shuffledInput = new ArrayList<>(chunks);
        java.util.Collections.reverse(shuffledInput);
        var c2 = QuestionSampler.sample(shuffledInput, 20, QuestionSampler.defaultMix(), 7, c -> Optional.empty());
        var other = QuestionSampler.sample(chunks, 20, QuestionSampler.defaultMix(), 8, c -> Optional.empty());
        assertThat(ids(b)).isEqualTo(ids(a));
        assertThat(ids(c2)).as("input order does not matter").isEqualTo(ids(a));
        assertThat(ids(other)).isNotEqualTo(ids(a));
    }

    static List<String> ids(List<Sample> samples) {
        return samples.stream().map(s -> s.type() + ":" + s.chunks().stream().map(c -> c.id().toString()).collect(Collectors.joining("+"))).toList();
    }

    @Test
    void samplingSpreadsAcrossDocumentsInsteadOfFavouringTheBiggest() {
        List<Chunk> chunks = new ArrayList<>(corpus(1, 40));
        chunks.addAll(corpus(3, 4).stream().filter(c -> !c.documentId().equals(new UUID(0, 1))).toList());
        var samples = QuestionSampler.sample(chunks, 9, Map.of("FACTUAL", 1.0), 1, c -> Optional.empty());
        Map<UUID, Long> perDoc = samples.stream().collect(Collectors.groupingBy(s -> s.chunks().get(0).documentId(), Collectors.counting()));
        assertThat(perDoc).hasSize(3);
        assertThat(perDoc.values()).allSatisfy(n -> assertThat(n).isEqualTo(3));
    }

    @Test
    void samplingNeverReusesAPassageAsPrimaryAndStopsWhenThePoolRunsOut() {
        var samples = QuestionSampler.sample(corpus(2, 3), 50, Map.of("FACTUAL", 1.0), 3, c -> Optional.empty());
        assertThat(samples).hasSize(6);
        assertThat(samples.stream().map(s -> s.chunks().get(0).id()).distinct()).hasSize(6);
    }

    @Test
    void theTypeMixIsFollowedAndNumericQuestionsGetPassagesWithNumbers() {
        List<Chunk> chunks = corpus(2, 30);
        var samples = QuestionSampler.sample(chunks, 20, Map.of("FACTUAL", 0.5, "NUMERIC", 0.5), 1, c -> Optional.empty());
        Map<String, Long> byType = samples.stream().collect(Collectors.groupingBy(Sample::type, Collectors.counting()));
        assertThat(byType).containsEntry("FACTUAL", 10L).containsEntry("NUMERIC", 10L);
        samples.stream().filter(s -> s.type().equals("NUMERIC")).forEach(s -> assertThat(s.chunks().get(0).text()).containsPattern("\\d"));
        assertThat(QuestionSampler.split(10, QuestionSampler.defaultMix()).values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
        assertThat(QuestionSampler.split(7, Map.of("FACTUAL", 1.0, "NUMERIC", 1.0)).values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(7);
    }

    @Test
    void multiHopUsesANeighbourAndComparisonUsesTheCrossDocumentLookup() {
        List<Chunk> chunks = corpus(2, 6);
        var hops = QuestionSampler.sample(chunks, 6, Map.of("MULTI_HOP", 1.0), 1, c -> Optional.empty());
        for (Sample s : hops) {
            assertThat(s.type()).isEqualTo("MULTI_HOP");
            assertThat(s.chunks()).hasSize(2);
            assertThat(s.chunks().get(0).documentId()).isEqualTo(s.chunks().get(1).documentId());
            assertThat(Math.abs(s.chunks().get(0).ordinal() - s.chunks().get(1).ordinal())).isEqualTo(1);
        }
        var cmp = QuestionSampler.sample(chunks, 4, Map.of("COMPARISON", 1.0), 1,
                c -> chunks.stream().filter(o -> !o.documentId().equals(c.documentId())).findFirst());
        for (Sample s : cmp) {
            assertThat(s.type()).isEqualTo("COMPARISON");
            assertThat(s.chunks().get(0).documentId()).isNotEqualTo(s.chunks().get(1).documentId());
        }
    }

    @Test
    void typesDowngradeWhenTheCorpusCannotSupportThem() {
        var single = List.of(corpus(1, 1).get(0)); // one chunk: no neighbour, no other document
        assertThat(QuestionSampler.sample(single, 1, Map.of("MULTI_HOP", 1.0), 1, c -> Optional.empty()).get(0).type()).isEqualTo("FACTUAL");
        assertThat(QuestionSampler.sample(single, 1, Map.of("COMPARISON", 1.0), 1, c -> Optional.empty()).get(0).type()).isEqualTo("FACTUAL");
        var pair = corpus(1, 2);
        assertThat(QuestionSampler.sample(pair, 1, Map.of("COMPARISON", 1.0), 1, c -> Optional.empty()).get(0).type()).isEqualTo("MULTI_HOP");
    }

    @Test
    void tinyAndHeadingOnlyChunksAreNotSampled() {
        Chunk heading = new Chunk(UUID.randomUUID(), new UUID(0, 1), new UUID(1, 1), "d", 0, 0, 10, null, "# Heading");
        Chunk noSentence = new Chunk(UUID.randomUUID(), new UUID(0, 1), new UUID(1, 1), "d", 1, 10, 300, null, "word ".repeat(40));
        assertThat(QuestionSampler.useful(heading)).isFalse();
        assertThat(QuestionSampler.useful(noSentence)).isFalse();
        assertThat(QuestionSampler.sample(List.of(heading, noSentence), 3, QuestionSampler.defaultMix(), 1, c -> Optional.empty())).isEmpty();
    }
}
