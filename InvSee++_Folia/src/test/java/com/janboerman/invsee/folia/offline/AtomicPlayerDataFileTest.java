package com.janboerman.invsee.folia.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AtomicPlayerDataFileTest {
    @TempDir Path directory;
    private final byte[] before = {1, 2, 3}, after = {4, 5, 6};
    private Path file() throws IOException { Path path = directory.resolve("player.dat"); Files.write(path, before); return path; }

    @Test void committedReplacementRetainsBothImagesUntilAcknowledgement() throws Exception {
        Path file = file();
        AtomicPlayerDataFile.replace(file, before, after);
        assertArrayEquals(after, Files.readAllBytes(file));
        assertArrayEquals(before, Files.readAllBytes(AtomicPlayerDataFile.journal(file).resolve("before.dat")));
        assertEquals(AtomicPlayerDataFile.Recovery.AFTER_IMAGE, AtomicPlayerDataFile.inspect(file));
        assertTrue(AtomicPlayerDataFile.needsRecovery(file));
        AtomicPlayerDataFile.acknowledge(file);
        assertEquals(AtomicPlayerDataFile.Recovery.NONE, AtomicPlayerDataFile.inspect(file));
        assertArrayEquals(after, Files.readAllBytes(file));
    }
    @Test void staleSnapshotNeverOverwritesCurrentFile() throws Exception {
        Path file = file();
        Files.write(file, after);
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.replace(file, before, new byte[] {7}));
        assertArrayEquals(after, Files.readAllBytes(file));
        assertFalse(AtomicPlayerDataFile.needsRecovery(file));
    }
    @Test void unresolvedJournalBlocksSubsequentWrites() throws Exception {
        Path file = file();
        AtomicPlayerDataFile.replace(file, before, after);
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.replace(file, after, before));
        assertArrayEquals(after, Files.readAllBytes(file));
    }
    @Test void externalModificationKeepsRecoveryEvidenceAndCannotAcknowledge() throws Exception {
        Path file = file();
        AtomicPlayerDataFile.replace(file, before, after);
        Files.write(file, new byte[] {9});
        assertEquals(AtomicPlayerDataFile.Recovery.CONFLICT, AtomicPlayerDataFile.inspect(file));
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.acknowledge(file));
        assertTrue(Files.exists(AtomicPlayerDataFile.journal(file).resolve("before.dat")));
    }
    @Test void interruptedPreparationIsInspectedWithoutReplaying() throws Exception {
        Path file = file();
        Path journal = AtomicPlayerDataFile.journal(file);
        Files.createDirectory(journal);
        Files.write(journal.resolve("before.dat"), before);
        Files.write(journal.resolve("after.dat"), after);
        assertEquals(AtomicPlayerDataFile.Recovery.BEFORE_IMAGE, AtomicPlayerDataFile.inspect(file));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.acknowledge(file));
    }
    @Test void interruptedAcknowledgementCleanupDoesNotBlockOrReplayNextCommit() throws Exception {
        Path file = file();
        Path acknowledged = file.resolveSibling(file.getFileName() + ".invsee-acknowledged");
        Files.createDirectory(acknowledged);
        Files.write(acknowledged.resolve("after.dat"), new byte[] {8});
        assertFalse(AtomicPlayerDataFile.needsRecovery(file));
        AtomicPlayerDataFile.replace(file, before, after);
        assertArrayEquals(after, Files.readAllBytes(file));
        assertFalse(Files.exists(acknowledged));
        AtomicPlayerDataFile.acknowledge(file);
    }
    @Test void forceReadsTheExactDescriptorAndRejectsNativeReplacementBetweenChecks() throws Exception {
        Path file = file();
        assertArrayEquals(before, AtomicPlayerDataFile.readForcedSnapshot(file));
        Path wrong = directory.resolve("wrong.tmp"), matching = directory.resolve("matching.tmp");
        Files.write(wrong, after); Files.write(matching, before);
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.readForcedSnapshot(file,
                () -> Files.move(wrong, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING),
                () -> Files.move(matching, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)));
        assertArrayEquals(before, Files.readAllBytes(file)); // matching path bytes alone are NOT proof of a forced image
    }
    @Test void missingDataIsNotSynthesizedAndSymbolicLinksAreRejected() throws Exception {
        Path missing = directory.resolve("unknown.dat");
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.read(missing));
        Path original = file(); Path link = directory.resolve("link.dat");
        Files.createSymbolicLink(link, original);
        assertThrows(IOException.class, () -> AtomicPlayerDataFile.read(link));
    }
}
