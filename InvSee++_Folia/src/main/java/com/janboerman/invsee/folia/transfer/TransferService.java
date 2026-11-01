package com.janboerman.invsee.folia.transfer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import static com.janboerman.invsee.folia.transfer.TransferRecord.State;

/**
 * Owner-dispatched, journaled transfer with validated source and destination snapshots. Never
 * accesses Bukkit objects itself. A journal and Minecraft player saves cannot be
 * committed atomically: interrupted/nonterminal records require human review,
 * not automatic redelivery (which could duplicate a committed stack).
 */
public final class TransferService {
    public enum Outcome { COMPLETED, RETURNED, CONFLICT, REJECTED, RECOVERY_REQUIRED }
    public static final class Result {
        private final Outcome outcome;
        private final TransferRecord record;
        Result(Outcome outcome, TransferRecord record) { this.outcome = outcome; this.record = record; }
        public Outcome outcome() { return outcome; }
        public TransferRecord record() { return record; }
    }

    private final TransferJournal journal;
    private final ConcurrentHashMap<UUID, CompletableFuture<Result>> pending = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private boolean accepting = true;
    private int inFlight;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    public TransferService(TransferJournal journal) { this.journal = Objects.requireNonNull(journal); }

    public CompletableFuture<Result> transfer(UUID id, SlotEndpoint source, SlotEndpoint destination, StackValue expectedSource) {
        return transfer(id, source, destination, expectedSource, StackValue.EMPTY, expectedSource);
    }

    /**
     * Caller must construct a valid partition: expectedSource = remainder + item,
     * preserving metadata. This opaque-byte core cannot verify stack quantities.
     */
    public CompletableFuture<Result> transfer(UUID id, SlotEndpoint source, SlotEndpoint destination,
                                             StackValue expectedSource, StackValue remainder, StackValue item) {
        return transfer(id, source, destination, expectedSource, remainder, item, StackValue.EMPTY, item);
    }

    /** Caller validates metadata/amount conservation and maximum stack size for a merge. */
    public CompletableFuture<Result> transfer(UUID id, SlotEndpoint source, SlotEndpoint destination,
                                             StackValue expectedSource, StackValue remainder, StackValue item,
                                             StackValue expectedDestination, StackValue replacementDestination) {
        Objects.requireNonNull(expectedDestination); Objects.requireNonNull(replacementDestination);
        if (replacementDestination.isEmpty()) throw new IllegalArgumentException("Destination replacement cannot be empty");
        Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(destination);
        Objects.requireNonNull(expectedSource); Objects.requireNonNull(remainder); Objects.requireNonNull(item);
        if (expectedSource.isEmpty() || item.isEmpty()) throw new IllegalArgumentException("Cannot transfer empty source/item");
        if (source == destination || source.description().equals(destination.description()))
            throw new IllegalArgumentException("Source and destination must differ");
        TransferRecord record = new TransferRecord(id, source.description(), destination.description(), item,
                expectedSource, remainder, expectedDestination, replacementDestination, State.PREPARED, "", System.currentTimeMillis());
        CompletableFuture<Result> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            CompletableFuture<Result> previous = pending.get(id);
            if (previous != null) return previous;
            if (!accepting) return CompletableFuture.completedFuture(new Result(Outcome.REJECTED, record));
            pending.put(id, result);
            inFlight++;
        }
        Context context = new Context(record);
        attempt(() -> journal.load(id)).thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(fromExisting(existing.get()));
            return write(context, State.PREPARED).thenCompose(unused -> {
                context.prepared = true;
                return attempt(() -> source.compareAndSet(expectedSource, remainder));
            }).thenCompose(reserved -> {
                if (!reserved) return write(context, State.CONFLICT).thenApply(unused -> new Result(Outcome.CONFLICT, context.record));
                return write(context, State.RESERVED)
                        .thenCompose(unused -> attempt(source::acknowledgeCommit))
                        .thenCompose(unused -> write(context, State.DELIVERING))
                        .thenCompose(unused -> attempt(() -> destination.compareAndSet(expectedDestination, replacementDestination)))
                        .thenCompose(delivered -> {
                            if (delivered) return write(context, State.DELIVERED)
                                    .thenCompose(unused -> attempt(destination::acknowledgeCommit))
                                    .thenCompose(unused -> write(context, State.COMPLETED))
                                    .thenApply(unused -> new Result(Outcome.COMPLETED, context.record));
                            return write(context, State.ROLLING_BACK)
                                    .thenCompose(unused -> attempt(() -> source.compareAndSet(remainder, expectedSource)))
                                    .thenCompose(returned -> {
                                        if (returned) return write(context, State.ROLLED_BACK)
                                                .thenCompose(unused -> attempt(source::acknowledgeCommit))
                                                .thenCompose(unused -> write(context, State.RETURNED))
                                                .thenApply(unused -> new Result(Outcome.RETURNED, context.record));
                                        return holdEscrow(context, "Destination rejected and source changed; undelivered item is held in escrow");
                                    });
                        });
            });
        }).whenComplete((value, error) -> {
            if (error == null) complete(id, result, value);
            else if (!context.prepared) complete(id, result, new Result(Outcome.REJECTED, context.record));
            else quarantine(context, "Uncertain operation at " + context.record.state() + "; inspect both inventories before recovery")
                    .whenComplete((recovery, ignored) -> complete(id, result, recovery));
        });
        return result;
    }

    /** Stop new admission; resolves when protocol work (not caller observations) has finished. */
    public CompletableFuture<Void> stopAccepting() {
        boolean drained;
        synchronized (lifecycle) {
            accepting = false;
            drained = inFlight == 0;
        }
        if (drained) stopped.complete(null);
        return stopped.thenApply(unused -> null);
    }

    /** Completion after stopAccepting() and all accepted operations have journaled their final outcome. */
    public CompletableFuture<Void> drained() { return stopped.thenApply(unused -> null); }

    private void complete(UUID id, CompletableFuture<Result> result, Result value) {
        boolean drained;
        synchronized (lifecycle) {
            if (pending.remove(id, result)) inFlight--;
            drained = !accepting && inFlight == 0;
        }
        result.complete(value);
        if (drained) stopped.complete(null);
    }

    private CompletableFuture<Void> write(Context context, State state) {
        context.record = context.record.withState(state, "");
        return attempt(() -> journal.write(context.record));
    }

    private CompletableFuture<Result> holdEscrow(Context context, String reason) {
        context.record = context.record.withState(State.ESCROW_HELD, reason);
        return attempt(() -> journal.write(context.record)).handle((unused, error) -> new Result(Outcome.RECOVERY_REQUIRED, context.record));
    }

    private CompletableFuture<Result> quarantine(Context context, String reason) {
        context.record = context.record.withState(State.RECOVERY_REQUIRED, reason);
        // A failed quarantine write still leaves the previously durable
        // nonterminal state. It must never trigger an automatic replay.
        return attempt(() -> journal.write(context.record)).handle((unused, error) -> new Result(Outcome.RECOVERY_REQUIRED, context.record));
    }

    private static Result fromExisting(TransferRecord record) {
        switch (record.state()) {
            case COMPLETED: return new Result(Outcome.COMPLETED, record);
            case RETURNED: return new Result(Outcome.RETURNED, record);
            case CONFLICT: return new Result(Outcome.CONFLICT, record);
            default: return new Result(Outcome.RECOVERY_REQUIRED, record);
        }
    }

    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> work) {
        try { return Objects.requireNonNull(work.get(), "operation future"); }
        catch (Throwable error) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
    }
    private static final class Context {
        volatile TransferRecord record;
        volatile boolean prepared;
        Context(TransferRecord record) { this.record = record; }
    }
}
