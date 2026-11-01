package com.janboerman.invsee.folia.patch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import static com.janboerman.invsee.folia.patch.PatchRecord.State;

/**
 * Single-target, whole-managed-inventory write-ahead protocol. No automatic rollback/replay.
 * The adapter must bind the supplied target UUID/kind to the endpoint it constructs.
 */
public final class PatchService {
    public enum Outcome { COMPLETED, CONFLICT, REJECTED, RECOVERY_REQUIRED }
    public static final class Result {
        private final Outcome outcome;
        private final PatchRecord record;
        private Result(Outcome outcome, PatchRecord record) { this.outcome = outcome; this.record = record; }
        public Outcome outcome() { return outcome; }
        public PatchRecord record() { return record; }
    }
    private final PatchJournal journal;
    private final Object lifecycle = new Object();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean accepting = true;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    public PatchService(PatchJournal journal) { this.journal = Objects.requireNonNull(journal); }

    public CompletableFuture<Result> apply(UUID id, UUID actor, PatchType type, UUID target, InventoryKind kind,
                                           PatchEndpoint endpoint, byte[][] before, byte[][] after) {
        Objects.requireNonNull(endpoint);
        PatchRecord request = new PatchRecord(id, actor, type, target, kind, endpoint.description(),
                before, after, State.PREPARED, "", System.currentTimeMillis());
        Pending operation = new Pending(request);
        synchronized (lifecycle) {
            Pending existing = pending.get(id);
            if (existing != null) return existing.request.sameRequest(request) ? existing.result : rejected(request, "Operation id is already used for another patch");
            if (!accepting) return rejected(request, "Patch service is stopping");
            pending.put(id, operation);
        }
        Context context = new Context(request);
        attempt(() -> journal.load(id)).thenCompose(existing -> {
            if (existing.isPresent()) {
                PatchRecord persisted = existing.get();
                if (!persisted.sameRequest(request)) return rejected(request, "Operation id is already used for another patch");
                return CompletableFuture.completedFuture(fromExisting(persisted));
            }
            return write(context, State.PREPARED).thenCompose(unused -> {
                context.prepared = true;
                return attempt(() -> endpoint.compareAndSet(request.before(), request.after()));
            }).thenCompose(applied -> {
                if (!applied) return write(context, State.CONFLICT).thenApply(unused -> new Result(Outcome.CONFLICT, context.record));
                return write(context, State.APPLIED)
                        .thenCompose(unused -> attempt(endpoint::acknowledgeCommit))
                        .thenCompose(unused -> write(context, State.COMPLETED))
                        .thenApply(unused -> new Result(Outcome.COMPLETED, context.record));
            });
        }).whenComplete((result, error) -> {
            if (error == null) complete(id, operation, result);
            else if (!context.prepared) complete(id, operation, new Result(Outcome.REJECTED,
                    request.withState(State.PREPARED, "Unable to durably prepare patch; no mutation was requested")));
            else {
                context.record = context.record.withState(State.RECOVERY_REQUIRED,
                        "Uncertain outcome after " + context.record.state() + "; inspect current inventory and before/after snapshots; never blindly replay");
                attempt(() -> journal.write(context.record)).whenComplete((unused, ignored) ->
                        complete(id, operation, new Result(Outcome.RECOVERY_REQUIRED, context.record)));
            }
        });
        return operation.result;
    }

    public CompletableFuture<Void> stopAccepting() {
        boolean drained;
        synchronized (lifecycle) { accepting = false; drained = pending.isEmpty(); }
        if (drained) stopped.complete(null);
        return drained();
    }
    /** Resolves only after stopAccepting and all internal protocol work, even if callers canceled. */
    public CompletableFuture<Void> drained() { return stopped.thenApply(unused -> null); }

    private void complete(UUID id, Pending operation, Result result) {
        boolean drained;
        synchronized (lifecycle) {
            pending.remove(id, operation);
            drained = !accepting && pending.isEmpty();
        }
        operation.result.complete(result);
        if (drained) stopped.complete(null);
    }
    private CompletableFuture<Void> write(Context context, State state) {
        context.record = context.record.withState(state, "");
        return attempt(() -> journal.write(context.record));
    }
    private static CompletableFuture<Result> rejected(PatchRecord record, String reason) {
        return CompletableFuture.completedFuture(new Result(Outcome.REJECTED, record.withState(State.PREPARED, reason)));
    }
    private static Result fromExisting(PatchRecord record) {
        if (record.state() == State.COMPLETED) return new Result(Outcome.COMPLETED, record);
        if (record.state() == State.CONFLICT) return new Result(Outcome.CONFLICT, record);
        return new Result(Outcome.RECOVERY_REQUIRED, record);
    }
    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> work) {
        try { return Objects.requireNonNull(work.get(), "operation future"); }
        catch (Throwable error) {
            CompletableFuture<T> failed = new CompletableFuture<>(); failed.completeExceptionally(error); return failed;
        }
    }
    private static final class Pending {
        final PatchRecord request;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        Pending(PatchRecord request) { this.request = request; }
    }
    private static final class Context {
        volatile PatchRecord record;
        volatile boolean prepared;
        Context(PatchRecord record) { this.record = record; }
    }
}
