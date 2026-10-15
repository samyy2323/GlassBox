package dev.glassbox.generate;

import dev.glassbox.auth.Hashing;
import dev.glassbox.generate.LlmProvider.Request;
import dev.glassbox.tenancy.Tx;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Temperature-0 model calls cached by a hash of the model, the parameters and the messages (SPEC 7). The same prompt gives the same answer, so
 * repeating an experiment or an AutoTune run costs nothing for the calls it has made before, and the cost estimator can use the measured hit rate.
 * A call with a temperature above zero is never cached. The cache is per workspace (row-level security), so one tenant's prompts are never
 * served to another.
 */
@Component
public class CachedLlm {
    private final LlmProvider llm;
    private final Tx tx;
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    public CachedLlm(LlmProvider llm, Tx tx) {
        this.llm = llm;
        this.tx = tx;
    }

    public String complete(UUID workspaceId, Request request) {
        if (request.temperature() > 0) return llm.complete(request);
        String key = key(request);
        var cached = tx.workspace(workspaceId, null, j -> j.queryForList("SELECT response FROM llm_cache WHERE key = ?", String.class, key));
        if (!cached.isEmpty()) {
            hits.incrementAndGet();
            return cached.get(0);
        }
        misses.incrementAndGet();
        String reply = llm.complete(request);
        if (reply != null && !reply.isBlank()) {
            tx.workspace(workspaceId, null, j -> j.update("INSERT INTO llm_cache (workspace_id, key, response) VALUES (?, ?, ?) ON CONFLICT (workspace_id, key) DO NOTHING",
                    workspaceId, key, reply));
        }
        return reply;
    }

    String key(Request r) {
        StringBuilder sb = new StringBuilder(llm.model()).append('|').append(r.temperature()).append('|').append(r.maxTokens()).append('|').append(r.jsonMode()).append('|').append(r.seed());
        for (var m : r.messages()) sb.append('|').append(m.role()).append(':').append(m.content());
        return Hashing.sha256Hex(sb.toString());
    }

    /** Share of calls served from the cache since the process started; 0 before any call. */
    public double hitRate() {
        long h = hits.get(), total = h + misses.get();
        return total == 0 ? 0 : (double) h / total;
    }
}
