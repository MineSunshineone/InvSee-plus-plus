package com.janboerman.invsee.folia.transfer;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * One file per transaction, forced to disk and atomically replaced. Use one
 * instance/service per directory; execute I/O on a dedicated executor, never a
 * region scheduler. Terminal files remain as idempotency/audit records.
 */
public final class FileJournal implements TransferJournal {
    private static final int MAGIC = 0x49565458;
    private static final int VERSION = 3;
    private static final long MAX_FILE_SIZE = StackValue.MAX_BYTES * 5L + 65536;
    private final Path directory;
    private final Executor executor;

    public FileJournal(Path directory, Executor executor) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.executor = Objects.requireNonNull(executor);
        Files.createDirectories(this.directory);
    }
    public Path directory() { return directory; }

    @Override public CompletableFuture<Optional<TransferRecord>> load(UUID id) {
        return io(() -> {
            Path path = file(id);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            TransferRecord record = read(path);
            if (!record.operationId().equals(id)) throw new IOException("Journal filename/operation mismatch");
            return Optional.of(record);
        });
    }

    @Override public CompletableFuture<List<TransferRecord>> loadAll() { return scan(false); }
    @Override public CompletableFuture<List<TransferRecord>> loadUnresolved() { return scan(true); }

    private CompletableFuture<List<TransferRecord>> scan(boolean unresolvedOnly) {
        return io(() -> {
            List<TransferRecord> records = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.txn")) {
                for (Path path : files) {
                    TransferRecord record = read(path);
                    if (!path.getFileName().toString().equals(record.operationId() + ".txn"))
                        throw new IOException("Journal filename/operation mismatch");
                    if (!unresolvedOnly || !record.state().terminal()) records.add(record);
                }
            }
            return Collections.unmodifiableList(records);
        });
    }

    @Override public CompletableFuture<Void> write(TransferRecord record) {
        return io(() -> {
            Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
            try {
                MessageDigest digest = sha256();
                try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(stream, digest)))) {
                    out.writeInt(MAGIC); out.writeInt(VERSION);
                    out.writeLong(record.operationId().getMostSignificantBits());
                    out.writeLong(record.operationId().getLeastSignificantBits());
                    out.writeUTF(record.source()); out.writeUTF(record.destination());
                    writeStack(out, record.item()); writeStack(out, record.sourceBefore()); writeStack(out, record.sourceAfter());
                    writeStack(out, record.destinationBefore()); writeStack(out, record.destinationAfter());
                    out.writeUTF(record.state().name()); out.writeUTF(record.detail()); out.writeLong(record.updatedAt());
                    out.flush();
                    stream.write(digest.digest());
                    stream.getChannel().force(true);
                }
                // Deliberately no non-atomic fallback. Failure means no mutation
                // may follow this write; existing player data is not risked.
                Files.move(temporary, file(record.operationId()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
                return null;
            } finally { Files.deleteIfExists(temporary); }
        });
    }

    private TransferRecord read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_SIZE)
            throw new IOException("Invalid journal file: " + path.getFileName());
        byte[] contents = Files.readAllBytes(path);
        if (contents.length < 40) throw new IOException("Truncated journal record");
        int payloadLength = contents.length - 32;
        MessageDigest digest = sha256();
        digest.update(contents, 0, payloadLength);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(contents, payloadLength, contents.length)))
            throw new IOException("Journal checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(contents, 0, payloadLength))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported journal format");
            UUID id = new UUID(in.readLong(), in.readLong());
            String source = in.readUTF(), destination = in.readUTF();
            StackValue item = readStack(in), before = readStack(in), after = readStack(in);
            StackValue destinationBefore = readStack(in), destinationAfter = readStack(in);
            TransferRecord.State state;
            try { state = TransferRecord.State.valueOf(in.readUTF()); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid journal state", error); }
            String detail = in.readUTF();
            long updated = in.readLong();
            if (in.read() != -1) throw new IOException("Trailing journal data");
            try { return new TransferRecord(id, source, destination, item, before, after, destinationBefore, destinationAfter, state, detail, updated); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid journal record", error); }
        }
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
    }

    private Path file(UUID id) { return directory.resolve(id + ".txn"); }
    private static void writeStack(DataOutputStream out, StackValue value) throws IOException {
        byte[] bytes = value.bytes(); out.writeInt(bytes.length); out.write(bytes);
    }
    private static StackValue readStack(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > StackValue.MAX_BYTES) throw new IOException("Invalid stack length");
        byte[] bytes = new byte[length]; in.readFully(bytes); return new StackValue(bytes);
    }
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
