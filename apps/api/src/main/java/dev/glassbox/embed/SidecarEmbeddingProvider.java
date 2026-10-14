package dev.glassbox.embed;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local embeddings from the FastAPI sidecar (bge-small by default). */
@Component
@ConditionalOnProperty(name = "glassbox.embed.sidecar.enabled", havingValue = "true", matchIfMissing = true)
public class SidecarEmbeddingProvider implements EmbeddingProvider {
    public static final String MODEL = "bge-small-en-v1.5";
    public static final int DIM = 384;

    private final SidecarClient client;
    private final int batchSize;

    public SidecarEmbeddingProvider(SidecarClient client, @Value("${glassbox.embed.batch-size}") int batchSize) {
        this.client = client;
        this.batchSize = batchSize;
    }

    @Override public String modelId() { return MODEL; }

    @Override public int dimension() { return DIM; }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + batchSize));
            var response = client.embed(MODEL, batch);
            if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
                throw new IllegalStateException("sidecar returned a wrong number of embeddings");
            }
            for (List<Double> v : response.embeddings()) {
                if (v.size() != DIM) throw new IllegalStateException("sidecar returned dimension " + v.size() + ", expected " + DIM);
                float[] f = new float[v.size()];
                for (int k = 0; k < f.length; k++) f[k] = v.get(k).floatValue();
                out.add(f);
            }
        }
        return out;
    }
}
