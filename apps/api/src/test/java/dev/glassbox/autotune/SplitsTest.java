package dev.glassbox.autotune;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.autotune.Splits.Item;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

class SplitsTest {
    private static final String[] TYPES = {"FACTUAL", "NUMERIC", "MULTI_HOP", "COMPARISON", "UNANSWERABLE"};

    private static List<Item> items(int n, int types) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Item(UUID.nameUUIDFromBytes(("q" + i).getBytes()), TYPES[i % types]));
        return out;
    }

    @Property(tries = 100)
    void everyQuestionIsOnExactlyOneSideAndTheShareIsAboutThirty(@ForAll @IntRange(min = 1, max = 300) int n, @ForAll @IntRange(min = 1, max = 5) int types,
                                                                  @ForAll long seed) {
        List<Item> items = items(n, types);
        Splits.Result r = Splits.split(seed, items);
        assertThat(r.tuning().size() + r.heldOut().size()).isEqualTo(n);
        assertThat(Collections.disjoint(r.tuning(), r.heldOut())).isTrue();
        for (Item i : items) assertThat(r.tuning().contains(i.id()) ^ r.heldOut().contains(i.id())).isTrue();
        // each type is cut by its own ~30% (rounded), so the total is within one question per type of 30%
        assertThat(Math.abs(r.heldOut().size() - n * Splits.HELD_OUT_SHARE)).isLessThanOrEqualTo(types);
        // a type with two or more questions is on both sides, so a held-out slice exists for it
        for (int t = 0; t < types; t++) {
            String type = TYPES[t];
            List<Item> of = items.stream().filter(i -> i.qtype().equals(type)).toList();
            if (of.size() >= 2) {
                assertThat(of).anyMatch(i -> r.heldOut().contains(i.id()));
                assertThat(of).anyMatch(i -> r.tuning().contains(i.id()));
            }
        }
    }

    @Property(tries = 50)
    void theSplitDoesNotDependOnTheOrderOfTheQuestions(@ForAll @IntRange(min = 2, max = 120) int n, @ForAll long seed) {
        List<Item> items = items(n, 4);
        Splits.Result a = Splits.split(seed, items);
        List<Item> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, new java.util.Random(seed));
        Splits.Result b = Splits.split(seed, shuffled);
        assertThat(b.heldOut()).isEqualTo(a.heldOut());
    }

    @Example
    void aDifferentSeedGivesADifferentSplit() {
        List<Item> items = items(100, 1);
        assertThat(Splits.split(1, items).heldOut()).isNotEqualTo(Splits.split(2, items).heldOut());
    }

    @Example
    void aLoneQuestionOfATypeStaysInTuning() {
        Splits.Result r = Splits.split(7, List.of(new Item(UUID.randomUUID(), "NUMERIC")));
        assertThat(r.heldOut()).isEmpty();
        assertThat(r.tuning()).hasSize(1);
    }
}
