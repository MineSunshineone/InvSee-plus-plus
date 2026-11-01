package com.janboerman.invsee.folia.offline;

import net.minecraft.nbt.CompoundTag;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Resolves server-known names only, never calls Mojang or derives an offline UUID.
 * The cache is authoritative; fallback names are historical lastKnownName values
 * from playerdata. Ambiguous historical names require an explicit UUID.
 * No player inventory is accessed, and all file indexing runs on a bounded IO worker.
 */
public final class OfflineNameResolver implements AutoCloseable {
    private static final int MAX_FILES = 50_000;
    private static final long MAX_COMPRESSED_BYTES = 256L * 1024 * 1024;
    private static final long CACHE_NANOS = TimeUnit.MINUTES.toNanos(2);
    private final Path directory;
    private final Function<String, Optional<UUID>> cached;
    private final ExecutorService executor;
    private CompletableFuture<Map<String, Set<UUID>>> index;
    private long indexedAt;
    private boolean closed;

    public OfflineNameResolver(JavaPlugin plugin) {
        this(((CraftServer) plugin.getServer()).getHandle().playerIo.getPlayerDir().toPath(), name -> {
            // Exact Folia 26.2: getIfCached is lock-protected, with no network/file lookup.
            OfflinePlayer player = plugin.getServer().getOfflinePlayerIfCached(name);
            return player == null ? Optional.empty() : Optional.of(player.getUniqueId());
        });
    }

    OfflineNameResolver(Path directory, Function<String, Optional<UUID>> cached) {
        this.directory = Objects.requireNonNull(directory);
        this.cached = Objects.requireNonNull(cached);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "InvSee-Folia-name-index");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<Optional<UUID>> resolve(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 64) return CompletableFuture.completedFuture(Optional.empty());
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IOException("Name resolver is closed"));
        }
        Optional<UUID> known = cached.apply(name);
        if (known.isPresent()) return CompletableFuture.completedFuture(known);
        return index().thenCompose(names -> io(() -> {
            // A login/cache update during the scan takes precedence over historical data.
            Optional<UUID> current = cached.apply(name);
            if (current.isPresent()) return current;
            Set<UUID> matches = names.get(name.toLowerCase(Locale.ROOT));
            if (matches == null || matches.size() != 1) return Optional.empty();
            UUID id = matches.iterator().next();
            // Do not return an index entry whose save file has since changed names.
            String latest = lastKnownName(directory.resolve(id + ".dat"));
            return latest != null && latest.equalsIgnoreCase(name) ? Optional.of(id) : Optional.empty();
        }));
    }

    private synchronized CompletableFuture<Map<String, Set<UUID>>> index() {
        if (closed) return CompletableFuture.failedFuture(new IOException("Name resolver is closed"));
        if (index == null || index.isCompletedExceptionally()
                || (index.isDone() && System.nanoTime() - indexedAt > CACHE_NANOS)) {
            indexedAt = System.nanoTime();
            index = io(this::scan);
        }
        return index;
    }

    private Map<String, Set<UUID>> scan() throws IOException {
        Map<String, Set<UUID>> names = new HashMap<>();
        if (!Files.isDirectory(directory)) return names;
        int count = 0;
        long bytes = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.dat")) {
            for (Path file : files) {
                String filename = file.getFileName().toString();
                UUID id;
                try { id = UUID.fromString(filename.substring(0, filename.length() - 4)); }
                catch (IllegalArgumentException invalid) { continue; }
                if (++count > MAX_FILES || (bytes += Files.size(file)) > MAX_COMPRESSED_BYTES)
                    throw new IOException("Player name index exceeds safe scan limits; use a UUID");
                String name = lastKnownName(file);
                if (name != null && !name.isBlank() && name.length() <= 64)
                    names.computeIfAbsent(name.toLowerCase(Locale.ROOT), unused -> new HashSet<>()).add(id);
            }
        }
        return names;
    }

    private static String lastKnownName(Path file) throws IOException {
        CompoundTag root = NbtInventoryCodec.decompress(AtomicPlayerDataFile.read(file));
        return root.getCompound("bukkit").flatMap(bukkit -> bukkit.getString("lastKnownName")).orElse(null);
    }

    private <T> CompletableFuture<T> io(IoTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { result.complete(task.run()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        // Read-only work may drain safely after disable; it never writes playerdata.
        executor.shutdown();
    }
    @FunctionalInterface private interface IoTask<T> { T run() throws Exception; }
}
