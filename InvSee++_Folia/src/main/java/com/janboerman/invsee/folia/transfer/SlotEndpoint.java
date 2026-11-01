package com.janboerman.invsee.folia.transfer;

import java.util.concurrent.CompletableFuture;

/**
 * All access must be dispatched by the implementation to its inventory's owner.
 * A successful CAS future resolves only after the mutation happened. False means
 * definitely unchanged. An exceptional outcome is conservatively treated as
 * uncertain (the caller must never assume that no mutation occurred).
 * Implementations must revalidate player identity, online/offline epoch, slot
 * validity and permissions in the owner task immediately before mutation.
 */
public interface SlotEndpoint {
    String description();
    CompletableFuture<StackValue> read();
    CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update);
    /** Release backend recovery state only after the transfer journal records this CAS durably. */
    default CompletableFuture<Void> acknowledgeCommit() { return CompletableFuture.completedFuture(null); }
}
