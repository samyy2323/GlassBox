package dev.glassbox.eval;

import dev.glassbox.generate.PromptBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits an answer into the statements that can be checked against the documents (SPEC 10): one per sentence, with the citation
 * labels the sentence used. The standard "not in the documents" reply, bare list numbers, short pleasantries and fragments of fewer
 * than three words are not claims.
 */
@Component
public class ClaimSplitter {
    public record Claim(String text, List<String> labels) {}

    private static final int MIN_WORDS = 3;
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*\\u2022]\\s*|\\d+[.)]\\s+)");
    private static final Pattern FILLER = Pattern.compile("^(sure|certainly|of course|okay|ok|here is|here are|based on the (documents|context|text))\\b[^.]{0,40}[:.!]?$",
            Pattern.CASE_INSENSITIVE);

    public List<Claim> split(String answer) {
        List<Claim> claims = new ArrayList<>();
        for (String sentence : Sentences.split(answer)) {
            String text = LIST_MARKER.matcher(Sentences.stripLabels(sentence)).replaceFirst("").strip();
            if (text.contains(PromptBuilder.NOT_IN_DOCUMENTS)) continue;
            if (text.split("\\s+").length < MIN_WORDS || FILLER.matcher(text).matches()) continue;
            claims.add(new Claim(text, Sentences.labels(sentence)));
        }
        return claims;
    }
}
