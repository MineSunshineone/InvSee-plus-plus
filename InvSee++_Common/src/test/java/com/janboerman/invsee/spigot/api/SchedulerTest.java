package com.janboerman.invsee.spigot.api;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {
    @Test void completesWhenTaskRuns() {
        TestScheduler scheduler = new TestScheduler();
        CompletableFuture<Integer> result = scheduler.supplySyncPlayer(UUID.randomUUID(), () -> 42);
        assertFalse(result.isDone());
        scheduler.task.run();
        assertEquals(42, result.join());
    }

    @Test void retirementCompletesRatherThanHangs() {
        TestScheduler scheduler = new TestScheduler();
        CompletableFuture<Integer> result = scheduler.supplySyncPlayer(UUID.randomUUID(), () -> 42);
        scheduler.retired.run();
        assertTrue(result.isCompletedExceptionally());
        assertThrows(CancellationException.class, result::join);
        scheduler.task.run();
        assertThrows(CancellationException.class, result::join);
    }

    @Test void schedulerRejectionCompletesFuture() {
        TestScheduler scheduler = new TestScheduler();
        scheduler.reject = true;
        CompletableFuture<Integer> result = scheduler.supplySyncPlayer(UUID.randomUUID(), () -> 42);
        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test void taskFailureCompletesFuture() {
        TestScheduler scheduler = new TestScheduler();
        CompletableFuture<Integer> result = scheduler.supplySyncPlayer(UUID.randomUUID(), () -> { throw new IllegalStateException("failure"); });
        scheduler.task.run();
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, result::join).getCause());
    }

    private static class TestScheduler implements Scheduler {
        Runnable task, retired;
        boolean reject;
        public void executeSyncPlayer(UUID player, Runnable task, Runnable retired) {
            if (reject) throw new RejectedExecutionException();
            this.task = task; this.retired = retired;
        }
        public void executeSyncGlobal(Runnable task) { throw new UnsupportedOperationException(); }
        public void executeSyncGlobalRepeatedly(Runnable task, long delay, long period) { throw new UnsupportedOperationException(); }
        public void executeAsync(Runnable task) { throw new UnsupportedOperationException(); }
        public void executeLaterGlobal(Runnable task, long delay) { throw new UnsupportedOperationException(); }
        public void executeLaterAsync(Runnable task, long delay) { throw new UnsupportedOperationException(); }
    }
}
