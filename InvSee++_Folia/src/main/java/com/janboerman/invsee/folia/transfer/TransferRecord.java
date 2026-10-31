package com.janboerman.invsee.folia.transfer;

import java.util.Objects;
import java.util.UUID;

/** Durable audit/escrow record. Nonterminal records must be quarantined on restart. */
public final class TransferRecord {
    public enum State {
        PREPARED, RESERVED, DELIVERING, DELIVERED, ROLLING_BACK, ROLLED_BACK,
        COMPLETED, RETURNED, CONFLICT, RECOVERY_REQUIRED, ESCROW_HELD, CLAIMING, CLAIMED, ESCROW_RECOVERED;
        public boolean terminal() { return this == COMPLETED || this == RETURNED || this == CONFLICT || this == ESCROW_RECOVERED; }
    }
    private final UUID operationId;
    private final String source, destination, detail;
    private final StackValue item, sourceBefore, sourceAfter, destinationBefore, destinationAfter;
    private final State state;
    private final long updatedAt;

    public TransferRecord(UUID operationId, String source, String destination, StackValue item,
                          State state, String detail, long updatedAt) {
        this(operationId, source, destination, item, item, StackValue.EMPTY, state, detail, updatedAt);
    }

    public TransferRecord(UUID operationId, String source, String destination, StackValue item,
                          StackValue sourceBefore, StackValue sourceAfter, State state, String detail, long updatedAt) {
        this(operationId, source, destination, item, sourceBefore, sourceAfter, StackValue.EMPTY, item, state, detail, updatedAt);
    }

    public TransferRecord(UUID operationId, String source, String destination, StackValue item,
                          StackValue sourceBefore, StackValue sourceAfter, StackValue destinationBefore,
                          StackValue destinationAfter, State state, String detail, long updatedAt) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.source = bounded(source, 2048);
        this.destination = bounded(destination, 2048);
        this.item = Objects.requireNonNull(item, "item");
        this.sourceBefore = Objects.requireNonNull(sourceBefore, "sourceBefore");
        this.sourceAfter = Objects.requireNonNull(sourceAfter, "sourceAfter");
        this.destinationBefore = Objects.requireNonNull(destinationBefore, "destinationBefore");
        this.destinationAfter = Objects.requireNonNull(destinationAfter, "destinationAfter");
        this.state = Objects.requireNonNull(state, "state");
        this.detail = bounded(detail, 4096);
        this.updatedAt = updatedAt;
    }
    private static String bounded(String value, int limit) {
        Objects.requireNonNull(value);
        if (value.length() > limit) throw new IllegalArgumentException("Journal text exceeds limit");
        return value;
    }
    public TransferRecord withState(State next, String reason) {
        return new TransferRecord(operationId, source, destination, item, sourceBefore, sourceAfter, destinationBefore, destinationAfter, next, reason, System.currentTimeMillis());
    }
    public UUID operationId() { return operationId; }
    public String source() { return source; }
    public String destination() { return destination; }
    public StackValue item() { return item; }
    public StackValue sourceBefore() { return sourceBefore; }
    public StackValue sourceAfter() { return sourceAfter; }
    public StackValue destinationBefore() { return destinationBefore; }
    public StackValue destinationAfter() { return destinationAfter; }
    public State state() { return state; }
    public String detail() { return detail; }
    public long updatedAt() { return updatedAt; }
}
