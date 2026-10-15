package dev.glassbox.embed;

import java.util.ArrayList;
import java.util.List;

/** Local embeddings from the FastAPI sidecar. One instance per model; {@link SidecarEmbeddingConfig} registers them. */
public class SidecarEmbeddingProvider implements EmbeddingProvider {
    public static final String MODEL = "bge-small-en-v1.5";
    public static final int DIM = 384;

    private final SidecarClient client;
    private final int batchSize;
    private final String model;
    private final int dim;

    public SidecarEmbeddingProvider(SidecarClient client, int batchSize) {
        this(client, batchSize, MODEL, DIM);
    }

    public SidecarEmbeddingProvider(SidecarClient client, int batchSize, String model, int dim) {
        this.client = client;
        this.batchSize = batchSize;
        this.model = model;
        this.dim = dim;
    }

    @Override public String modelId() { return model; }

    @Override public int dimension() { return dim; }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + batchSize));
            var response = client.embed(model, batch);
            if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
                throw new IllegalStateException("sidecar returned a wrong number of embeddings");
            }
            for (List<Double> v : response.embeddings()) {
                if (v.size() != dim) throw new IllegalStateException("sidecar returned dimension " + v.size() + ", expected " + dim);
                float[] f = new float[v.size()];
                for (int k = 0; k < f.length; k++) f[k] = v.get(k).floatValue();
                out.add(f);
            }
        }
        return out;
    }
}
