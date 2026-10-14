package dev.glassbox.eval;

import dev.glassbox.dataset.Words;
import dev.glassbox.eval.ClaimSplitter.Claim;
import dev.glassbox.retrieval.ContextBuilder.Context;
import dev.glassbox.retrieval.ContextBuilder.ContextChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Claim-level verification (SPEC 10). Each claim is checked against its cited chunk and against the chunk of the context that
 * matches it best, so a claim that is true but cited to the wrong chunk counts as faithful (it is supported by what was retrieved)
 * and as a citation error (the cited chunk does not say it) separately.
 *
 * The NLI model decides the clear cases cheaply: strong entailment is SUPPORTED, no entailment is UNSUPPORTED. The LLM judge decides the
 * cases in between, where a verdict of PARTIAL is possible. If the judge's reply cannot be read the NLI score alone decides.
 */
@Component
public class ClaimVerifier {
    public enum Verdict { SUPPORTED, PARTIAL, UNSUPPORTED }

    /** {@code citedSupports} is null when the claim cites nothing. {@code premiseLabel} is the context chunk the verdict rests on. */
    public record Checked(String text, List<String> labels, Verdict verdict, String source, double entailment, String premiseLabel,
                          String premise, Boolean citedSupports, String reason) {}

    /**
     * Version of the verification procedure. Calibration of the faithfulness judge is per version, because the procedure is part of what is being
     * trusted. windows-v2: each sentence and each pair of neighbouring sentences is checked on its own and the best counts, because the NLI model
     * scored a claim that appears word for word in a three-sentence premise at 0.03 and the same claim against its own sentence at 0.98.
     */
    public static final String VERSION = "windows-v2";

    /** NLI scores at or above this make a claim SUPPORTED without asking the judge. */
    static final double CLEAR_SUPPORT = 0.8;
    /** Below this, no judge is needed either: the text does not say it. */
    static final double CLEAR_NO_SUPPORT = 0.1;
    /** A cited chunk counts as supporting its claim at this entailment. */
    static final double CITATION_SUPPORT = 0.5;
    private static final int WINDOWS = 3;

    private final NliProvider nli;
    private final Judge judge;

    public ClaimVerifier(NliProvider nli, Judge judge) {
        this.nli = nli;
        this.judge = judge;
    }

    private record Premise(String label, String text, boolean cited) {}

    /**
     * Candidate premises for a claim: single sentences and pairs of neighbouring sentences of the chunk, the few that share most of the claim's
     * meaningful words (fewer sentences win a tie). A small NLI model judges a short premise far better than a whole chunk.
     */
    static List<String> windows(String chunkText, String claim) {
        List<String> sentences = Sentences.split(chunkText);
        if (sentences.isEmpty()) return List.of(chunkText.strip());
        Set<String> want = Words.content(claim);
        record Window(String text, double score, int sentences, int position) {}
        List<Window> all = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            all.add(new Window(sentences.get(i), Words.overlap(want, Words.all(sentences.get(i))), 1, i));
            if (i > 0) {
                String pair = sentences.get(i - 1) + " " + sentences.get(i);
                all.add(new Window(pair, Words.overlap(want, Words.all(pair)), 2, i - 1));
            }
        }
        all.sort(Comparator.comparingDouble((Window w) -> -w.score()).thenComparingInt(Window::sentences).thenComparingInt(Window::position));
        List<String> out = new ArrayList<>();
        for (Window w : all) {
            if (!out.contains(w.text())) out.add(w.text());
            if (out.size() == WINDOWS) break;
        }
        return out;
    }

    public List<Checked> verify(List<Claim> claims, Context context, Runnable alive) {
        Map<String, ContextChunk> byLabel = new LinkedHashMap<>();
        context.included().forEach(c -> byLabel.put(c.label(), c));

        List<List<Premise>> premises = new ArrayList<>();
        List<NliProvider.Pair> pairs = new ArrayList<>();
        for (Claim claim : claims) {
            List<Premise> ps = new ArrayList<>();
            for (String label : claim.labels()) {
                ContextChunk c = byLabel.get(label);
                if (c != null) for (String w : windows(c.chunk().text(), claim.text())) ps.add(new Premise(label, w, true));
            }
            ContextChunk best = bestMatch(claim.text(), context);
            if (best != null && ps.stream().noneMatch(p -> p.label().equals(best.label()))) {
                for (String w : windows(best.chunk().text(), claim.text())) ps.add(new Premise(best.label(), w, false));
            }
            premises.add(ps);
            for (Premise p : ps) pairs.add(new NliProvider.Pair(p.text(), claim.text()));
        }
        List<NliProvider.Score> scores = pairs.isEmpty() ? List.of() : nli.score(pairs);
        alive.run();

        List<Checked> out = new ArrayList<>();
        int next = 0;
        for (int c = 0; c < claims.size(); c++) {
            Claim claim = claims.get(c);
            List<Premise> ps = premises.get(c);
            if (ps.isEmpty()) {
                out.add(new Checked(claim.text(), claim.labels(), Verdict.UNSUPPORTED, "NO_CONTEXT", 0, null, null, claim.labels().isEmpty() ? null : Boolean.FALSE, "nothing was retrieved to check it against"));
                continue;
            }
            int bestIndex = 0;
            double bestEntailment = -1, citedBest = -1;
            for (int k = 0; k < ps.size(); k++) {
                double e = scores.get(next + k).entailment();
                if (e > bestEntailment) {
                    bestEntailment = e;
                    bestIndex = k;
                }
                if (ps.get(k).cited() && e > citedBest) citedBest = e;
            }
            next += ps.size();
            Premise decisive = ps.get(bestIndex);
            Boolean citedSupports = claim.labels().isEmpty() ? null : citedBest >= CITATION_SUPPORT;

            Verdict verdict;
            String source = "NLI", reason = "";
            if (bestEntailment >= CLEAR_SUPPORT) {
                verdict = Verdict.SUPPORTED;
            } else if (bestEntailment < CLEAR_NO_SUPPORT) {
                verdict = Verdict.UNSUPPORTED;
            } else {
                Optional<Judge.Verdict> judged = judge.support(claim.text(), decisive.text());
                alive.run();
                if (judged.isPresent()) {
                    verdict = Verdict.valueOf(judged.get().label().toUpperCase());
                    source = "JUDGE";
                    reason = judged.get().reason();
                } else {
                    verdict = bestEntailment >= CITATION_SUPPORT ? Verdict.PARTIAL : Verdict.UNSUPPORTED;
                    reason = "the judge's reply could not be read; decided by the NLI score";
                }
            }
            out.add(new Checked(claim.text(), claim.labels(), verdict, source, bestEntailment, decisive.label(), decisive.text(), citedSupports, reason));
        }
        return out;
    }

    /** The context chunk that shares the most of the claim's meaningful words, or null when the context is empty. */
    static ContextChunk bestMatch(String claim, Context context) {
        Set<String> want = Words.content(claim);
        ContextChunk best = null;
        double bestScore = -1;
        for (ContextChunk c : context.included()) {
            double s = Words.overlap(want, Words.all(c.chunk().text()));
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }

    /** Supported share of all claims; NaN when there are none (an abstention has nothing to be faithful about). */
    public static double faithfulness(List<Checked> checked) {
        if (checked.isEmpty()) return Double.NaN;
        return (double) checked.stream().filter(c -> c.verdict() == Verdict.SUPPORTED).count() / checked.size();
    }

    /** Of the claims that cite something, the share whose cited chunk really says it; NaN when no claim cites. */
    public static double citationPrecision(List<Checked> checked) {
        long cited = checked.stream().filter(c -> c.citedSupports() != null).count();
        if (cited == 0) return Double.NaN;
        return (double) checked.stream().filter(c -> Boolean.TRUE.equals(c.citedSupports())).count() / cited;
    }
}
