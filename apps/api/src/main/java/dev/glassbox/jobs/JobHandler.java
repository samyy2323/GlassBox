package dev.glassbox.jobs;

public interface JobHandler {
    String type();

    /** Runs the job. Throw to fail it: it is retried with backoff and moves to DEAD after the last attempt. */
    void handle(Job job) throws Exception;

    /** Called once when the job exhausted its attempts, so the owning entity can show a failure. */
    default void onDead(Job job, String error) {}
}
