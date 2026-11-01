package com.janboerman.invsee.folia.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.janboerman.invsee.folia.patch.PatchServiceTest.inventory;

class FilePatchJournalTest {
    @TempDir Path directory;

    @Test void roundTripFullSnapshotsMetadataAndAtomicReplacement() throws Exception {
        FilePatchJournal journal = new FilePatchJournal(directory, Runnable::run);
        for (InventoryKind kind : InventoryKind.values()) {
            UUID id = UUID.randomUUID(), target = UUID.randomUUID();
            byte[][] before = inventory(kind, 1), after = inventory(kind, 2);
            before[kind.slots() - 1] = new byte[] {3,4}; after[kind.slots() - 1] = new byte[] {5,6};
            PatchRecord record = new PatchRecord(id, PatchRecord.CONSOLE_ACTOR, PatchType.CLONE, target, kind,
                    "target:" + kind, before, after, PatchRecord.State.APPLIED, "inspection", 123);
            journal.write(record).join();
            PatchRecord loaded = journal.load(id).join().orElseThrow();
            assertEquals(target, loaded.target()); assertEquals(PatchRecord.CONSOLE_ACTOR, loaded.actor());
            assertEquals(PatchType.CLONE, loaded.type()); assertEquals(kind, loaded.kind());
            assertTrue(Arrays.deepEquals(before, loaded.before())); assertTrue(Arrays.deepEquals(after, loaded.after()));
            assertEquals(123, loaded.updatedAt()); assertEquals("inspection", loaded.detail());
            assertEquals(1, journal.loadUnresolved().join().size());
            journal.write(record.withState(PatchRecord.State.COMPLETED, "")).join();
            assertEquals(PatchRecord.State.COMPLETED, journal.load(id).join().orElseThrow().state());
            assertTrue(journal.loadUnresolved().join().isEmpty());
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void corruptDataFailsClosedEvenWhenStructureLooksValid() throws Exception {
        FilePatchJournal journal = new FilePatchJournal(directory, Runnable::run);
        PatchRecord record = record(); journal.write(record).join(); Path path = directory.resolve(record.id() + ".patch");
        byte[] bytes = Files.readAllBytes(path); bytes[60] ^= 1; Files.write(path, bytes);
        assertThrows(CompletionException.class, () -> journal.load(record.id()).join());
        assertThrows(CompletionException.class, () -> journal.loadUnresolved().join());
    }

    @Test void renamedValidRecordCannotMasqueradeAsDifferentId() throws Exception {
        FilePatchJournal journal = new FilePatchJournal(directory, Runnable::run);
        PatchRecord record = record(); journal.write(record).join(); UUID other = UUID.randomUUID();
        Files.move(directory.resolve(record.id() + ".patch"), directory.resolve(other + ".patch"));
        assertThrows(CompletionException.class, () -> journal.load(other).join());
        assertThrows(CompletionException.class, () -> journal.loadUnresolved().join());
    }

    @Test void ioRejectionReturnsFailedFutureRatherThanHanging() throws Exception {
        FilePatchJournal journal = new FilePatchJournal(directory, ignored -> { throw new RejectedExecutionException(); });
        assertTrue(journal.write(record()).isCompletedExceptionally());
        assertTrue(journal.load(UUID.randomUUID()).isCompletedExceptionally());
        assertTrue(journal.loadUnresolved().isCompletedExceptionally());
    }

    @Test void absentRecordIsNotAnError() throws Exception {
        FilePatchJournal journal = new FilePatchJournal(directory, Runnable::run);
        assertTrue(journal.load(UUID.randomUUID()).join().isEmpty());
    }

    private static PatchRecord record() {
        return new PatchRecord(UUID.randomUUID(), UUID.randomUUID(), PatchType.GIVE, UUID.randomUUID(), InventoryKind.MAIN,
                "target:main", inventory(InventoryKind.MAIN, 1), inventory(InventoryKind.MAIN, 2), PatchRecord.State.PREPARED, "", 0);
    }
}
