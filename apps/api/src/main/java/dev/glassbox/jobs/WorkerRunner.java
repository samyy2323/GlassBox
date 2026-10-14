package dev.glassbox.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Placeholder for the job-queue worker (SPEC 5); the SKIP LOCKED loop arrives in P1. */
@Component
@Profile("worker")
public class WorkerRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    @Scheduled(fixedDelay = 30_000)
    void heartbeat() {
        log.info("worker alive; no job types registered yet");
    }
}
