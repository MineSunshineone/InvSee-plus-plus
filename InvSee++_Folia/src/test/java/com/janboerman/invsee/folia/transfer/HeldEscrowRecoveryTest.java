package com.janboerman.invsee.folia.transfer;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.janboerman.invsee.folia.transfer.TransferRecord.State;
import static com.janboerman.invsee.folia.transfer.TransferService.Outcome;
import static com.janboerman.invsee.folia.transfer.TransferServiceTest.*;

class HeldEscrowRecoveryTest {
    private static final StackValue ITEM = new StackValue(new byte[] {1, 32});
    private static final StackValue OTHER = new StackValue(new byte[] {2, 4});

    @Test void heldEscrowCanBeClaimedExactlyOnceAndRepeatedClaimRefused() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        assertEquals(Outcome.COMPLETED, recovery.claim(original, UUID.randomUUID(), destination).join().outcome());
        assertEquals(ITEM, destination.value);
        assertEquals(State.ESCROW_RECOVERED, journal.records.get(original).state());
        Endpoint another = new Endpoint("another", StackValue.EMPTY);
        assertThrows(CompletionException.class, () -> recovery.claim(original, UUID.randomUUID(), another).join());
        assertEquals(0, another.calls);
    }

    @Test void rejectedDestinationReturnsAuthorityToOriginalHeldRecord() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        assertEquals(Outcome.RETURNED, recovery.claim(original, UUID.randomUUID(), new Endpoint("full", OTHER)).join().outcome());
        assertEquals(State.ESCROW_HELD, journal.records.get(original).state());
        Endpoint empty = new Endpoint("empty", StackValue.EMPTY);
        assertEquals(Outcome.COMPLETED, recovery.claim(original, UUID.randomUUID(), empty).join().outcome());
        assertEquals(ITEM, empty.value);
    }

    @Test void uncertainDestinationMustNeverRestoreOriginalHeldAuthority() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                value = update; return failed(new IllegalStateException("disconnect after delivery"));
            }
        };
        assertEquals(Outcome.RECOVERY_REQUIRED, recovery.claim(original, UUID.randomUUID(), destination).join().outcome());
        assertEquals(State.CLAIMED, journal.records.get(original).state()); assertEquals(ITEM, destination.value);
        assertThrows(CompletionException.class, () -> new HeldEscrowRecovery(journal, new TransferService(journal))
                .claim(original, UUID.randomUUID(), new Endpoint("other", StackValue.EMPTY)).join());
    }

    @Test void ambiguousOriginalIsNeverClaimable() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        journal.records.put(original, journal.records.get(original).withState(State.RECOVERY_REQUIRED, "uncertain"));
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        assertThrows(CompletionException.class, () -> new HeldEscrowRecovery(journal, new TransferService(journal))
                .claim(original, UUID.randomUUID(), destination).join());
        assertEquals(0, destination.calls);
    }

    @Test void childPreparedBeforeOriginalConsumptionAndConcurrentClaimRejected() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal), child = UUID.randomUUID();
        CompletableFuture<Boolean> gate = new CompletableFuture<>();
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY) {
            @Override public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                assertEquals(State.CLAIMED, journal.records.get(original).state());
                assertEquals(State.DELIVERING, journal.records.get(child).state());
                return gate.thenApply(success -> { if (success) value = update; return success; });
            }
        };
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        CompletableFuture<TransferService.Result> first = recovery.claim(original, child, destination);
        first.cancel(false);
        assertThrows(CompletionException.class, () -> recovery.claim(original, UUID.randomUUID(), new Endpoint("other", StackValue.EMPTY)).join());
        gate.complete(true);
        assertEquals(State.ESCROW_RECOVERED, journal.records.get(original).state());
        assertTrue(journal.phases.indexOf(State.PREPARED) < journal.phases.indexOf(State.CLAIMING));
    }

    @Test void crashAfterOriginalClaimAndBeforeChildTerminalNeverRedelivers() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal), child = UUID.randomUUID();
        TransferRecord record = journal.records.get(original);
        journal.records.put(original, record.withState(State.CLAIMED, "recovery-child=" + child));
        journal.records.put(child, new TransferRecord(child, record.source() + ":escrow:" + original, "destination", ITEM, State.PREPARED, "", 0));
        HeldEscrowRecovery restarted = new HeldEscrowRecovery(journal, new TransferService(journal));
        assertEquals(State.CLAIMED, restarted.reconcile(original).join().state());
        assertThrows(CompletionException.class, () -> restarted.claim(original, UUID.randomUUID(), new Endpoint("other", StackValue.EMPTY)).join());
    }

    @Test void completedChildCanReconcileParentAfterCrashWithoutAnotherGrant() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        journal.fail = State.ESCROW_RECOVERED;
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        assertThrows(CompletionException.class, () -> recovery.claim(original, UUID.randomUUID(), destination).join());
        assertEquals(ITEM, destination.value); assertEquals(State.CLAIMED, journal.records.get(original).state());
        journal.fail = null;
        HeldEscrowRecovery restarted = new HeldEscrowRecovery(journal, new TransferService(journal));
        restarted.reconcileCompletedClaims().join();
        assertEquals(State.ESCROW_RECOVERED, journal.records.get(original).state());
        assertEquals(1, destination.calls);
    }

    @Test void claimingCrashRemainsQuarantined() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal);
        journal.fail = State.CLAIMED;
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        HeldEscrowRecovery recovery = new HeldEscrowRecovery(journal, new TransferService(journal));
        assertEquals(Outcome.RECOVERY_REQUIRED, recovery.claim(original, UUID.randomUUID(), destination).join().outcome());
        assertEquals(State.CLAIMING, journal.records.get(original).state()); assertEquals(0, destination.calls);
        assertThrows(CompletionException.class, () -> recovery.claim(original, UUID.randomUUID(), destination).join());
    }

    @Test void alreadyUsedChildIdCannotAcknowledgeUnrelatedTransferAsRecovery() {
        MemoryJournal journal = new MemoryJournal(); UUID original = held(journal), child = UUID.randomUUID();
        journal.records.put(child, new TransferRecord(child, "unrelated", "another", ITEM, State.COMPLETED, "", 0));
        Endpoint destination = new Endpoint("destination", StackValue.EMPTY);
        assertThrows(CompletionException.class, () -> new HeldEscrowRecovery(journal, new TransferService(journal))
                .claim(original, child, destination).join());
        assertEquals(State.ESCROW_HELD, journal.records.get(original).state());
        assertEquals(0, destination.calls);
    }

    private static UUID held(MemoryJournal journal) {
        UUID original = UUID.randomUUID();
        journal.records.put(original, new TransferRecord(original, UUID.randomUUID() + ":main:0", UUID.randomUUID() + ":main:1", ITEM, State.ESCROW_HELD, "provably held", 0));
        return original;
    }
}
