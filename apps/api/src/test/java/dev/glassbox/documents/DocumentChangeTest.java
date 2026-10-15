package dev.glassbox.documents;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.documents.SpanRelocator.How;
import dev.glassbox.documents.TextDiff.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChangeTest {
    private static final String OLD = "Intro paragraph about the site.\n\nPumps are inspected every 14 days by the maintenance lead.\n\n"
            + "Visitors sign in at the main reception and wear a badge.\n\nThe assembly point is the north car park.";

    // ------------------------------------------------------------------ the diff

    @Test
    void theDiffListsKeptAddedAndRemovedParagraphsInReadingOrder() {
        String changed = "Intro paragraph about the site.\n\nPumps are inspected every 7 days by the maintenance lead.\n\nVisitors sign in at the main reception and wear a badge.\n\n"
                + "A new section about parking.\n\nThe assembly point is the north car park.";
        var ops = TextDiff.diff(OLD, changed);
        assertThat(ops).extracting(TextDiff.Op::kind).containsExactly(Kind.SAME, Kind.REMOVED, Kind.ADDED, Kind.SAME, Kind.ADDED, Kind.SAME);
        assertThat(ops.get(1).text()).contains("14 days");
        assertThat(ops.get(2).text()).contains("7 days");
        assertThat(TextDiff.summarize(ops)).isEqualTo(new TextDiff.Summary(3, 2, 1));
    }

    @Test
    void identicalAndEmptyTextsAndSpacingOnlyChanges() {
        assertThat(TextDiff.summarize(TextDiff.diff(OLD, OLD))).isEqualTo(new TextDiff.Summary(4, 0, 0));
        assertThat(TextDiff.diff("", "")).isEmpty();
        assertThat(TextDiff.summarize(TextDiff.diff("", "One.\n\nTwo."))).isEqualTo(new TextDiff.Summary(0, 2, 0));
        assertThat(TextDiff.summarize(TextDiff.diff("One.\n\n\n\n   Two.   ", "One.\n\nTwo."))).as("blank lines and trailing spaces are not changes").isEqualTo(new TextDiff.Summary(2, 0, 0));
    }

    // ------------------------------------------------------------------ re-locating a gold span

    private static int start(String text, String part) {
        return text.indexOf(part);
    }

    @Test
    void anUnchangedPassageIsFoundExactlyEvenWhenItMoved() {
        String span = "Pumps are inspected every 14 days by the maintenance lead.";
        String moved = "A new first section.\n\nIt has two paragraphs.\n\n" + OLD;
        var found = SpanRelocator.relocate(OLD, moved, start(OLD, span), start(OLD, span) + span.length()).orElseThrow();
        assertThat(found.how()).isEqualTo(How.EXACT);
        assertThat(moved.substring(found.start(), found.end())).isEqualTo(span);
    }

    @Test
    void whenThePassageAppearsTwiceTheOneNearestTheOldPositionWins() {
        String span = "Wear a badge at all times on site.";
        String old = "Part A. " + span + " Other things here to make space between them. ".repeat(3) + "Part B. " + span;
        String fresh = old;
        int partB = old.lastIndexOf(span);
        var found = SpanRelocator.relocate(old, fresh, partB, partB + span.length()).orElseThrow();
        assertThat(found.start()).isEqualTo(partB);
    }

    @Test
    void differentSpacingStillMatches() {
        String span = "Visitors sign in at the main reception and wear a badge.";
        String reflowed = OLD.replace("sign in at the main", "sign in\nat   the\nmain");
        var found = SpanRelocator.relocate(OLD, reflowed, start(OLD, span), start(OLD, span) + span.length()).orElseThrow();
        assertThat(found.how()).isEqualTo(How.WHITESPACE);
        assertThat(reflowed.substring(found.start(), found.end())).startsWith("Visitors sign in").endsWith("badge.");
    }

    @Test
    void aLightlyEditedPassageIsFoundByItsWordsButARewrittenOrDeletedOneIsNot() {
        String span = "Pumps are inspected every 14 days by the maintenance lead.";
        String edited = OLD.replace("every 14 days", "every 7 days");
        var found = SpanRelocator.relocate(OLD, edited, start(OLD, span), start(OLD, span) + span.length()).orElseThrow();
        assertThat(found.how()).isEqualTo(How.FUZZY);
        assertThat(edited.substring(found.start(), found.end())).contains("every 7 days");

        String rewritten = OLD.replace(span, "Maintenance of pumps now follows the vendor schedule described in appendix C.");
        assertThat(SpanRelocator.relocate(OLD, rewritten, start(OLD, span), start(OLD, span) + span.length())).as("stale, not guessed").isEmpty();
        String deleted = OLD.replace(span + "\n\n", "");
        assertThat(SpanRelocator.relocate(OLD, deleted, start(OLD, span), start(OLD, span) + span.length())).isEmpty();
    }

    @Test
    void nonsenseRangesAndTinySpansAreNeverMatched() {
        assertThat(SpanRelocator.relocate(OLD, OLD, 5, 5)).isEmpty();
        assertThat(SpanRelocator.relocate(OLD, OLD, -1, 4)).isEmpty();
        assertThat(SpanRelocator.relocate(OLD, OLD, 0, OLD.length() + 5)).isEmpty();
        assertThat(SpanRelocator.relocate("a b c", "x y z a", 0, 3)).as("two words cannot be told from any other two").isEmpty();
        assertThat(SpanRelocator.relocate("   \n  ", "anything", 0, 3)).isEmpty();
    }
}
