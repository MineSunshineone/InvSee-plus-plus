package com.janboerman.invsee.folia.transfer;

import com.janboerman.invsee.folia.offline.OfflineAccessCoordinator;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic ownership tests, NOT proof of Folia runtime region ownership. */
class AdversarialOwnerTest {
    static final StackValue ITEM = new StackValue(new byte[]{42, 64});
    static final class Owner implements SlotEndpoint, AutoCloseable {
        final String name;
        final ExecutorService executor;
        final AtomicReference<Thread> owner = new AtomicReference<>();
        StackValue value;
        volatile boolean retired;
        Owner(String name, StackValue initial) {
            this.name = name; value = initial;
            executor = Executors.newSingleThreadExecutor(r -> new Thread(r, name));
        }
        public String description() { return name; }
        <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.supplyAsync(() -> {
                owner.compareAndSet(null, Thread.currentThread());
                if (owner.get() != Thread.currentThread()) throw new AssertionError("wrong owner");
                if (retired) throw new IllegalStateException("owner retired");
                try { return task.call(); } catch (Exception e) { throw new CompletionException(e); }
            }, executor);
        }
        public CompletableFuture<StackValue> read() { return submit(() -> value); }
        public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
            return submit(() -> { if (!value.equals(expected)) return false; value = update; return true; });
        }
        public void close() { executor.shutdownNow(); }
    }
    @Test void simultaneousEditorsConserveStackAcrossThreeDistinctSyntheticOwners() throws Exception {
        try (Owner source = new Owner("target", ITEM); Owner a = new Owner("viewer-a", StackValue.EMPTY); Owner b = new Owner("viewer-b", StackValue.EMPTY)) {
            TransferService service = new TransferService(new TransferServiceTest.MemoryJournal());
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                CompletableFuture<TransferService.Result> x = service.transfer(UUID.randomUUID(), source, a, ITEM);
                CompletableFuture<TransferService.Result> y = service.transfer(UUID.randomUUID(), source, b, ITEM);
                CompletableFuture.allOf(x, y).get(10, TimeUnit.SECONDS);
                assertEquals(Set.of(TransferService.Outcome.COMPLETED, TransferService.Outcome.CONFLICT), Set.of(x.join().outcome(), y.join().outcome()));
                assertTrue(source.read().join().isEmpty());
                assertTrue(a.read().join().isEmpty() ^ b.read().join().isEmpty());
                Owner winner = a.read().join().isEmpty() ? b : a;
                assertEquals(TransferService.Outcome.COMPLETED, service.transfer(UUID.randomUUID(), winner, source, ITEM).get(10, TimeUnit.SECONDS).outcome());
                assertEquals(ITEM, source.read().join());
            }
            assertEquals(3, Set.of(source.owner.get(), a.owner.get(), b.owner.get()).size());
            System.out.printf("SYNTHETIC 3000 owner-dispatched transfers: %.3f ms (not Folia performance)\n", (System.nanoTime()-start)/1e6);
        }
    }
    @Test void retiredDestinationDoesNotReplayOrReturnUncertainEscrow() throws Exception {
        try (Owner source = new Owner("target", ITEM); Owner destination = new Owner("viewer", StackValue.EMPTY)) {
            destination.retired = true;
            TransferServiceTest.MemoryJournal journal = new TransferServiceTest.MemoryJournal();
            UUID id = UUID.randomUUID();
            TransferService service = new TransferService(journal);
            assertEquals(TransferService.Outcome.RECOVERY_REQUIRED, service.transfer(id, source, destination, ITEM).get(10, TimeUnit.SECONDS).outcome());
            assertTrue(source.read().join().isEmpty());
            destination.retired = false;
            assertEquals(TransferService.Outcome.RECOVERY_REQUIRED, new TransferService(journal).transfer(id, source, destination, ITEM).join().outcome());
            assertTrue(destination.read().join().isEmpty());
            assertEquals(ITEM, journal.records.get(id).item()); // retained review evidence, not automatically recoverable inventory
        }
    }
    @Test void simultaneousLoginAndOfflineEditHaveExactlyOneAdmission() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 1000; i++) {
                OfflineAccessCoordinator coordinator = new OfflineAccessCoordinator();
                UUID id = UUID.randomUUID();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<OfflineAccessCoordinator.Token> login = racers.submit(() -> { start.await(); return coordinator.tryLogin(id); });
                Future<OfflineAccessCoordinator.Token> edit = racers.submit(() -> { start.await(); return coordinator.tryOffline(id); });
                OfflineAccessCoordinator.Token l = login.get(5, TimeUnit.SECONDS), e = edit.get(5, TimeUnit.SECONDS);
                assertTrue((l == null) ^ (e == null));
                assertTrue(coordinator.isOwned(id));
                coordinator.close();
                (l == null ? e : l).close();
                assertFalse(coordinator.isOwned(id));
                assertNull(coordinator.tryOffline(id));
                assertNull(coordinator.tryLogin(id));
            }
        } finally { racers.shutdownNow(); }
    }
}
