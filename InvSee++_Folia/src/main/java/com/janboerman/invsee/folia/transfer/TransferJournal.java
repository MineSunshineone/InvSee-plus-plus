package com.janboerman.invsee.folia.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Writes must be durable before their returned future succeeds. Never block a region thread. */
public interface TransferJournal {
    CompletableFuture<Optional<TransferRecord>> load(UUID operationId);
    CompletableFuture<List<TransferRecord>> loadAll();
    /** Recovery UI should use this rather than materializing all terminal audit records. */
    default CompletableFuture<List<TransferRecord>> loadUnresolved() {
        return loadAll().thenApply(records -> {
            java.util.List<TransferRecord> unresolved = new java.util.ArrayList<>();
            for (TransferRecord record : records) if (!record.state().terminal()) unresolved.add(record);
            return java.util.Collections.unmodifiableList(unresolved);
        });
    }
    CompletableFuture<Void> write(TransferRecord record);
}
