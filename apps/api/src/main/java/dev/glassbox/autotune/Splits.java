package dev.glassbox.autotune;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tuning / held-out split of a test set (SPEC 9). AutoTune picks configurations on the tuning part and reports headline numbers
 * only from the held-out part, which no choice ever saw; this is what keeps the winner's curse out of the result.
 *
 * About 30% of every question type goes to held-out (at least one question per type that has two or more), so the held-out part is
 * a small copy of the whole test set, not a lucky or unlucky slice of it. Which question lands where depends only on the seed and
 * the question's id, so the split never changes between runs and a question can be traced to its side.
 */
public final class Splits {
    public static final double HELD_OUT_SHARE = 0.30;

    public record Item(UUID id, String qtype) {}

    public record Result(Set<UUID> tuning, Set<UUID> heldOut) {
        public boolean isHeldOut(UUID id) {
            return heldOut.contains(id);
        }
    }

    private Splits() {}

    public static Result split(long seed, List<Item> items) {
        Map<String, List<Item>> byType = new LinkedHashMap<>();
        for (Item i : items) byType.computeIfAbsent(i.qtype(), k -> new ArrayList<>()).add(i);
        Set<UUID> tuning = new HashSet<>(), heldOut = new HashSet<>();
        for (List<Item> group : byType.values()) {
            List<Item> ordered = new ArrayList<>(group);
            ordered.sort(Comparator.comparingLong((Item i) -> key(seed, i.id())).thenComparing(Item::id));
            int n = ordered.size();
            int out = n < 2 ? 0 : Math.max(1, (int) Math.round(n * HELD_OUT_SHARE));
            for (int k = 0; k < n; k++) (k < out ? heldOut : tuning).add(ordered.get(k).id());
        }
        return new Result(tuning, heldOut);
    }

    private static long key(long seed, UUID id) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((seed + ":" + id).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(d).getLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
