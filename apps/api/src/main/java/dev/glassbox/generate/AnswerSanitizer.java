package dev.glassbox.generate;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes anything in a model answer that could exfiltrate data or load remote content: images, links, bare URLs and
 * HTML tags (SPEC 12). Document injection can still change what an answer SAYS, but it cannot make the page fetch a URL.
 */
@Component
public class AnswerSanitizer {
    public record Result(String text, boolean changed) {}

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern HTML = Pattern.compile("</?[a-zA-Z][^>]*>");
    /** A URL or scheme-prefixed payload; sentence punctuation right after it is not part of it. */
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:(?:https?|ftp|data|javascript):|www\\.)[^\\s)\\]>\"']*[^\\s)\\]>\"'.,;:!?]");

    public Result sanitize(String answer) {
        String s = IMAGE.matcher(answer).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        s = HTML.matcher(s).replaceAll("");
        s = URL.matcher(s).replaceAll("[link removed]");
        return new Result(s, !s.equals(answer));
    }
}
