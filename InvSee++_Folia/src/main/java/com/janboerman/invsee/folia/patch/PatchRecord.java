package com.janboerman.invsee.folia.patch;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit/recovery evidence. No record authorizes automatic overwriting. */
public final class PatchRecord {
    public static final int MAX_ITEM_BYTES = 1024 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    /** Actor UUID reserved for commands issued by the server console. */
    public static final UUID CONSOLE_ACTOR = new UUID(0, 0);
    public enum State {
        PREPARED, APPLIED, COMPLETED, CONFLICT, RECOVERY_REQUIRED;
        public boolean terminal() { return this == COMPLETED || this == CONFLICT; }
    }
    private final UUID id, actor, target;
    private final PatchType type;
    private final InventoryKind kind;
    private final String description, detail;
    private final byte[][] before, after;
    private final State state;
    private final long updatedAt;

    public PatchRecord(UUID id, UUID actor, PatchType type, UUID target, InventoryKind kind,
                       String description, byte[][] before, byte[][] after, State state,
                       String detail, long updatedAt) {
        this(id, actor, type, target, kind, description, copy(before, kind), copy(after, kind), state, detail, updatedAt, true);
    }
    private PatchRecord(UUID id, UUID actor, PatchType type, UUID target, InventoryKind kind,
                        String description, byte[][] before, byte[][] after, State state,
                        String detail, long updatedAt, boolean trusted) {
        this.id = Objects.requireNonNull(id); this.actor = Objects.requireNonNull(actor);
        this.target = Objects.requireNonNull(target); this.type = Objects.requireNonNull(type);
        this.kind = Objects.requireNonNull(kind); this.state = Objects.requireNonNull(state);
        this.description = bounded(description, 2048); this.detail = bounded(detail, 4096);
        this.before = before; this.after = after; this.updatedAt = updatedAt;
    }
    private static String bounded(String value, int limit) {
        Objects.requireNonNull(value);
        if (value.length() > limit) throw new IllegalArgumentException("Patch text exceeds limit");
        return value;
    }
    private static byte[][] copy(byte[][] inventory, InventoryKind kind) {
        Objects.requireNonNull(kind); Objects.requireNonNull(inventory);
        if (inventory.length != kind.slots()) throw new IllegalArgumentException("Wrong managed inventory size for " + kind);
        byte[][] copy = new byte[inventory.length][];
        int total = 0;
        for (int i = 0; i < inventory.length; i++) {
            byte[] item = inventory[i];
            if (item == null || item.length == 0) continue;
            if (item.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("Item exceeds patch data limit");
            total += item.length;
            if (total > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Inventory exceeds patch data limit");
            copy[i] = item.clone();
        }
        return copy;
    }
    public PatchRecord withState(State next, String reason) {
        return new PatchRecord(id, actor, type, target, kind, description, before, after, next, reason, System.currentTimeMillis(), true);
    }
    boolean sameRequest(PatchRecord other) {
        return id.equals(other.id) && actor.equals(other.actor) && target.equals(other.target) &&
                type == other.type && kind == other.kind && description.equals(other.description) &&
                Arrays.deepEquals(before, other.before) && Arrays.deepEquals(after, other.after);
    }
    public UUID id() { return id; }
    public UUID operationId() { return id; }
    public UUID actor() { return actor; }
    public UUID target() { return target; }
    public PatchType type() { return type; }
    public InventoryKind kind() { return kind; }
    public String description() { return description; }
    public byte[][] before() { return copy(before, kind); }
    public byte[][] after() { return copy(after, kind); }
    public State state() { return state; }
    public String detail() { return detail; }
    public long updatedAt() { return updatedAt; }
}
