package dev.glassbox.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.TestDb;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SPEC 14: job queue with concurrent workers and no double processing; retries, DEAD, reaper. */
@SpringBootTest
@ActiveProfiles("api")
class JobQueueTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    /** Records every invocation; "FAIL" jobs always throw. */
    static final Map<UUID, AtomicInteger> HANDLED = new ConcurrentHashMap<>();
    static final List<UUID> DEAD = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Handlers {
        @Bean
        JobHandler countingHandler() {
            return new JobHandler() {
                public String type() { return "COUNT"; }
                public void handle(Job job) throws Exception {
                    HANDLED.computeIfAbsent(job.id(), k -> new AtomicInteger()).incrementAndGet();
                    Thread.sleep(2); // widen the race window
                }
            };
        }

        @Bean
        JobHandler failingHandler() {
            return new JobHandler() {
                public String type() { return "FAIL"; }
                public void handle(Job job) {
                    HANDLED.computeIfAbsent(job.id(), k -> new AtomicInteger()).incrementAndGet();
                    throw new IllegalStateException("boom");
                }
                public void onDead(Job job, String error) { DEAD.add(job.id()); }
            };
        }
    }

    @Autowired Tx tx;
    @Autowired JobQueue queue;
    @Autowired JobWorker worker;
    @Autowired WorkspaceService workspaces;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        tx.worker(j -> j.update("DELETE FROM jobs"));
        HANDLED.clear();
        DEAD.clear();
    }

    UUID newWorkspace() {
        UUID user = tx.anonymous(j -> j.queryForObject("SELECT auth_create_user(?, 'x')", UUID.class, "q-" + UUID.randomUUID() + "@example.com"));
        return workspaces.create(user, "queue").id();
    }

    int jobCount(String where) {
        Integer n = tx.worker(j -> j.queryForObject("SELECT count(*) FROM jobs " + where, Integer.class));
        return n;
    }

    int jobCountIn(UUID ws) {
        Integer n = tx.workspace(ws, null, j -> j.queryForObject("SELECT count(*) FROM jobs", Integer.class));
        return n;
    }

    String jobString(String column) {
        return tx.worker(j -> j.queryForObject("SELECT " + column + " FROM jobs", String.class));
    }

    void enqueue(UUID ws, String type, String key) {
        tx.workspace(ws, null, j -> {
            queue.enqueue(j, ws, type, json.valueToTree(Map.of("k", key)), key);
            return null;
        });
    }

    @Test
    void concurrentWorkersProcessEveryJobExactlyOnce() throws Exception {
        List<UUID> workspaceIds = List.of(newWorkspace(), newWorkspace(), newWorkspace());
        int jobs = 150;
        for (int i = 0; i < jobs; i++) enqueue(workspaceIds.get(i % 3), "COUNT", "job-" + UUID.randomUUID());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            String id = "w" + t;
            pool.submit(() -> {
                int emptyPolls = 0;
                while (emptyPolls < 3) emptyPolls = worker.runOnce(id) ? 0 : emptyPolls + 1;
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(HANDLED).hasSize(jobs);
        assertThat(HANDLED.values()).allSatisfy(n -> assertThat(n.get()).as("handled once").isEqualTo(1));
        assertThat(jobCount("WHERE status = 'DONE'")).isEqualTo(jobs);
        assertThat(queue.queueDepth()).isZero();
    }

    @Test
    void duplicateIdempotencyKeyIsIgnored() {
        UUID ws = newWorkspace();
        enqueue(ws, "COUNT", "same-key");
        enqueue(ws, "COUNT", "same-key");
        assertThat(queue.queueDepth()).isEqualTo(1);
    }

    @Test
    void failedJobsBackOffThenDieAfterFiveAttempts() {
        UUID ws = newWorkspace();
        enqueue(ws, "FAIL", "fail-1");

        for (int attempt = 1; attempt <= JobQueue.MAX_ATTEMPTS; attempt++) {
            assertThat(worker.runOnce("w")).as("attempt %d claims the job", attempt).isTrue();
            if (attempt < JobQueue.MAX_ATTEMPTS) {
                // Backed off into the future: not claimable yet.
                assertThat(worker.runOnce("w")).isFalse();
                var delay = tx.worker(j -> j.queryForObject(
                        "SELECT extract(epoch FROM run_after - now()) FROM jobs", Double.class));
                assertThat(delay).isGreaterThan(5.0);
                tx.worker(j -> j.update("UPDATE jobs SET run_after = now()")); // fast-forward
            }
        }
        assertThat(jobString("status")).isEqualTo("DEAD");
        assertThat(jobString("error")).contains("boom");
        assertThat(DEAD).hasSize(1);
        assertThat(HANDLED.values().iterator().next().get()).isEqualTo(JobQueue.MAX_ATTEMPTS);
        assertThat(worker.runOnce("w")).isFalse();
    }

    @Test
    void reaperRequeuesJobsWhoseLockExpired() {
        UUID ws = newWorkspace();
        enqueue(ws, "COUNT", "stale-1");
        queue.claim("crashed-worker").orElseThrow();
        assertThat(queue.reapStale()).isZero(); // fresh lock is left alone
        tx.worker(j -> j.update("UPDATE jobs SET locked_at = now() - interval '6 minutes'"));
        assertThat(queue.reapStale()).isEqualTo(1);
        assertThat(worker.runOnce("healthy")).isTrue();
        assertThat(HANDLED).hasSize(1);
    }

    @Test
    void ordinaryRequestsCannotSeeOtherWorkspacesJobs() {
        UUID a = newWorkspace(), b = newWorkspace();
        enqueue(a, "COUNT", "a-job");
        enqueue(b, "COUNT", "b-job");
        assertThat(jobCountIn(a)).isEqualTo(1);
        assertThat(jobCount("")).isEqualTo(2);
    }
}
