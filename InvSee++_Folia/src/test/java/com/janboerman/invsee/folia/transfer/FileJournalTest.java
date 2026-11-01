package com.janboerman.invsee.folia.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileJournalTest {
    @TempDir Path directory;

    @Test void roundTripRetainsSplitSnapshotsAndAtomicReplacement() throws Exception {
        FileJournal journal = new FileJournal(directory, Runnable::run);
        UUID id = UUID.randomUUID();
        StackValue item = new StackValue(new byte[] {1, 32}), before = new StackValue(new byte[] {1, 64});
        TransferRecord record = new TransferRecord(id, "source", "dest", item, before, item, item, before, TransferRecord.State.RESERVED, "detail", 123);
        journal.write(record).join();
        TransferRecord loaded = journal.load(id).join().orElseThrow();
        assertEquals(before, loaded.sourceBefore()); assertEquals(item, loaded.sourceAfter());
        assertEquals(item, loaded.destinationBefore()); assertEquals(before, loaded.destinationAfter());
        assertEquals(123, loaded.updatedAt()); assertEquals("detail", loaded.detail());
        journal.write(record.withState(TransferRecord.State.COMPLETED, "")).join();
        assertEquals(TransferRecord.State.COMPLETED, journal.load(id).join().orElseThrow().state());
        assertEquals(1, journal.loadAll().join().size());
        assertTrue(journal.loadUnresolved().join().isEmpty());
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void corruptedRecordFailsClosed() throws Exception {
        UUID id = UUID.randomUUID();
        Files.write(directory.resolve(id + ".txn"), new byte[] {1,2,3});
        FileJournal journal = new FileJournal(directory, Runnable::run);
        assertThrows(CompletionException.class, () -> journal.load(id).join());
        assertThrows(CompletionException.class, () -> journal.loadAll().join());
    }

    @Test void checksumRejectsCorruptionEvenIfBinaryStructureRemainsValid() throws Exception {
        UUID id = UUID.randomUUID();
        FileJournal journal = new FileJournal(directory, Runnable::run);
        journal.write(new TransferRecord(id, "source", "dest", new StackValue(new byte[] {1}), TransferRecord.State.PREPARED, "", 0)).join();
        Path path = directory.resolve(id + ".txn");
        byte[] bytes = Files.readAllBytes(path);
        bytes[28] ^= 1;
        Files.write(path, bytes);
        assertThrows(CompletionException.class, () -> journal.load(id).join());
    }

    @Test void rejectedIoExecutorCompletesFuture() throws Exception {
        FileJournal journal = new FileJournal(directory, command -> { throw new RejectedExecutionException(); });
        assertTrue(journal.load(UUID.randomUUID()).isCompletedExceptionally());
    }

    @Test void stackBytesCannotBeMutatedByCaller() {
        byte[] original = {1,2,3};
        StackValue stack = new StackValue(original); original[0] = 9;
        byte[] exposed = stack.bytes(); exposed[0] = 8;
        assertArrayEquals(new byte[] {1,2,3}, stack.bytes());
    }
}
