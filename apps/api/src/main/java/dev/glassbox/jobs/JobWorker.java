package dev.glassbox.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Claims and runs one job at a time. Safe to call from many threads and from many processes. */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final Map<String, JobHandler> handlers = new HashMap<>();

    public JobWorker(JobQueue queue, List<JobHandler> handlers) {
        this.queue = queue;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
    }

    /** @return true if a job was claimed (whether it succeeded or not). */
    public boolean runOnce(String workerId) {
        var claimed = queue.claim(workerId);
        if (claimed.isEmpty()) return false;
        Job job = claimed.get();
        JobHandler handler = handlers.get(job.type());
        try {
            if (handler == null) throw new IllegalStateException("No handler for job type " + job.type());
            handler.handle(job);
            queue.complete(job);
        } catch (Exception e) {
            log.warn("job {} ({}) failed on attempt {}: {}", job.id(), job.type(), job.attempts(), e.toString());
            boolean dead = queue.fail(job, e.toString());
            if (dead && handler != null) {
                try {
                    handler.onDead(job, e.toString());
                } catch (RuntimeException inner) {
                    log.error("onDead for job {} failed", job.id(), inner);
                }
            }
        }
        return true;
    }
}
