package com.janboerman.invsee.folia.transfer;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.janboerman.invsee.folia.transfer.TransferService.Outcome;
import static com.janboerman.invsee.folia.transfer.TransferRecord.State;

class TransferServiceTest {
    private static final StackValue ITEM = new StackValue(new byte[] {1, 64});
    private static final StackValue HALF = new StackValue(new byte[] {1, 32});
    private static final StackValue OTHER = new StackValue(new byte[] {2, 5});

    @Test void completeMoveHasWriteAheadPhases() {
        MemoryJournal journal = new MemoryJournal();
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.COMPLETED, new TransferService(journal).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(StackValue.EMPTY, source.value); assertEquals(ITEM, destination.value);
        assertEquals(Arrays.asList(State.PREPARED, State.RESERVED, State.DELIVERING, State.DELIVERED, State.COMPLETED), journal.phases);
    }

    @Test void splitPreservesRemainder() {
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.COMPLETED, new TransferService(new MemoryJournal())
                .transfer(UUID.randomUUID(), source, destination, ITEM, HALF, HALF).join().outcome());
        assertEquals(HALF, source.value); assertEquals(HALF, destination.value);
    }

    @Test void changedSourceDoesNotTouchDestination() {
        Endpoint source = new Endpoint("source", OTHER), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.CONFLICT, new TransferService(new MemoryJournal()).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(OTHER, source.value); assertEquals(0, destination.calls);
    }

    @Test void occupiedDestinationReturnsExactOriginal() {
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", OTHER);
        assertEquals(Outcome.RETURNED, new TransferService(new MemoryJournal()).transfer(UUID.randomUUID(), source, destination, ITEM, HALF, HALF).join().outcome());
        assertEquals(ITEM, source.value); assertEquals(OTHER, destination.value);
    }

    @Test void failedRollbackRetainsEscrowRecord() {
        MemoryJournal journal = new MemoryJournal();
        Endpoint source = new Endpoint("source", ITEM);
        Endpoint destination = new Endpoint("destination", OTHER) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                source.value = OTHER; return CompletableFuture.completedFuture(false);
            }
        };
        TransferService.Result result = new TransferService(journal).transfer(UUID.randomUUID(), source, destination, ITEM).join();
        assertEquals(Outcome.RECOVERY_REQUIRED, result.outcome());
        assertEquals(ITEM, result.record().item()); assertEquals(OTHER, source.value);
        assertEquals(State.ESCROW_HELD, journal.records.get(result.record().operationId()).state());
    }

    @Test void uncertainDestinationSuccessNeverRollsBackOrDuplicates() {
        Endpoint source = new Endpoint("source", ITEM);
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                value = update; return failed(new IllegalStateException("disconnected after commit"));
            }
        };
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(new MemoryJournal()).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(StackValue.EMPTY, source.value); assertEquals(ITEM, destination.value); assertEquals(1, source.calls);
    }

    @Test void reservationExceptionDoesNotDeliver() {
        Endpoint source = new Endpoint("source", ITEM) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                return failed(new IllegalStateException("retired"));
            }
        };
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(new MemoryJournal()).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(0, destination.calls);
    }

    @Test void initialJournalFailurePreventsAllMutation() {
        MemoryJournal journal = new MemoryJournal(); journal.fail = State.PREPARED;
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.REJECTED, new TransferService(journal).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(0, source.calls); assertEquals(0, destination.calls);
    }

    @Test void journalFailureAfterReservationQuarantinesWithoutDelivery() {
        MemoryJournal journal = new MemoryJournal(); journal.fail = State.RESERVED;
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(journal).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(StackValue.EMPTY, source.value); assertEquals(0, destination.calls);
    }

    @Test void duplicateIdDoesNotTransferAgainAfterCompletionOrRestart() {
        MemoryJournal journal = new MemoryJournal(); UUID id = UUID.randomUUID();
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        TransferService service = new TransferService(journal);
        service.transfer(id, source, destination, ITEM).join();
        source.value = ITEM; destination.value = StackValue.EMPTY;
        assertEquals(Outcome.COMPLETED, new TransferService(journal).transfer(id, source, destination, ITEM).join().outcome());
        assertEquals(ITEM, source.value); assertEquals(StackValue.EMPTY, destination.value);
    }

    @Test void interruptedRestartIsNotReplayed() {
        MemoryJournal journal = new MemoryJournal(); UUID id = UUID.randomUUID();
        journal.records.put(id, new TransferRecord(id, "source", "destination", ITEM, State.DELIVERING, "", 0));
        Endpoint source = new Endpoint("source", StackValue.EMPTY), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(journal).transfer(id, source, destination, ITEM).join().outcome());
        assertEquals(0, source.calls); assertEquals(0, destination.calls);
    }

    @Test void duplicateWhilePendingSharesOneOperationAndCancellationDoesNotReplay() {
        MemoryJournal journal = new MemoryJournal(); UUID id = UUID.randomUUID();
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        Endpoint source = new Endpoint("source", ITEM) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                calls++; return gate.thenApply(ok -> { if (ok) value = update; return ok; });
            }
        };
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        TransferService service = new TransferService(journal);
        CompletableFuture<TransferService.Result> first = service.transfer(id, source, destination, ITEM);
        assertSame(first, service.transfer(id, source, destination, ITEM));
        first.cancel(false);
        gate.complete(true);
        assertEquals(1, source.calls); assertEquals(ITEM, destination.value);
        assertEquals(State.COMPLETED, journal.records.get(id).state());
    }

    @Test void concurrentTransfersOnlyOneCanReserveSameSource() throws Exception {
        MemoryJournal journal = new MemoryJournal();
        Endpoint source = new Endpoint("source", ITEM), one = new Endpoint("one", StackValue.EMPTY), two = new Endpoint("two", StackValue.EMPTY);
        TransferService service = new TransferService(journal);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<TransferService.Result> a = CompletableFuture.supplyAsync(() -> service.transfer(UUID.randomUUID(), source, one, ITEM).join(), executor);
            CompletableFuture<TransferService.Result> b = CompletableFuture.supplyAsync(() -> service.transfer(UUID.randomUUID(), source, two, ITEM).join(), executor);
            Set<Outcome> outcomes = new HashSet<>(Arrays.asList(a.get(5, TimeUnit.SECONDS).outcome(), b.get(5, TimeUnit.SECONDS).outcome()));
            assertEquals(new HashSet<>(Arrays.asList(Outcome.COMPLETED, Outcome.CONFLICT)), outcomes);
            assertTrue(one.value.isEmpty() ^ two.value.isEmpty());
        } finally { executor.shutdownNow(); }
    }

    @Test void acknowledgementsOccurAfterNonterminalDurableJournalAndBeforeTerminal() {
        MemoryJournal journal = new MemoryJournal(); UUID id = UUID.randomUUID();
        Endpoint source = new Endpoint("source", ITEM) {
            @Override public CompletableFuture<Void> acknowledgeCommit() {
                assertEquals(State.RESERVED, journal.records.get(id).state());
                return CompletableFuture.completedFuture(null);
            }
        };
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY) {
            @Override public CompletableFuture<Void> acknowledgeCommit() {
                assertEquals(State.DELIVERED, journal.records.get(id).state());
                return CompletableFuture.completedFuture(null);
            }
        };
        assertEquals(Outcome.COMPLETED, new TransferService(journal).transfer(id, source, destination, ITEM).join().outcome());
    }

    @Test void acknowledgementFailureDoesNotMarkTerminalOrReturnDeliveredStack() {
        MemoryJournal journal = new MemoryJournal(); UUID id = UUID.randomUUID();
        Endpoint source = new Endpoint("source", ITEM);
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY) {
            @Override public CompletableFuture<Void> acknowledgeCommit() {
                return failed(new IllegalStateException("cannot acknowledge"));
            }
        };
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(journal).transfer(id, source, destination, ITEM).join().outcome());
        assertEquals(StackValue.EMPTY, source.value); assertEquals(ITEM, destination.value);
        assertFalse(journal.phases.contains(State.COMPLETED));
    }

    @Test void finalJournalFailureNeverDuplicatesDeliveredItem() {
        MemoryJournal journal = new MemoryJournal(); journal.fail = State.COMPLETED;
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(journal).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(StackValue.EMPTY, source.value); assertEquals(ITEM, destination.value);
        assertEquals(1, source.calls);
    }

    @Test void sourceAcknowledgementFailureStopsBeforeDelivery() {
        Endpoint source = new Endpoint("source", ITEM) {
            @Override public CompletableFuture<Void> acknowledgeCommit() {
                return failed(new IllegalStateException("cannot acknowledge source"));
            }
        };
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.RECOVERY_REQUIRED, new TransferService(new MemoryJournal()).transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        assertEquals(0, destination.calls);
    }

    @Test void stoppingWaitsForProtocolEvenWhenCallerCancelledObservation() {
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        Endpoint source = new Endpoint("source", ITEM) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                return gate.thenApply(ok -> { if (ok) value = update; return ok; });
            }
        };
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        TransferService service = new TransferService(new MemoryJournal());
        service.transfer(UUID.randomUUID(), source, destination, ITEM).cancel(false);
        CompletableFuture<Void> stopped = service.stopAccepting();
        assertFalse(stopped.isDone());
        assertEquals(Outcome.REJECTED, service.transfer(UUID.randomUUID(), source, destination, ITEM).join().outcome());
        gate.complete(true);
        assertTrue(stopped.isDone());
        assertEquals(ITEM, destination.value);
    }

    @Test void mergeUsesExactExpectedDestinationAndRecordsBothSides() {
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", HALF);
        TransferService.Result result = new TransferService(new MemoryJournal())
                .transfer(UUID.randomUUID(), source, destination, ITEM, HALF, HALF, HALF, ITEM).join();
        assertEquals(Outcome.COMPLETED, result.outcome());
        assertEquals(HALF, source.value); assertEquals(ITEM, destination.value);
        assertEquals(HALF, result.record().destinationBefore());
        assertEquals(ITEM, result.record().destinationAfter());
    }

    @Test void changedMergeDestinationReturnsReservationWithoutOverwritingIt() {
        Endpoint source = new Endpoint("source", ITEM), destination = new Endpoint("destination", OTHER);
        TransferService.Result result = new TransferService(new MemoryJournal())
                .transfer(UUID.randomUUID(), source, destination, ITEM, HALF, HALF, HALF, ITEM).join();
        assertEquals(Outcome.RETURNED, result.outcome());
        assertEquals(ITEM, source.value); assertEquals(OTHER, destination.value);
    }

    static class Endpoint implements SlotEndpoint {
        final String name; volatile StackValue value; int calls;
        Endpoint(String name, StackValue value) { this.name = name; this.value = value; }
        public String description() { return name; }
        public CompletableFuture<StackValue> read() { return CompletableFuture.completedFuture(value); }
        public synchronized CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
            calls++;
            if (!value.equals(expected)) return CompletableFuture.completedFuture(false);
            value = update; return CompletableFuture.completedFuture(true);
        }
    }
    static class MemoryJournal implements TransferJournal {
        final Map<UUID, TransferRecord> records = new ConcurrentHashMap<>();
        final List<State> phases = Collections.synchronizedList(new ArrayList<>());
        State fail;
        public CompletableFuture<Optional<TransferRecord>> load(UUID id) { return CompletableFuture.completedFuture(Optional.ofNullable(records.get(id))); }
        public CompletableFuture<List<TransferRecord>> loadAll() { return CompletableFuture.completedFuture(new ArrayList<>(records.values())); }
        public CompletableFuture<Void> write(TransferRecord record) {
            if (record.state() == fail) return failed(new IllegalStateException("disk unavailable"));
            records.put(record.operationId(), record); phases.add(record.state()); return CompletableFuture.completedFuture(null);
        }
    }
    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }
}
