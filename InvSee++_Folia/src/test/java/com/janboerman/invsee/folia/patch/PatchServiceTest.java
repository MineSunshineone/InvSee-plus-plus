package com.janboerman.invsee.folia.patch;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.janboerman.invsee.folia.patch.PatchRecord.State;
import static com.janboerman.invsee.folia.patch.PatchService.Outcome;

class PatchServiceTest {
    private static final UUID ACTOR = UUID.randomUUID(), TARGET = UUID.randomUUID();

    @Test void allAdministrativeKindsUseWriteAheadAndAcknowledgementOrder() {
        for (PatchType type : PatchType.values()) for (InventoryKind kind : InventoryKind.values()) {
            Journal journal = new Journal(); UUID id = UUID.randomUUID();
            byte[][] before = inventory(kind, 1), after = inventory(kind, 2);
            Endpoint endpoint = new Endpoint(before) {
                public CompletableFuture<Boolean> compareAndSet(byte[][] expected, byte[][] replacement) {
                    assertEquals(State.PREPARED, journal.records.get(id).state());
                    return super.compareAndSet(expected, replacement);
                }
                public CompletableFuture<Void> acknowledgeCommit() {
                    assertEquals(State.APPLIED, journal.records.get(id).state()); return super.acknowledgeCommit();
                }
            };
            PatchService.Result result = new PatchService(journal).apply(id, ACTOR, type, TARGET, kind, endpoint, before, after).join();
            assertEquals(Outcome.COMPLETED, result.outcome());
            assertTrue(Arrays.deepEquals(after, endpoint.value));
            assertEquals(Arrays.asList(State.PREPARED, State.APPLIED, State.COMPLETED), journal.phases);
        }
    }

    @Test void changedInventoryConflictsWithoutAnyMutationOrAck() {
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 3));
        assertEquals(Outcome.CONFLICT, apply(new PatchService(new Journal()), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertEquals(3, endpoint.value[0][0]); assertEquals(0, endpoint.acks);
    }

    @Test void partialMutationExceptionIsQuarantinedNotOverwritten() {
        Journal journal = new Journal();
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1)) {
            public CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after) {
                calls++; value[0] = after[0].clone(); return failed(new IllegalStateException("uncertain partial mutation"));
            }
        };
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(new PatchService(journal), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertEquals(1, endpoint.calls); assertEquals(2, endpoint.value[0][0]); assertEquals(0, endpoint.acks);
    }

    @Test void initialJournalFailurePreventsCas() {
        Journal journal = new Journal(); journal.fail = State.PREPARED;
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        assertEquals(Outcome.REJECTED, apply(new PatchService(journal), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertEquals(0, endpoint.calls);
    }

    @Test void appliedJournalFailureDoesNotAcknowledgeOrRollback() {
        Journal journal = new Journal(); journal.fail = State.APPLIED;
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(new PatchService(journal), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertEquals(2, endpoint.value[0][0]); assertEquals(0, endpoint.acks); assertEquals(1, endpoint.calls);
    }

    @Test void acknowledgementFailureRemainsNonterminal() {
        Journal journal = new Journal();
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1)) {
            public CompletableFuture<Void> acknowledgeCommit() { return failed(new IllegalStateException("cannot release backend recovery")); }
        };
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(new PatchService(journal), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertFalse(journal.phases.contains(State.COMPLETED)); assertEquals(2, endpoint.value[0][0]);
    }

    @Test void terminalJournalFailureNeverReappliesOrRestoresSnapshot() {
        Journal journal = new Journal(); journal.fail = State.COMPLETED;
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(new PatchService(journal), UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        assertEquals(2, endpoint.value[0][0]); assertEquals(1, endpoint.calls);
    }

    @Test void replayCompletedIdReturnsOutcomeWithoutMutatingAgain() {
        Journal journal = new Journal(); UUID id = UUID.randomUUID();
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        assertEquals(Outcome.COMPLETED, apply(new PatchService(journal), id, endpoint, 1, 2).join().outcome());
        endpoint.value = inventory(InventoryKind.MAIN, 3);
        assertEquals(Outcome.COMPLETED, apply(new PatchService(journal), id, endpoint, 1, 2).join().outcome());
        assertEquals(3, endpoint.value[0][0]); assertEquals(1, endpoint.calls);
    }

    @Test void interruptedRecordNeverReplays() {
        Journal journal = new Journal(); UUID id = UUID.randomUUID();
        journal.records.put(id, new PatchRecord(id, ACTOR, PatchType.GIVE, TARGET, InventoryKind.MAIN,
                "target:main", inventory(InventoryKind.MAIN, 1), inventory(InventoryKind.MAIN, 2), State.APPLIED, "", 0));
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(new PatchService(journal), id, endpoint, 1, 2).join().outcome());
        assertEquals(0, endpoint.calls);
    }

    @Test void reusedIdWithDifferentRequestIsRejected() {
        Journal journal = new Journal(); UUID id = UUID.randomUUID();
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1)); PatchService service = new PatchService(journal);
        apply(service, id, endpoint, 1, 2).join();
        assertEquals(Outcome.REJECTED, apply(service, id, endpoint, 1, 3).join().outcome());
        assertEquals(1, endpoint.calls);
    }

    @Test void inFlightDuplicateDoesNotApplyTwiceAndStopWaitsAfterCallerCancel() {
        Journal journal = new Journal(); UUID id = UUID.randomUUID(); CompletableFuture<Boolean> gate = new CompletableFuture<>();
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1)) {
            public CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after) {
                calls++; return gate.thenApply(success -> { if (success) value = cloneInventory(after); return success; });
            }
        };
        PatchService service = new PatchService(journal);
        CompletableFuture<PatchService.Result> result = apply(service, id, endpoint, 1, 2);
        assertSame(result, apply(service, id, endpoint, 1, 2));
        assertEquals(Outcome.REJECTED, apply(service, id, endpoint, 1, 3).join().outcome());
        result.cancel(false); CompletableFuture<Void> drain = service.stopAccepting();
        assertFalse(drain.isDone());
        assertEquals(Outcome.REJECTED, apply(service, UUID.randomUUID(), endpoint, 1, 2).join().outcome());
        gate.complete(true);
        assertTrue(drain.isDone()); assertEquals(1, endpoint.calls); assertEquals(State.COMPLETED, journal.records.get(id).state());
    }

    @Test void snapshotsAreDeepClonedAcrossCallerEndpointAndAuditBoundaries() {
        Journal journal = new Journal(); UUID id = UUID.randomUUID();
        byte[][] before = inventory(InventoryKind.MAIN, 1), after = inventory(InventoryKind.MAIN, 2);
        Endpoint endpoint = new Endpoint(before) {
            public CompletableFuture<Boolean> compareAndSet(byte[][] expected, byte[][] replacement) {
                expected[0][0] = 8; replacement[0][0] = 9; return CompletableFuture.completedFuture(false);
            }
        };
        PatchService.Result result = new PatchService(journal).apply(id, ACTOR, PatchType.GIVE, TARGET, InventoryKind.MAIN, endpoint, before, after).join();
        before[0][0] = 7; after[0][0] = 6; byte[][] exposed = result.record().before(); exposed[0][0] = 5;
        assertEquals(1, result.record().before()[0][0]); assertEquals(2, result.record().after()[0][0]);
        assertEquals(1, journal.records.get(id).before()[0][0]);
    }

    @Test void invalidSnapshotShapeAndOversizeAreRejectedBeforeJournal() {
        assertThrows(IllegalArgumentException.class, () -> new PatchRecord(UUID.randomUUID(), ACTOR, PatchType.GIVE, TARGET, InventoryKind.MAIN,
                "target", new byte[41][], new byte[43][], State.PREPARED, "", 0));
        byte[][] large = new byte[43][]; large[0] = new byte[PatchRecord.MAX_ITEM_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> new PatchRecord(UUID.randomUUID(), ACTOR, PatchType.GIVE, TARGET, InventoryKind.MAIN,
                "target", large, new byte[43][], State.PREPARED, "", 0));
    }

    @Test void emptyRepresentationNormalizesWithoutChangingSlotCount() {
        byte[][] empty = new byte[27][]; empty[0] = new byte[0];
        PatchRecord record = new PatchRecord(UUID.randomUUID(), PatchRecord.CONSOLE_ACTOR, PatchType.CLEAR, TARGET, InventoryKind.ENDER,
                "target", empty, empty, State.PREPARED, "", 0);
        assertNull(record.before()[0]); assertEquals(27, record.after().length);
    }

    @Test void simultaneousDifferentIdsCannotOverwriteChangedTargetSnapshot() throws Exception {
        Journal journal = new Journal(); Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1));
        PatchService service = new PatchService(journal); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PatchService.Result> first = pool.submit(() -> { start.await(); return apply(service, UUID.randomUUID(), endpoint, 1, 2).join(); });
            Future<PatchService.Result> second = pool.submit(() -> { start.await(); return apply(service, UUID.randomUUID(), endpoint, 1, 3).join(); });
            start.countDown();
            Set<Outcome> outcomes = new HashSet<>(Arrays.asList(first.get(5, TimeUnit.SECONDS).outcome(), second.get(5, TimeUnit.SECONDS).outcome()));
            assertEquals(new HashSet<>(Arrays.asList(Outcome.COMPLETED, Outcome.CONFLICT)), outcomes);
        } finally { pool.shutdownNow(); }
    }

    @Test void quarantineWriteFailureStillResolvesObservationAndDrain() {
        Journal journal = new Journal(); journal.fail = State.RECOVERY_REQUIRED;
        Endpoint endpoint = new Endpoint(inventory(InventoryKind.MAIN, 1)) {
            public CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after) {
                value = cloneInventory(after); throw new IllegalStateException("uncertain failure");
            }
        };
        PatchService service = new PatchService(journal); UUID id = UUID.randomUUID();
        assertEquals(Outcome.RECOVERY_REQUIRED, apply(service, id, endpoint, 1, 2).join().outcome());
        assertEquals(State.PREPARED, journal.records.get(id).state()); assertTrue(service.stopAccepting().isDone());
        assertEquals(2, endpoint.value[0][0]);
    }

    private static CompletableFuture<PatchService.Result> apply(PatchService service, UUID id, Endpoint endpoint, int before, int after) {
        return service.apply(id, ACTOR, PatchType.GIVE, TARGET, InventoryKind.MAIN, endpoint, inventory(InventoryKind.MAIN, before), inventory(InventoryKind.MAIN, after));
    }
    static byte[][] inventory(InventoryKind kind, int first) { byte[][] value = new byte[kind.slots()][]; value[0] = new byte[] {(byte) first}; return value; }
    static byte[][] cloneInventory(byte[][] input) {
        byte[][] clone = new byte[input.length][];
        for (int i = 0; i < clone.length; i++) if (input[i] != null) clone[i] = input[i].clone();
        return clone;
    }
    static class Endpoint implements PatchEndpoint {
        byte[][] value; int calls, acks;
        Endpoint(byte[][] initial) { value = cloneInventory(initial); }
        public String description() { return "target:main"; }
        public synchronized CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after) {
            calls++;
            if (!Arrays.deepEquals(value, before)) return CompletableFuture.completedFuture(false);
            value = cloneInventory(after); return CompletableFuture.completedFuture(true);
        }
        public CompletableFuture<Void> acknowledgeCommit() { acks++; return CompletableFuture.completedFuture(null); }
    }
    static class Journal implements PatchJournal {
        final Map<UUID, PatchRecord> records = new ConcurrentHashMap<>();
        final List<State> phases = Collections.synchronizedList(new ArrayList<>()); State fail;
        public CompletableFuture<Optional<PatchRecord>> load(UUID id) { return CompletableFuture.completedFuture(Optional.ofNullable(records.get(id))); }
        public CompletableFuture<List<PatchRecord>> loadUnresolved() {
            List<PatchRecord> result = new ArrayList<>();
            for (PatchRecord record : records.values()) if (!record.state().terminal()) result.add(record);
            return CompletableFuture.completedFuture(result);
        }
        public CompletableFuture<Void> write(PatchRecord record) {
            if (record.state() == fail) return failed(new IllegalStateException("disk failure"));
            records.put(record.id(), record); phases.add(record.state()); return CompletableFuture.completedFuture(null);
        }
    }
    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }
}
