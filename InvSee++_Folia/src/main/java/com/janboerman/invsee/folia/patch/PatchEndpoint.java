package com.janboerman.invsee.folia.patch;

import java.util.concurrent.CompletableFuture;

/**
 * Whole-inventory operation dispatched to one target owner. Implementations must
 * validate identity/epoch, permissions and every slot before changing any slot.
 * False guarantees no mutation. True requires verified durable persistence.
 * Exceptions may mean partial/committed mutation and are NEVER rolled back here.
 */
public interface PatchEndpoint {
    String description();
    CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after);
    /** Release backend recovery state only after durable APPLIED journal state. */
    default CompletableFuture<Void> acknowledgeCommit() { return CompletableFuture.completedFuture(null); }
}
