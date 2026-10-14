package dev.glassbox.dataset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Chooses which passages to write questions about: stratified round-robin over documents so that one long document cannot
 * crowd out the others, seeded so the same inputs always give the same samples. Pure: the database lookups it needs
 * (chunks, cross-document neighbours) are passed in.
 */
public final class QuestionSampler {
    public static final String FACTUAL = "FACTUAL", NUMERIC = "NUMERIC", MULTI_HOP = "MULTI_HOP", COMPARISON = "COMPARISON";
    /** Types P2 can generate. UNANSWERABLE needs retrieval-based confirmation and arrives in P4. */
    public static final List<String> TYPES = List.of(FACTUAL, NUMERIC, MULTI_HOP, COMPARISON);

    private static final Pattern DIGITS = Pattern.compile("\\d");
    private static final int MIN_WORDS = 25;

    public record Chunk(UUID id, UUID documentId, UUID versionId, String title, int ordinal, int charStart, int charEnd, Integer page, String text) {}

    public record Sample(int index, String type, List<Chunk> chunks) {}

    private QuestionSampler() {}

    public static Map<String, Double> defaultMix() {
        Map<String, Double> mix = new LinkedHashMap<>();
        mix.put(FACTUAL, 0.55);
        mix.put(NUMERIC, 0.15);
        mix.put(MULTI_HOP, 0.15);
        mix.put(COMPARISON, 0.15);
        return mix;
    }

    /** Tiny chunks and heading-only chunks do not contain a fact worth asking about. */
    public static boolean useful(Chunk c) {
        String t = c.text().strip();
        if (t.split("\\s+").length < MIN_WORDS) return false;
        return t.chars().anyMatch(ch -> ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':');
    }

    /** How many of each type for {@code count} samples, by largest remainder. */
    public static Map<String, Integer> split(int count, Map<String, Double> mix) {
        double total = mix.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Double> rest = new LinkedHashMap<>();
        int assigned = 0;
        for (var e : mix.entrySet()) {
            double exact = count * e.getValue() / total;
            out.put(e.getKey(), (int) Math.floor(exact));
            rest.put(e.getKey(), exact - Math.floor(exact));
            assigned += (int) Math.floor(exact);
        }
        List<String> byRemainder = new ArrayList<>(rest.keySet());
        byRemainder.sort(Comparator.comparingDouble((String k) -> -rest.get(k)));
        for (int i = 0; assigned < count; i++, assigned++) out.merge(byRemainder.get(i % byRemainder.size()), 1, Integer::sum);
        return out;
    }

    public static List<Sample> sample(List<Chunk> chunks, int count, Map<String, Double> mix, long seed,
                                      Function<Chunk, Optional<Chunk>> crossDocumentNeighbour) {
        List<Chunk> eligible = new ArrayList<>(chunks.stream().filter(QuestionSampler::useful).toList());
        eligible.sort(Comparator.comparing(Chunk::documentId).thenComparingInt(Chunk::ordinal));
        Map<String, Chunk> byPosition = new HashMap<>();
        for (Chunk c : eligible) byPosition.put(c.documentId() + "#" + c.ordinal(), c);

        Random random = new Random(seed);
        Map<UUID, List<Chunk>> grouped = new TreeMap<>();
        for (Chunk c : eligible) grouped.computeIfAbsent(c.documentId(), k -> new ArrayList<>()).add(c);
        TreeMap<UUID, Deque<Chunk>> pools = new TreeMap<>();
        for (var e : grouped.entrySet()) {
            List<Chunk> shuffled = new ArrayList<>(e.getValue());
            Collections.shuffle(shuffled, random);
            pools.put(e.getKey(), new ArrayDeque<>(shuffled));
        }

        List<String> plan = interleave(split(count, mix));
        List<UUID> docs = new ArrayList<>(pools.keySet());
        int[] cursor = {0};
        List<Sample> out = new ArrayList<>();
        for (String wanted : plan) {
            Predicate<Chunk> want = wanted.equals(NUMERIC) ? c -> DIGITS.matcher(c.text()).find() : c -> true;
            Chunk primary = take(pools, docs, cursor, want);
            if (primary == null) break; // every passage has been used
            String type = wanted;
            List<Chunk> used = List.of(primary);
            if (type.equals(NUMERIC) && !DIGITS.matcher(primary.text()).find()) type = FACTUAL;
            if (type.equals(COMPARISON)) {
                Optional<Chunk> other = crossDocumentNeighbour.apply(primary);
                if (other.isPresent()) used = List.of(primary, other.get());
                else type = MULTI_HOP;
            }
            if (type.equals(MULTI_HOP)) {
                Chunk next = byPosition.get(primary.documentId() + "#" + (primary.ordinal() + 1));
                if (next == null) next = byPosition.get(primary.documentId() + "#" + (primary.ordinal() - 1));
                if (next != null) used = primary.ordinal() <= next.ordinal() ? List.of(primary, next) : List.of(next, primary);
                else type = FACTUAL;
            }
            out.add(new Sample(out.size(), type, used));
        }
        return out;
    }

    /** FACTUAL, NUMERIC, MULTI_HOP, COMPARISON, FACTUAL, ... so that stopping early still leaves a mix. */
    private static List<String> interleave(Map<String, Integer> counts) {
        Map<String, Integer> left = new LinkedHashMap<>(counts);
        List<String> plan = new ArrayList<>();
        while (left.values().stream().anyMatch(n -> n > 0)) {
            for (var e : left.entrySet()) {
                if (e.getValue() > 0) {
                    plan.add(e.getKey());
                    e.setValue(e.getValue() - 1);
                }
            }
        }
        return plan;
    }

    /** Next chunk in document round-robin order that satisfies {@code want}; any chunk if none does. */
    private static Chunk take(TreeMap<UUID, Deque<Chunk>> pools, List<UUID> docs, int[] cursor, Predicate<Chunk> want) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < docs.size(); i++) {
                int at = (cursor[0] + i) % docs.size();
                Deque<Chunk> pool = pools.get(docs.get(at));
                Chunk found = null;
                for (Chunk c : pool) {
                    if (pass == 1 || want.test(c)) {
                        found = c;
                        break;
                    }
                }
                if (found != null) {
                    pool.remove(found);
                    cursor[0] = (at + 1) % docs.size();
                    return found;
                }
            }
        }
        return null;
    }
}
