package com.janboerman.invsee.folia.transfer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import static com.janboerman.invsee.folia.transfer.TransferRecord.State;

/**
 * Explicit operator recovery of PROVABLY undelivered escrow only. Ambiguous
 * RECOVERY_REQUIRED/PREPARED/DELIVERING records are never claimable. One instance
 * per journal/service is required. Claims use the normal write-ahead transfer
 * protocol; they are not direct item grants.
 */
public final class HeldEscrowRecovery {
    private final TransferJournal journal;
    private final TransferService transfers;
    private final ConcurrentHashMap<UUID, Object> claims = new ConcurrentHashMap<>();

    public HeldEscrowRecovery(TransferJournal journal, TransferService transfers) {
        this.journal = Objects.requireNonNull(journal);
        this.transfers = Objects.requireNonNull(transfers);
    }

    /** Destination must be empty; authorization/owner scheduling belongs to its adapter. */
    public CompletableFuture<TransferService.Result> claim(UUID originalId, UUID claimId, SlotEndpoint destination) {
        Objects.requireNonNull(originalId); Objects.requireNonNull(claimId); Objects.requireNonNull(destination);
        if (originalId.equals(claimId)) return failed(new IllegalArgumentException("Claim needs a new transaction id"));
        Object lock = new Object();
        if (claims.putIfAbsent(originalId, lock) != null)
            return failed(new IllegalStateException("An escrow claim is already in progress"));
        // Do not release the lock if a caller merely cancels its observation.
        CompletableFuture<TransferService.Result> observation = new CompletableFuture<>();
        attempt(() -> journal.load(originalId)).thenCompose(maybe -> {
            TransferRecord original = maybe.orElseThrow(() -> new IllegalArgumentException("Escrow record not found"));
            if (original.state() != State.ESCROW_HELD)
                throw new IllegalStateException("This record is not provably held escrow; inspect it manually");
            if (original.item().isEmpty()) throw new IllegalStateException("Escrow has no item");
            return attempt(() -> journal.load(claimId)).thenCompose(existingClaim -> {
                if (existingClaim.isPresent()) throw new IllegalArgumentException("Recovery transaction id is already used");
                return transfers.transfer(claimId, endpoint(original, claimId), destination, original.item());
            }).thenCompose(result -> {
                if (!result.record().source().equals(original.source() + ":escrow:" + originalId))
                    throw new IllegalStateException("Recovery transaction id belongs to another operation");
                if (result.outcome() != TransferService.Outcome.COMPLETED)
                    return CompletableFuture.completedFuture(result);
                return reconcile(originalId).thenApply(unused -> result);
            });
        }).whenComplete((result, error) -> {
            claims.remove(originalId, lock);
            if (error == null) observation.complete(result);
            else observation.completeExceptionally(error);
        });
        return observation;
    }

    /**
     * A crash after the child completed but before parent acknowledgement may
     * leave CLAIMED. Only a matching durable COMPLETED child can terminalize it;
     * this method never schedules an inventory operation or grants an item.
     */
    public CompletableFuture<TransferRecord> reconcile(UUID originalId) {
        return attempt(() -> journal.load(originalId)).thenCompose(maybe -> {
            TransferRecord original = maybe.orElseThrow(() -> new IllegalArgumentException("Escrow record not found"));
            if (original.state() != State.CLAIMED || !original.detail().startsWith("recovery-child="))
                return CompletableFuture.completedFuture(original);
            final UUID child;
            try { child = UUID.fromString(original.detail().substring("recovery-child=".length())); }
            catch (IllegalArgumentException malformed) { return failed(new IllegalStateException("Invalid recovery child link", malformed)); }
            return attempt(() -> journal.load(child)).thenCompose(childRecord -> {
                if (!childRecord.isPresent() || childRecord.get().state() != State.COMPLETED ||
                        !childRecord.get().source().equals(original.source() + ":escrow:" + originalId) ||
                        !childRecord.get().item().equals(original.item()) ||
                        !childRecord.get().sourceBefore().equals(original.item()) ||
                        !childRecord.get().sourceAfter().isEmpty())
                    return CompletableFuture.completedFuture(original);
                return attempt(() -> journal.load(originalId)).thenCompose(latest -> {
                    TransferRecord current = latest.orElseThrow(() -> new IllegalStateException("Escrow record disappeared"));
                    if (current.state() != State.CLAIMED || !current.detail().equals(original.detail()))
                        return CompletableFuture.completedFuture(current);
                    TransferRecord recovered = current.withState(State.ESCROW_RECOVERED, original.detail());
                    return attempt(() -> journal.write(recovered)).thenApply(unused -> recovered);
                });
            });
        });
    }

    /** Reconcile metadata-only acknowledgements before presenting recovery UI. */
    public CompletableFuture<Void> reconcileCompletedClaims() {
        return attempt(journal::loadUnresolved).thenCompose(records -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (TransferRecord record : records) {
                if (record.state() == State.CLAIMED)
                    chain = chain.thenCompose(unused -> reconcile(record.operationId()).thenApply(ignored -> null));
            }
            return chain;
        });
    }

    private SlotEndpoint endpoint(TransferRecord original, UUID childId) {
        final String link = "recovery-child=" + childId;
        return new SlotEndpoint() {
            public String description() { return original.source() + ":escrow:" + original.operationId(); }
            public CompletableFuture<StackValue> read() {
                return attempt(() -> journal.load(original.operationId())).thenApply(maybe ->
                        maybe.filter(record -> record.state() == State.ESCROW_HELD)
                                .map(TransferRecord::item).orElse(StackValue.EMPTY));
            }
            public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                return attempt(() -> journal.load(original.operationId())).thenCompose(maybe -> {
                    if (!maybe.isPresent()) return CompletableFuture.completedFuture(false);
                    TransferRecord current = maybe.get();
                    if (expected.equals(original.item()) && update.isEmpty()) {
                        if (current.state() != State.ESCROW_HELD || !current.item().equals(expected))
                            return CompletableFuture.completedFuture(false);
                        // Child PREPARED is already durable before this source CAS.
                        // A crash at CLAIMING refuses a second claim conservatively.
                        return attempt(() -> journal.write(current.withState(State.CLAIMING, link)))
                                .thenCompose(unused -> attempt(() -> journal.write(current.withState(State.CLAIMED, link))))
                                .thenApply(unused -> true);
                    }
                    if (expected.isEmpty() && update.equals(original.item())) {
                        if (current.state() != State.CLAIMED || !current.detail().equals(link))
                            return CompletableFuture.completedFuture(false);
                        // Called only after the destination CAS DEFINITELY returned
                        // false. An uncertain destination never invokes rollback.
                        return attempt(() -> journal.write(current.withState(State.ESCROW_HELD, "Returned by " + link)))
                                .thenApply(unused -> true);
                    }
                    return CompletableFuture.completedFuture(false);
                });
            }
        };
    }

    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> work) {
        try { return Objects.requireNonNull(work.get()); }
        catch (Throwable error) { return failed(error); }
    }
    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(error); return result;
    }
}
