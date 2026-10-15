package dev.glassbox.retrieval.transform;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Transformers {
    private final Map<Transformer, QueryTransformer> byType = new EnumMap<>(Transformer.class);

    public Transformers(List<QueryTransformer> all) {
        all.forEach(t -> byType.put(t.type(), t));
    }

    /** Null and NONE both mean the question is searched as written. */
    public QueryTransformer.Plan plan(UUID workspaceId, Transformer type, String query) {
        if (type == null || type == Transformer.NONE) return QueryTransformer.Plan.unchanged(query);
        QueryTransformer t = byType.get(type);
        if (t == null) throw new IllegalStateException("No query transformer for " + type);
        return t.transform(workspaceId, query);
    }
}
