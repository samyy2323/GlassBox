package dev.glassbox.jobs;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the queue loop in the {@code worker} profile: N polling threads plus a periodic reaper. */
@Component
@Profile("worker")
public class WorkerRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final JobWorker worker;
    private final JobQueue queue;
    private final int concurrency;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    public WorkerRunner(JobWorker worker, JobQueue queue, @Value("${glassbox.worker.concurrency:2}") int concurrency) {
        this.worker = worker;
        this.queue = queue;
        this.concurrency = concurrency;
    }

    @PostConstruct
    void start() {
        String host = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < concurrency; i++) {
            String id = host + "-" + i;
            Thread t = new Thread(() -> loop(id), id);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        log.info("worker started with {} threads", concurrency);
    }

    private void loop(String id) {
        while (running) {
            try {
                if (!worker.runOnce(id)) Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("worker loop error", e);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void reap() {
        int n = queue.reapStale();
        if (n > 0) log.warn("requeued {} stale jobs", n);
    }

    @PreDestroy
    void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }
}
