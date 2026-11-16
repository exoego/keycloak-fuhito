package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs tasks that do not depend on each other on a few threads.
 */
public final class InParallel {

    private InParallel() {
    }

    /**
     * Runs every task, also when one fails, and waits for all of them.
     *
     * @param tasks the tasks
     * @param threads the largest number of tasks that run at the same time
     * @throws RuntimeException the failure of the first task in the list that failed
     */
    public static void run(List<Runnable> tasks, int threads) {
        if (tasks.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, tasks.size()));
        try {
            List<Future<?>> running = tasks.stream().<Future<?>>map(pool::submit).toList();
            RuntimeException first = null;
            for (Future<?> task : running) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    if (first == null) {
                        first = e.getCause() instanceof RuntimeException cause ? cause
                                : new IllegalStateException(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (first != null) {
                throw first;
            }
        } finally {
            pool.shutdown();
        }
    }
}
