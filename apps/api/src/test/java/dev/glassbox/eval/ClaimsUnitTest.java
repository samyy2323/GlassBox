package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.eval.ClaimSplitter.Claim;
import dev.glassbox.eval.ClaimVerifier.Checked;
import dev.glassbox.eval.ClaimVerifier.Verdict;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.ContextBuilder.Context;
import dev.glassbox.retrieval.ContextBuilder.ContextChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Claim splitting, judge replies and claim verification: the parts of the eval lab that decide faithfulness. */
class ClaimsUnitTest {
    static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ sentences and claims

    @Test
    void sentencesKeepDecimalsAndCitationsTogether() {
        assertThat(Sentences.split("A reading below 4.5 millimetres per second is normal [c1]. Above 7.1 the pump stops [c2, c3]. Done"))
                .containsExactly("A reading below 4.5 millimetres per second is normal [c1].", "Above 7.1 the pump stops [c2, c3].", "Done");
        assertThat(Sentences.split("First line\nSecond line. Third!  Fourth?")).containsExactly("First line", "Second line.", "Third!", "Fourth?");
        assertThat(Sentences.split("")).isEmpty();
        assertThat(Sentences.split("No full stop at the end")).containsExactly("No full stop at the end");
        assertThat(Sentences.labels("It says so [c1, c3] and [c2].")).containsExactly("c1", "c3", "c2");
        assertThat(Sentences.stripLabels("Pumps are inspected every 90 days [c1].")).isEqualTo("Pumps are inspected every 90 days.");
    }

    @Test
    void anAnswerBecomesOneClaimPerStatementWithItsCitations() {
        List<Claim> claims = new ClaimSplitter().split("Pumps are inspected every 90 days [c1]. A certified engineer does the work [c1, c2].\nStandby pumps are tested monthly.");
        assertThat(claims).extracting(Claim::text).containsExactly("Pumps are inspected every 90 days.", "A certified engineer does the work.", "Standby pumps are tested monthly.");
        assertThat(claims.get(0).labels()).containsExactly("c1");
        assertThat(claims.get(1).labels()).containsExactly("c1", "c2");
        assertThat(claims.get(2).labels()).isEmpty();
    }

    @Test
    void abstentionsListMarkersAndPleasantriesAreNotClaims() {
        ClaimSplitter s = new ClaimSplitter();
        assertThat(s.split("The answer is not in the documents.")).isEmpty();
        assertThat(s.split("Sure! Here is what I found:\n1. Pumps are inspected every 90 days [c1].\n2. Valves are checked yearly [c2].")).extracting(Claim::text)
                .containsExactly("Pumps are inspected every 90 days.", "Valves are checked yearly.");
        assertThat(s.split("- Filters are replaced once a year [c1]")).extracting(Claim::text).containsExactly("Filters are replaced once a year");
        assertThat(s.split("Yes. Two.")).as("fragments of fewer than three words").isEmpty();
        assertThat(s.split("")).isEmpty();
    }

    // ------------------------------------------------------------------ judge replies

    @Test
    void theJudgeReplyIsReadFromJsonOrFromTheFirstAllowedWord() {
        assertThat(Judge.parse(JSON, "{\"verdict\": \"correct\", \"reason\": \"same facts\"}", Judge.CORRECTNESS).orElseThrow().label()).isEqualTo("correct");
        assertThat(Judge.parse(JSON, "```json\n{\"verdict\": \"Partial\"}\n```", Judge.CORRECTNESS).orElseThrow().label()).isEqualTo("partial");
        assertThat(Judge.parse(JSON, "Sure: {\"verdict\":\"no\",\"reason\":\"off topic\"}", Judge.RELEVANCE).orElseThrow().label()).isEqualTo("no");
        assertThat(Judge.parse(JSON, "The answer is wrong because it names another number.", Judge.CORRECTNESS).orElseThrow().label()).as("no JSON: first allowed word").isEqualTo("wrong");
        assertThat(Judge.parse(JSON, "{\"verdict\": \"maybe\"}", Judge.CORRECTNESS)).as("a label outside the rubric is not a verdict").isEmpty();
        assertThat(Judge.parse(JSON, "I cannot decide.", Judge.CORRECTNESS)).isEmpty();
        assertThat(Judge.parse(JSON, "", Judge.SUPPORT)).isEmpty();
        assertThat(Judge.parse(JSON, null, Judge.SUPPORT)).isEmpty();
        assertThat(Judge.parse(JSON, "{broken json supported", Judge.SUPPORT).orElseThrow().label()).isEqualTo("supported");
    }

    // ------------------------------------------------------------------ verification

    static Context context(String... texts) {
        List<ContextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            Candidate c = new Candidate(UUID.randomUUID(), UUID.randomUUID(), texts[i], 100, i * 1000, i * 1000 + texts[i].length(), null, 0.1, 1, 0.1, 1, 1.0, null);
            chunks.add(new ContextChunk("c" + (i + 1), c));
        }
        return new Context(chunks, List.of(), 100, 2000);
    }

    /** NLI where the score depends on the hypothesis text: scripted per test. */
    static NliProvider nli(Function<NliProvider.Pair, NliProvider.Score> rule) {
        return new NliProvider() {
            public String model() { return "scripted"; }
            public List<Score> score(List<Pair> pairs) { return pairs.stream().map(rule).toList(); }
        };
    }

    static Judge judge(Function<String, String> reply) {
        LlmProvider llm = new LlmProvider() {
            public String model() { return "judge-model"; }
            public void stream(Request request, java.util.function.Consumer<String> onToken) { onToken.accept(reply.apply(request.messages().get(1).content())); }
        };
        return new Judge(llm, JSON);
    }

    static NliProvider.Score entails(double e) {
        return new NliProvider.Score(e, (1 - e) * 0.8, (1 - e) * 0.2);
    }

    @Test
    void clearEntailmentAndClearNonEntailmentNeedNoJudge() {
        int[] judgeCalls = {0};
        ClaimVerifier v = new ClaimVerifier(nli(p -> p.hypothesis().contains("90 days") ? entails(0.97) : entails(0.02)), judge(u -> {
            judgeCalls[0]++;
            return "{\"verdict\": \"supported\"}";
        }));
        Context ctx = context("Pumps must be inspected every 90 days.", "The cafeteria opens at 8.");
        List<Checked> out = v.verify(List.of(new Claim("Pumps are inspected every 90 days.", List.of("c1")), new Claim("Pumps are inspected every 30 days by robots.", List.of("c1"))), ctx, () -> { });
        assertThat(out.get(0).verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(out.get(0).source()).isEqualTo("NLI");
        assertThat(out.get(1).verdict()).isEqualTo(Verdict.UNSUPPORTED);
        assertThat(judgeCalls[0]).as("the judge was not needed").isZero();
        assertThat(ClaimVerifier.faithfulness(out)).isEqualTo(0.5);
    }

    @Test
    void anUnsureNliScoreGoesToTheJudgeWhoCanSayPartial() {
        ClaimVerifier v = new ClaimVerifier(nli(p -> entails(0.4)), judge(u -> "{\"verdict\": \"partial\", \"reason\": \"only the number is there\"}"));
        List<Checked> out = v.verify(List.of(new Claim("Pumps are inspected every 90 days by an engineer.", List.of("c1"))), context("Pumps are inspected every 90 days."), () -> { });
        assertThat(out.get(0).verdict()).isEqualTo(Verdict.PARTIAL);
        assertThat(out.get(0).source()).isEqualTo("JUDGE");
        assertThat(out.get(0).reason()).contains("only the number");
        assertThat(ClaimVerifier.faithfulness(out)).as("partial is not supported").isZero();
    }

    @Test
    void anUnreadableJudgeFallsBackToTheNliScore() {
        Context ctx = context("Pumps are inspected every 90 days.");
        Claim claim = new Claim("Pumps are inspected every 90 days by an engineer.", List.of("c1"));
        assertThat(new ClaimVerifier(nli(p -> entails(0.6)), judge(u -> "I do not know")).verify(List.of(claim), ctx, () -> { })).extracting(Checked::verdict).containsExactly(Verdict.PARTIAL);
        assertThat(new ClaimVerifier(nli(p -> entails(0.3)), judge(u -> "I do not know")).verify(List.of(claim), ctx, () -> { })).extracting(Checked::verdict).containsExactly(Verdict.UNSUPPORTED);
    }

    @Test
    void aTrueClaimCitedToTheWrongChunkIsFaithfulButAWrongCitation() {
        // the fact is in c2, the claim cites c1: supported by what was retrieved, but the citation is wrong
        ClaimVerifier v = new ClaimVerifier(nli(p -> p.premise().contains("90 days") ? entails(0.95) : entails(0.02)), judge(u -> "{\"verdict\": \"supported\"}"));
        Context ctx = context("The cafeteria opens at eight and closes at four.", "Pumps must be inspected every 90 days.");
        Checked c = v.verify(List.of(new Claim("Pumps must be inspected every 90 days.", List.of("c1"))), ctx, () -> { })
                .get(0);
        assertThat(c.verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(c.premiseLabel()).isEqualTo("c2");
        assertThat(c.citedSupports()).isFalse();
        assertThat(ClaimVerifier.citationPrecision(List.of(c))).isZero();
    }

    @Test
    void uncitedClaimsAreCheckedAgainstTheBestMatchingChunkAndHaveNoCitationScore() {
        ClaimVerifier v = new ClaimVerifier(nli(p -> p.premise().contains("90 days") ? entails(0.95) : entails(0.02)), judge(u -> "{}"));
        Context ctx = context("The cafeteria opens at eight.", "Pumps must be inspected every 90 days.");
        Checked c = v.verify(List.of(new Claim("Pumps must be inspected every 90 days.", List.of())), ctx, () -> { }).get(0);
        assertThat(c.verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(c.premiseLabel()).isEqualTo("c2");
        assertThat(c.citedSupports()).isNull();
        assertThat(ClaimVerifier.citationPrecision(List.of(c))).isNaN();
    }

    @Test
    void withNothingRetrievedEveryClaimIsUnsupported() {
        ClaimVerifier v = new ClaimVerifier(nli(p -> entails(1.0)), judge(u -> "{}"));
        List<Checked> out = v.verify(List.of(new Claim("Pumps are inspected every 90 days.", List.of("c1"))), new Context(List.of(), List.of(), 0, 2000), () -> { });
        assertThat(out.get(0).verdict()).isEqualTo(Verdict.UNSUPPORTED);
        assertThat(out.get(0).source()).isEqualTo("NO_CONTEXT");
        assertThat(ClaimVerifier.faithfulness(List.of())).as("no claims, nothing to be faithful about").isNaN();
    }

    @Test
    void aChunkIsOfferedToTheModelAsShortWindowsAroundTheSentencesThatMatter() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("Filler sentence number ").append(i).append(" talks about unrelated things. ");
        sb.append("Pumps must be inspected every 90 days by a certified engineer. ");
        for (int i = 30; i < 60; i++) sb.append("Filler sentence number ").append(i).append(" talks about unrelated things. ");
        List<String> windows = ClaimVerifier.windows(sb.toString(), "Pumps are inspected every 90 days by an engineer.");
        assertThat(windows).hasSize(3);
        assertThat(windows.get(0)).as("the best window is the sentence itself, alone").isEqualTo("Pumps must be inspected every 90 days by a certified engineer.");
        assertThat(windows).allSatisfy(w -> assertThat(Sentences.split(w)).hasSizeLessThanOrEqualTo(2));
        assertThat(ClaimVerifier.windows("Only one sentence here.", "anything")).containsExactly("Only one sentence here.");
        assertThat(ClaimVerifier.windows("", "anything")).containsExactly("");
        assertThat(ClaimVerifier.windows("First one. Second one.", "second")).as("windows are not repeated").doesNotHaveDuplicates();
    }

    /** The finding that led to windows: the real NLI model scored a claim that appears word for word in a three-sentence premise at 0.03, and against its own sentence at 0.98. */
    @Test
    void aClaimThatIsInTheChunkWordForWordIsFoundEvenWhenTheModelFailsOnLongPremises() {
        String claim = "Two buddies are named for each person, and refuge areas with an intercom are located on each landing.";
        String chunk = "Fire Safety Procedure People who need assistance  Employees and visitors who cannot use the stairs unaided have a personal evacuation plan agreed with "
                + "the fire safety officer. " + claim + " The fire brigade is told about anyone waiting in a refuge area.";
        // a fragile model: entails only a short premise that contains the claim
        NliProvider fragile = nli(p -> p.premise().split("\s+").length <= 30 && p.premise().contains(p.hypothesis()) ? entails(0.97) : entails(0.03));
        ClaimVerifier v = new ClaimVerifier(fragile, judge(u -> "{\"verdict\": \"unsupported\"}"));
        Checked c = v.verify(List.of(new Claim(claim, List.of("c1"))), context(chunk), () -> { }).get(0);
        assertThat(c.verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(c.source()).isEqualTo("NLI");
        assertThat(c.premise()).as("what the model actually saw").isEqualTo(claim);
        assertThat(c.citedSupports()).isTrue();
        // the old way, handing the model the whole chunk, would have said unsupported
        assertThat(fragile.score(List.of(new NliProvider.Pair(chunk, claim))).get(0).entailment()).isLessThan(0.1);
    }

    @Test
    void faithfulnessAndCitationPrecisionAreSharesOfTheClaims() {
        List<Checked> checked = List.of(
                new Checked("a", List.of("c1"), Verdict.SUPPORTED, "NLI", 0.9, "c1", "p", true, ""),
                new Checked("b", List.of("c2"), Verdict.SUPPORTED, "NLI", 0.9, "c1", "p", false, ""),
                new Checked("c", List.of(), Verdict.UNSUPPORTED, "NLI", 0.0, "c1", "p", null, ""),
                new Checked("d", List.of(), Verdict.PARTIAL, "JUDGE", 0.4, "c1", "p", null, ""));
        assertThat(ClaimVerifier.faithfulness(checked)).isCloseTo(0.5, within(1e-12));
        assertThat(ClaimVerifier.citationPrecision(checked)).isCloseTo(0.5, within(1e-12));
        assertThat(Optional.of(ClaimVerifier.citationPrecision(List.of(checked.get(2), checked.get(3))))).get().satisfies(d -> assertThat((double) d).isNaN());
    }
}
