package dev.glassbox.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion of the result lists of several searches (one per query a transformer produced): a chunk scores the sum of
 * 1 / (k + rank) over the lists it appears in, so a chunk several queries agree on beats one only a single query found.
 */
public final class QueryFusion {
    private QueryFusion() {}

    /** One list is returned as it is. Otherwise the best {@code limit} chunks, each keeping the details (ranks, scores) of the first list that had it. */
    public static List<Candidate> fuse(List<List<Candidate>> lists, int rrfK, int limit) {
        if (lists.size() == 1) return lists.get(0);
        Map<UUID, Candidate> first = new LinkedHashMap<>();
        Map<UUID, Double> score = new LinkedHashMap<>();
        for (List<Candidate> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                Candidate c = list.get(i);
                first.putIfAbsent(c.chunkId(), c);
                score.merge(c.chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
            }
        }
        List<UUID> order = new ArrayList<>(first.keySet());
        Map<UUID, Integer> arrival = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) arrival.put(order.get(i), i);
        order.sort(Comparator.comparingDouble((UUID id) -> -score.get(id)).thenComparingInt(arrival::get));
        List<Candidate> out = new ArrayList<>();
        for (UUID id : order.subList(0, Math.min(limit, order.size()))) out.add(first.get(id).withFusedScore(score.get(id)));
        return out;
    }
}
