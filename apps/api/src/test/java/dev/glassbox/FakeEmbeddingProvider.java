package dev.glassbox;

import dev.glassbox.embed.EmbeddingProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for the sidecar, active in every Spring test (test classes live under the app's
 * component-scan root). Hashed bag of words, L2-normalised: texts that share words are closer. It claims the
 * bge-small model id so the schema's HNSW index applies to it. Counts calls so tests can prove vector reuse.
 */
@Component
public class FakeEmbeddingProvider implements EmbeddingProvider {
    public static final AtomicInteger TEXTS_EMBEDDED = new AtomicInteger();
    public static final AtomicInteger CALLS = new AtomicInteger();
    /** While positive, embed() throws and decrements: simulates a sidecar outage. */
    public static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}]*");

    @Override public String modelId() { return "bge-small-en-v1.5"; }

    @Override public int dimension() { return 384; }

    @Override
    public List<float[]> embed(List<String> texts) {
        CALLS.incrementAndGet();
        if (FAIL_NEXT.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new IllegalStateException("sidecar down (simulated)");
        TEXTS_EMBEDDED.addAndGet(texts.size());
        List<float[]> out = new ArrayList<>();
        for (String t : texts) out.add(vector(t, 384));
        return out;
    }

    public static float[] vector(String text, int dim) {
        float[] v = new float[dim];
        Matcher m = WORD.matcher(text.toLowerCase());
        while (m.find()) {
            byte[] h = sha(m.group());
            int idx = ((h[0] & 0xff) << 8 | (h[1] & 0xff)) % dim;
            v[idx] += 1f;
        }
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) v[0] = 1f; else for (int i = 0; i < dim; i++) v[i] /= (float) norm;
        return v;
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
