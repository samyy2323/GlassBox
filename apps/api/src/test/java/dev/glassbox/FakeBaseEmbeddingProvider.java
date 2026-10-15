package dev.glassbox;

import dev.glassbox.embed.EmbeddingProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Stands in for the sidecar's bge-base-en-v1.5 in Spring tests; its own embedding space, so models can rank differently. */
@Component
public class FakeBaseEmbeddingProvider implements EmbeddingProvider {
    public static final AtomicInteger TEXTS_EMBEDDED = new AtomicInteger();

    @Override public String modelId() { return "bge-base-en-v1.5"; }

    @Override public int dimension() { return 768; }

    @Override
    public List<float[]> embed(List<String> texts) {
        TEXTS_EMBEDDED.addAndGet(texts.size());
        return texts.stream().map(t -> FakeEmbeddingProvider.vector(t, 768, "bge-base-en-v1.5")).toList();
    }
}
