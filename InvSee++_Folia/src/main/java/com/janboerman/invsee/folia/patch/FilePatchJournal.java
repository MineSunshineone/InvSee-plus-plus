package com.janboerman.invsee.folia.patch;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Independent patch journal directory. Atomic, checksummed, fsynced records; no unsafe fallback. */
public final class FilePatchJournal implements PatchJournal {
    private static final int MAGIC = 0x49565054, VERSION = 1;
    private static final long MAX_FILE_BYTES = 2L * PatchRecord.MAX_SNAPSHOT_BYTES + 65536;
    private final Path directory;
    private final Executor executor;
    public FilePatchJournal(Path directory, Executor executor) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.executor = Objects.requireNonNull(executor);
        Files.createDirectories(this.directory);
    }
    public Path directory() { return directory; }

    @Override public CompletableFuture<Optional<PatchRecord>> load(UUID id) {
        return io(() -> {
            Path path = file(id);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            PatchRecord record = read(path);
            if (!record.id().equals(id)) throw new IOException("Patch filename/id mismatch");
            return Optional.of(record);
        });
    }
    @Override public CompletableFuture<List<PatchRecord>> loadUnresolved() {
        return io(() -> {
            List<PatchRecord> records = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.patch")) {
                for (Path path : files) {
                    PatchRecord record = read(path);
                    if (!path.getFileName().toString().equals(record.id() + ".patch")) throw new IOException("Patch filename/id mismatch");
                    if (!record.state().terminal()) records.add(record);
                }
            }
            return Collections.unmodifiableList(records);
        });
    }
    @Override public CompletableFuture<Void> write(PatchRecord record) {
        return io(() -> {
            Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
            try {
                MessageDigest digest = sha256();
                try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(stream, digest)))) {
                    out.writeInt(MAGIC); out.writeInt(VERSION);
                    writeUuid(out, record.id()); writeUuid(out, record.actor()); writeUuid(out, record.target());
                    out.writeUTF(record.type().name()); out.writeUTF(record.kind().name()); out.writeUTF(record.description());
                    writeInventory(out, record.before()); writeInventory(out, record.after());
                    out.writeUTF(record.state().name()); out.writeUTF(record.detail()); out.writeLong(record.updatedAt());
                    out.flush(); stream.write(digest.digest()); stream.getChannel().force(true);
                }
                Files.move(temporary, file(record.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
                return null;
            } finally { Files.deleteIfExists(temporary); }
        });
    }
    private PatchRecord read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES)
            throw new IOException("Invalid patch record file");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 40) throw new IOException("Truncated patch record");
        int payload = bytes.length - 32;
        MessageDigest digest = sha256(); digest.update(bytes, 0, payload);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(bytes, payload, bytes.length)))
            throw new IOException("Patch checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payload))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported patch format");
            UUID id = readUuid(in), actor = readUuid(in), target = readUuid(in);
            try {
                PatchType type = PatchType.valueOf(in.readUTF()); InventoryKind kind = InventoryKind.valueOf(in.readUTF());
                String description = in.readUTF();
                byte[][] before = readInventory(in, kind), after = readInventory(in, kind);
                PatchRecord.State state = PatchRecord.State.valueOf(in.readUTF()); String detail = in.readUTF();
                long updatedAt = in.readLong();
                if (in.read() != -1) throw new IOException("Trailing patch record data");
                return new PatchRecord(id, actor, type, target, kind, description, before, after, state, detail, updatedAt);
            } catch (IllegalArgumentException error) { throw new IOException("Invalid patch record", error); }
        }
    }
    private static void writeInventory(DataOutputStream out, byte[][] inventory) throws IOException {
        out.writeInt(inventory.length);
        for (byte[] item : inventory) {
            out.writeInt(item == null ? -1 : item.length);
            if (item != null) out.write(item);
        }
    }
    private static byte[][] readInventory(DataInputStream in, InventoryKind kind) throws IOException {
        if (in.readInt() != kind.slots()) throw new IOException("Invalid managed inventory size");
        byte[][] inventory = new byte[kind.slots()][]; int total = 0;
        for (int i = 0; i < inventory.length; i++) {
            int size = in.readInt();
            if (size == -1) continue;
            if (size < 0 || size > PatchRecord.MAX_ITEM_BYTES) throw new IOException("Invalid item length");
            total += size;
            if (total > PatchRecord.MAX_SNAPSHOT_BYTES) throw new IOException("Inventory snapshot exceeds limit");
            if (size == 0) continue;
            byte[] item = new byte[size]; in.readFully(item); inventory[i] = item;
        }
        return inventory;
    }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
    }
    private Path file(UUID id) { return directory.resolve(id + ".patch"); }
    private <T> CompletableFuture<T> io(IOAction<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { future.complete(action.run()); }
                catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (Throwable error) { future.completeExceptionally(error); }
        return future;
    }
    @FunctionalInterface private interface IOAction<T> { T run() throws Exception; }
}
