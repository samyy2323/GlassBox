package dev.glassbox.rerank;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class Rerankers {
    private final Map<Reranker.Type, Reranker> byType = new EnumMap<>(Reranker.Type.class);

    public Rerankers(List<Reranker> rerankers) {
        rerankers.forEach(r -> byType.put(r.type(), r));
    }

    public Reranker get(Reranker.Type type) {
        Reranker r = byType.get(type);
        if (r == null) throw new IllegalStateException("No reranker configured for " + type + ". Available: " + byType.keySet());
        return r;
    }
}
