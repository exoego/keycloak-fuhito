package net.exoego.keycloak.fuhito.keycloak;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a job of the timer on another thread, one run at a time.
 * Keycloak runs every scheduled task of a node on one thread, so a long job there holds up the tasks of Keycloak.
 */
final class InBackground implements Runnable {

    private final Executor executor;
    private final Runnable job;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates the task for the timer.
     *
     * @param executor the threads that run the job
     * @param job the job
     */
    InBackground(Executor executor, Runnable job) {
        this.executor = executor;
        this.job = job;
    }

    /**
     * Hands the job to the executor and returns at once. Starts nothing while the last run still goes on.
     */
    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            // Keycloak is stopping. An exception would end the timer thread of Keycloak.
            running.set(false);
        }
    }
}
