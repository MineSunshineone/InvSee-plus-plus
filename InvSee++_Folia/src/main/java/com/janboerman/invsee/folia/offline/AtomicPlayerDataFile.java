package com.janboerman.invsee.folia.offline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Disk-only primitive. Caller must hold the UUID login fence for the entire operation. */
public final class AtomicPlayerDataFile {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private AtomicPlayerDataFile() {}

    public static byte[] read(Path target) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing/non-regular player data: " + target);
        long length = Files.size(target);
        if (length > MAX_BYTES) throw new IOException("Player data exceeds size limit: " + target);
        try (var in = Files.newInputStream(target)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Player data exceeds size limit: " + target);
            return bytes;
        }
    }

    /**
     * Reads and forces one descriptor, rejecting concurrent native atomic replacement. Never claim
     * durability by reading one pathname image and forcing a different inode opened later.
     */
    public static byte[] readForcedSnapshot(Path target) throws IOException {
        return readForcedSnapshot(target, () -> {}, () -> {});
    }
    // Deterministic fault-injection seam, package-private and never used by the runtime adapter.
    static byte[] readForcedSnapshot(Path target, IoAction afterRead, IoAction afterForce) throws IOException {
        BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() > MAX_BYTES) throw new IOException("Cannot verify stable player data identity: " + target);
        byte[] snapshot;
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int read;
            while ((read = channel.read(buffer)) != -1) {
                if (out.size() + read > MAX_BYTES) throw new IOException("Player data exceeds size limit");
                out.write(buffer.array(), 0, read);
                buffer.clear();
            }
            snapshot = out.toByteArray();
            afterRead.run();
            channel.force(true);
            forceDirectory(target.getParent());
            afterForce.run();
            BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException("Native player data changed during durability verification");
            }
            // Native safeReplaceFile can restore its old inode on failure (an ABA rename).
            // Also compare the visible image, then check that its identity stayed stable while read.
            if (!Arrays.equals(snapshot, read(target))) throw new IOException("Forced descriptor is not the visible player data image");
            BasicFileAttributes finalAttributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(after.fileKey(), finalAttributes.fileKey()) || after.size() != finalAttributes.size()
                    || !after.lastModifiedTime().equals(finalAttributes.lastModifiedTime())) {
                throw new IOException("Player data changed while confirming the forced image");
            }
        }
        return snapshot;
    }

    /**
     * Preserves a before-image and proposed after-image before the atomic replacement.
     * A failure after replacement is INDETERMINATE, never a false/rolled-back result.
     * Unresolved journals deliberately prevent further edits until explicitly reconciled.
     */
    public static void replace(Path target, byte[] expected, byte[] replacement) throws IOException {
        if (replacement.length > MAX_BYTES) throw new IOException("Replacement exceeds size limit");
        Path journal = journal(target);
        cleanupAcknowledged(target);
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unresolved offline transaction: " + journal);
        if (!Arrays.equals(expected, read(target))) throw new IOException("Player data changed outside offline lease");
        Files.createDirectory(journal);
        forceDirectory(target.getParent());
        writeForced(journal.resolve("before.dat"), expected);
        writeForced(journal.resolve("after.dat"), replacement);
        forceDirectory(journal);
        // Never fall back to a non-atomic move. The server must see old OR new NBT.
        Path temporary = journal.resolve("install.dat");
        writeForced(temporary, replacement);
        if (!Arrays.equals(expected, read(target))) throw new IOException("Player data changed before atomic replacement");
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(target.getParent());
        writeForced(journal.resolve("committed"), new byte[] {1});
        forceDirectory(journal);
    }

    /** Only acknowledge after the caller has durably recorded the target commit. */
    public static void acknowledge(Path target) throws IOException {
        Path journal = journal(target);
        if (!Files.isRegularFile(journal.resolve("committed"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Transaction is not confirmed committed: " + journal);
        if (!Arrays.equals(read(target), read(journal.resolve("after.dat")))) throw new IOException("Committed player data changed before acknowledgement");
        // Rename first: a crash during cleanup must not turn a fully acknowledged transfer into
        // a half-deleted unresolved journal. The higher-level transfer phase is durable already.
        cleanupAcknowledged(target);
        Files.move(journal, acknowledged(target), StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(target.getParent());
        cleanupAcknowledged(target);
    }

    private static Path acknowledged(Path target) { return target.resolveSibling(target.getFileName() + ".invsee-acknowledged"); }
    private static void cleanupAcknowledged(Path target) throws IOException {
        Path acknowledged = acknowledged(target);
        if (!Files.exists(acknowledged, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(acknowledged, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid acknowledged journal: " + acknowledged);
        Files.deleteIfExists(acknowledged.resolve("before.dat"));
        Files.deleteIfExists(acknowledged.resolve("after.dat"));
        Files.deleteIfExists(acknowledged.resolve("committed"));
        Files.delete(acknowledged);
        forceDirectory(target.getParent());
    }

    public static Path journal(Path target) { return target.resolveSibling(target.getFileName() + ".invsee-transaction"); }
    public static boolean needsRecovery(Path target) { return Files.exists(journal(target), LinkOption.NOFOLLOW_LINKS); }

    /** Inspection is read-only: ambiguous transfers must not be replayed automatically. */
    public static Recovery inspect(Path target) throws IOException {
        Path journal = journal(target);
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return Recovery.NONE;
        byte[] current = read(target);
        Path after = journal.resolve("after.dat"), before = journal.resolve("before.dat");
        if (Files.isRegularFile(after, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(current, read(after))) return Recovery.AFTER_IMAGE;
        if (Files.isRegularFile(before, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(current, read(before))) return Recovery.BEFORE_IMAGE;
        return Recovery.CONFLICT;
    }
    public enum Recovery { NONE, BEFORE_IMAGE, AFTER_IMAGE, CONFLICT }

    @FunctionalInterface interface IoAction { void run() throws IOException; }

    private static void writeForced(Path path, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static void forceDirectory(Path directory) throws IOException {
        // A filesystem without durable directory synchronization is not safe for this backend.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
