package com.janboerman.invsee.folia.patch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Writes complete only after durable persistence; I/O never runs on a region thread. */
public interface PatchJournal {
    CompletableFuture<Optional<PatchRecord>> load(UUID id);
    CompletableFuture<List<PatchRecord>> loadUnresolved();
    CompletableFuture<Void> write(PatchRecord record);
}
