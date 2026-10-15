package dev.glassbox.retrieval.transform;

import dev.glassbox.chunking.TokenIndex;
import dev.glassbox.generate.CachedLlm;
import dev.glassbox.generate.LlmProvider.Message;
import dev.glassbox.generate.LlmProvider.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The three model-backed transformers. They share one rule: a reply that cannot be used must never make the search worse than no transformer,
 * so every failure (an error from the model, an empty or garbled reply) falls back to searching the original question alone.
 */
public final class ModelTransformers {
    private static final Logger log = LoggerFactory.getLogger(ModelTransformers.class);
    static final int PARAPHRASES = 3;
    static final int SUB_QUESTIONS = 4;

    public static final String MULTI_QUERY_SYSTEM = """
            You rewrite search queries. Write %d different paraphrases of the user's question that mean the same thing but use different words.
            Keep every name, number and identifier exactly as written. Reply with the paraphrases only, one per line, no numbering and no commentary.""".formatted(PARAPHRASES);

    public static final String HYDE_SYSTEM = """
            You rewrite search queries. Write a short passage (two or three sentences) that would answer the user's question, in the style of a company
            handbook, using the names and terms of the question. Invent plausible details if you must: the passage is only used for searching.
            Reply with the passage only.""";

    public static final String DECOMPOSE_SYSTEM = """
            You rewrite search queries. If the user's question needs several separate facts, split it into at most %d simple sub-questions that each ask for
            one fact and can be understood on their own (repeat any name or place they all share). If the question is already a single fact, reply with the
            question unchanged. Reply with the questions only, one per line, no numbering and no commentary.""".formatted(SUB_QUESTIONS);

    private ModelTransformers() {}

    private abstract static class Base implements QueryTransformer {
        final CachedLlm llm;

        Base(CachedLlm llm) {
            this.llm = llm;
        }

        abstract String system();

        abstract int maxTokens();

        abstract Plan plan(String query, String reply, int tokens);

        @Override
        public Plan transform(UUID workspaceId, String query) {
            Request request = new Request(List.of(Message.system(system()), Message.user(query)), 0.0, maxTokens());
            String reply;
            try {
                reply = llm.complete(workspaceId, request);
            } catch (RuntimeException e) {
                log.warn("{} failed, searching the original question only: {}", type(), e.toString());
                return Plan.unchanged(query);
            }
            int tokens = TokenIndex.count(system()) + TokenIndex.count(query) + TokenIndex.count(reply == null ? "" : reply);
            return plan(query, reply, tokens);
        }
    }

    @Component
    public static class MultiQuery extends Base {
        public MultiQuery(CachedLlm llm) {
            super(llm);
        }

        @Override public Transformer type() { return Transformer.MULTI_QUERY; }

        @Override String system() { return MULTI_QUERY_SYSTEM; }

        @Override int maxTokens() { return 160; }

        @Override
        Plan plan(String query, String reply, int tokens) {
            List<Search> s = new ArrayList<>();
            s.add(Search.of(query));
            for (String p : ReplyLines.parse(reply, query, PARAPHRASES)) s.add(Search.of(p));
            return new Plan(s, 1, tokens);
        }
    }

    @Component
    public static class Hyde extends Base {
        public Hyde(CachedLlm llm) {
            super(llm);
        }

        @Override public Transformer type() { return Transformer.HYDE; }

        @Override String system() { return HYDE_SYSTEM; }

        @Override int maxTokens() { return 160; }

        @Override
        Plan plan(String query, String reply, int tokens) {
            String passage = reply == null ? "" : reply.strip();
            if (passage.length() < 20) return new Plan(List.of(Search.of(query)), 1, tokens);
            if (passage.length() > 1200) passage = passage.substring(0, 1200);
            // the question's own words still drive the keyword side: a hypothetical answer may name the wrong things
            return new Plan(List.of(new Search(query, passage)), 1, tokens);
        }
    }

    @Component
    public static class Decompose extends Base {
        public Decompose(CachedLlm llm) {
            super(llm);
        }

        @Override public Transformer type() { return Transformer.DECOMPOSE; }

        @Override String system() { return DECOMPOSE_SYSTEM; }

        @Override int maxTokens() { return 200; }

        @Override
        Plan plan(String query, String reply, int tokens) {
            List<Search> s = new ArrayList<>();
            s.add(Search.of(query));
            for (String q : ReplyLines.parse(reply, query, SUB_QUESTIONS)) s.add(Search.of(q));
            return new Plan(s, 1, tokens);
        }
    }
}
