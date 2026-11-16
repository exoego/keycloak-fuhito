package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InParallelTest {

    @Test
    void runsTasksAtTheSameTime() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<Runnable> tasks = new ArrayList<>();
        Set<String> done = ConcurrentHashMap.newKeySet();
        for (String name : List.of("a", "b")) {
            tasks.add(() -> {
                bothStarted.countDown();
                try {
                    // Each task waits for the other, so they end only when both run at once.
                    assertTrue(bothStarted.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                done.add(name);
            });
        }

        InParallel.run(tasks, 2);

        assertEquals(Set.of("a", "b"), done);
    }

    @Test
    void runsEveryTaskAndThrowsTheFirstFailure() {
        Set<String> done = ConcurrentHashMap.newKeySet();
        IllegalArgumentException first = new IllegalArgumentException("first");
        List<Runnable> tasks = List.of(
                () -> done.add("before"),
                () -> {
                    throw first;
                },
                () -> {
                    throw new IllegalStateException("second");
                },
                () -> done.add("after"));

        assertSame(first, assertThrows(IllegalArgumentException.class, () -> InParallel.run(tasks, 2)));
        assertEquals(Set.of("before", "after"), done);
        InParallel.run(List.of(), 2);
    }

    @Test
    void wrapsAFailureThatIsNotARuntimeException() {
        List<Runnable> tasks = List.of(() -> {
            throw new AssertionError("broken");
        });

        assertInstanceOf(AssertionError.class,
                assertThrows(IllegalStateException.class, () -> InParallel.run(tasks, 1)).getCause());
    }

    @Test
    void anInterruptStopsTheWaitAndKeepsTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> InParallel.run(List.of(() -> { }), 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
