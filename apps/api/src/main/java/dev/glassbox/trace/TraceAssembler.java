package dev.glassbox.trace;

import dev.glassbox.generate.CitationParser;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.ContextBuilder.Context;
import dev.glassbox.retrieval.ContextBuilder.ContextChunk;
import dev.glassbox.retrieval.ContextBuilder.Dropped;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.trace.TraceModel.CandidateView;
import dev.glassbox.trace.TraceModel.ContextView;
import dev.glassbox.trace.TraceModel.Disposition;
import dev.glassbox.trace.TraceModel.DroppedView;
import dev.glassbox.trace.TraceModel.IncludedView;
import dev.glassbox.trace.TraceModel.TraceData;
import dev.glassbox.trace.TraceModel.Usage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns a retrieval result (plus what the chat turn added) into the stored trace data. */
@Component
public class TraceAssembler {
    static final int SNIPPET_CHARS = 300;

    public TraceData assemble(RetrievalPipeline.Result result, Map<UUID, String> titles, List<LlmProvider.Message> prompt,
                              CitationParser.Parsed citations, Usage usage, String error) {
        List<CandidateView> candidates = result == null ? List.of() : candidates(result, titles);
        ContextView context = result == null ? null : context(result.context());
        return new TraceData(result == null ? null : result.config(), candidates, context, prompt,
                citations == null ? List.of() : citations.citations(), citations == null ? List.of() : citations.unknown(), usage, error);
    }

    private List<CandidateView> candidates(RetrievalPipeline.Result r, Map<UUID, String> titles) {
        Map<UUID, ContextChunk> included = new HashMap<>();
        r.context().included().forEach(c -> included.put(c.chunk().chunkId(), c));
        Map<UUID, Dropped> dropped = new HashMap<>();
        r.context().dropped().forEach(d -> dropped.put(d.chunk().chunkId(), d));
        // Position and score come from the reranker's full order of the pool it saw (for NONE that is the fused order).
        Map<UUID, Integer> position = new HashMap<>();
        Map<UUID, Double> rerank = new HashMap<>();
        for (int i = 0; i < r.reranked().size(); i++) {
            Candidate c = r.reranked().get(i);
            position.put(c.chunkId(), i + 1);
            if (c.rerankScore() != null) rerank.put(c.chunkId(), c.rerankScore());
        }
        int topN = r.config().topN();
        int pool = r.config().rerankCandidates();

        List<CandidateView> out = new ArrayList<>();
        for (Candidate c : r.candidates()) {
            ContextChunk in = included.get(c.chunkId());
            Dropped dr = dropped.get(c.chunkId());
            Disposition disposition;
            String label = null;
            String reason = null;
            if (in != null) {
                disposition = Disposition.IN_CONTEXT;
                label = in.label();
            } else if (dr != null) {
                disposition = Disposition.DROPPED;
                reason = dr.reason() + ": " + dr.detail();
            } else {
                disposition = Disposition.CUT;
                Integer pos = position.get(c.chunkId());
                reason = pos == null ? "outside the first " + pool + " fused candidates, which are all the reranker sees"
                        : "ranked #" + pos + ", below the top " + topN;
            }
            out.add(new CandidateView(c.chunkId(), c.documentId(), titles.getOrDefault(c.documentId(), "document"), c.page(),
                    c.charStart(), c.charEnd(), c.tokenCount(), snippet(c.text()), c.fusedScore(), c.vecRank(), c.vecDistance(),
                    c.kwRank(), c.kwScore(), rerank.get(c.chunkId()), position.get(c.chunkId()), disposition, label, reason));
        }
        return out;
    }

    private static ContextView context(Context ctx) {
        return new ContextView(ctx.budget(), ctx.tokensUsed(),
                ctx.included().stream().map(c -> new IncludedView(c.label(), c.chunk().chunkId(), c.chunk().tokenCount())).toList(),
                ctx.dropped().stream().map(d -> new DroppedView(d.chunk().chunkId(), d.reason().name(), d.detail())).toList());
    }

    static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }
}
