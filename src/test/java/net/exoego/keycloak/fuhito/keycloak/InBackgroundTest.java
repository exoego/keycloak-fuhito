package net.exoego.keycloak.fuhito.keycloak;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class InBackgroundTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    private static Runnable waitingFor(CountDownLatch release, AtomicInteger runs) {
        return () -> {
            runs.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    // The one thread runs the tasks in order, so the jobs before this one are done when it returns.
    private void waitForTheJobs() throws Exception {
        executor.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void theTimerIsFreeWhileTheJobStillRuns() throws Exception {
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        var timerTask = new InBackground(executor, waitingFor(release, runs));

        assertTimeoutPreemptively(Duration.ofSeconds(10), timerTask::run);

        release.countDown();
        waitForTheJobs();
        assertEquals(1, runs.get());
    }

    @Test
    void aCheckWhileTheLastRunGoesOnStartsNothing() throws Exception {
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        var timerTask = new InBackground(executor, waitingFor(release, runs));

        timerTask.run();
        timerTask.run();
        timerTask.run();
        release.countDown();
        waitForTheJobs();
        assertEquals(1, runs.get());

        timerTask.run();
        waitForTheJobs();
        assertEquals(2, runs.get(), "the next check after the end of the run starts one");
    }

    @Test
    void aRunThatFailsLetsTheNextCheckIn() {
        var runs = new AtomicInteger();
        var timerTask = new InBackground(Runnable::run, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("broken");
        });

        assertThrows(IllegalStateException.class, timerTask::run);
        assertThrows(IllegalStateException.class, timerTask::run);
        assertEquals(2, runs.get());
    }

    @Test
    void aRefusedRunThrowsNothingOnTheTimerThread() {
        var offers = new AtomicInteger();
        var runs = new AtomicInteger();
        var timerTask = new InBackground(job -> {
            if (offers.getAndIncrement() == 0) {
                throw new RejectedExecutionException("stopping");
            }
            job.run();
        }, runs::incrementAndGet);

        assertDoesNotThrow(timerTask::run);
        timerTask.run();
        assertEquals(1, runs.get(), "the refused run did not block the next one");
        assertEquals(2, offers.get());
    }
}
