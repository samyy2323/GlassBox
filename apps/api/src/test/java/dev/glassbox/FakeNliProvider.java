package dev.glassbox;

import dev.glassbox.eval.NliProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for the sidecar's NLI model in Spring tests, the same rule as the sidecar's own fake mode: entailment is the share
 * of the hypothesis's words found in the premise, and the word "contradicts" in the hypothesis forces a contradiction.
 */
@Component
public class FakeNliProvider implements NliProvider {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    @Override public String model() { return "fake-nli"; }

    @Override
    public List<Score> score(List<Pair> pairs) {
        List<Score> out = new ArrayList<>();
        for (Pair p : pairs) {
            List<String> hypothesis = words(p.hypothesis());
            if (hypothesis.contains("contradicts")) {
                out.add(new Score(0.03, 0.07, 0.90));
                continue;
            }
            Set<String> premise = words(p.premise()).stream().collect(Collectors.toSet());
            double share = hypothesis.isEmpty() ? 0 : (double) hypothesis.stream().filter(premise::contains).count() / hypothesis.size();
            double entailment = 0.98 * share, rest = 1.0 - entailment;
            out.add(new Score(entailment, rest * 0.9, rest * 0.1));
        }
        return out;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }
}
