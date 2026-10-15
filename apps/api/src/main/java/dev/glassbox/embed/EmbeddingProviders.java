package dev.glassbox.embed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Registry of the configured providers, keyed by model id. */
@Component
public class EmbeddingProviders {
    private final Map<String, EmbeddingProvider> byModel = new LinkedHashMap<>();
    private final String defaultModel;

    public EmbeddingProviders(List<EmbeddingProvider> providers, @Value("${glassbox.embed.default-model}") String defaultModel) {
        providers.forEach(p -> byModel.put(p.modelId(), p));
        this.defaultModel = defaultModel;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean has(String modelId) {
        return byModel.containsKey(modelId);
    }

    public java.util.Set<String> available() {
        return byModel.keySet();
    }

    public EmbeddingProvider get(String modelId) {
        EmbeddingProvider p = byModel.get(modelId);
        if (p == null) {
            throw new IllegalStateException("No embedding provider configured for model '" + modelId + "'. Available: " + byModel.keySet());
        }
        return p;
    }
}
