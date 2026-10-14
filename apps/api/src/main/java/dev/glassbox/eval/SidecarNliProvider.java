package dev.glassbox.eval;

import dev.glassbox.embed.SidecarClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Entailment scores from the sidecar's NLI cross-encoder. */
@Component
@ConditionalOnProperty(name = "glassbox.embed.sidecar.enabled", havingValue = "true", matchIfMissing = true)
public class SidecarNliProvider implements NliProvider {
    static final String MODEL = "nli-deberta-v3-xsmall";
    /** The sidecar accepts at most 64 pairs per call. */
    static final int BATCH = 32;

    private final SidecarClient client;

    public SidecarNliProvider(SidecarClient client) {
        this.client = client;
    }

    @Override public String model() { return MODEL; }

    @Override
    public List<Score> score(List<Pair> pairs) {
        List<Score> out = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i += BATCH) {
            var batch = pairs.subList(i, Math.min(pairs.size(), i + BATCH)).stream().map(p -> new SidecarClient.NliPairDto(p.premise(), p.hypothesis())).toList();
            var r = client.nli(MODEL, batch);
            if (r == null || r.results() == null || r.results().size() != batch.size()) throw new IllegalStateException("sidecar returned a wrong number of NLI scores");
            r.results().forEach(s -> out.add(new Score(s.entailment(), s.neutral(), s.contradiction())));
        }
        return out;
    }
}
