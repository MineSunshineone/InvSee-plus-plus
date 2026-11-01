package com.janboerman.invsee.folia.transfer;

import java.util.Arrays;
import java.util.Objects;

/** Immutable, opaque serialized full item stack; zero bytes represent an empty slot. */
public final class StackValue {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final StackValue EMPTY = new StackValue(new byte[0]);
    private final byte[] bytes;

    public StackValue(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Item data exceeds limit");
        this.bytes = bytes.clone();
    }
    public static StackValue empty() { return EMPTY; }
    public boolean isEmpty() { return bytes.length == 0; }
    public byte[] bytes() { return bytes.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof StackValue && Arrays.equals(bytes, ((StackValue) other).bytes);
    }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
}
