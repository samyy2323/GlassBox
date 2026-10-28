package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** SPEC 12: a cap on the experiments and AutoTune searches one workspace may have queued or running at once. */
@TestPropertySource(properties = "glassbox.limits.max-active-runs=2")
class ConcurrencyCapTest extends EvalApiSupport {
    private static final String ONE = "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]";

    @Test
    void aThirdRunIsRefusedWhileTwoAreActiveAndAllowedOnceTheyFinishAndOtherWorkspacesAreNotAffected() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "cap", 6);
        createExperiment(a, set, "first", ONE);
        // an AutoTune search counts toward the same cap as an experiment
        ok(send(post("/api/autotune"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"tune\",\"stage1Candidates\":6,\"stage2Candidates\":6,\"finalists\":2,\"seed\":7}"));

        send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"third\",\"configs\":" + ONE + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("most allowed at once is 2")));
        send(post("/api/autotune"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"tune two\",\"stage1Candidates\":6,\"stage2Candidates\":6,\"finalists\":2,\"seed\":8}")
                .andExpect(status().isConflict());
        assertThat(count(a, "SELECT count(*) FROM experiments")).as("the refused run left nothing behind").isEqualTo(1);

        Account other = accountWithDocs(2, 4);
        String otherSet = finalTestSet(other, "cap other", 6);
        createExperiment(other, otherSet, "unaffected", ONE);

        drainQueue();
        createExperiment(a, set, "after they finished", ONE);
    }
}
