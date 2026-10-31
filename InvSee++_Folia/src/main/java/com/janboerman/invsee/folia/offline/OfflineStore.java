package com.janboerman.invsee.folia.offline;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import net.kyori.adventure.text.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Offline sessions contain bytes/tags only. No fake players, world reads, or region waits. */
public final class OfflineStore implements Listener, AutoCloseable {
    private final OfflineAccessCoordinator fence = new OfflineAccessCoordinator();
    private final Map<UUID, OfflineAccessCoordinator.Token> logins = new ConcurrentHashMap<>();
    private final Map<AsyncPlayerPreLoginEvent, OfflineAccessCoordinator.Token> admissions = new ConcurrentHashMap<>();
    private final Set<UUID> recoveryBlocked = ConcurrentHashMap.newKeySet();
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final Semaphore pending = new Semaphore(128);
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "InvSee-Folia-offline-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Path directory;
    private final RegistryAccess registries;
    private volatile boolean closed;

    /** Construct during initial plugin enable, before accepting connections. Hot reload is unsupported. */
    public OfflineStore(JavaPlugin plugin) throws IOException {
        directory = ((CraftServer) plugin.getServer()).getHandle().playerIo.getPlayerDir().toPath();
        registries = CraftRegistry.getMinecraftRegistry();
        // Recoveries are quarantined BEFORE registering admission, not discovered after a login starts.
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".dat.invsee-transaction")).forEach(name -> {
                    try { recoveryBlocked.add(UUID.fromString(name.substring(0, name.length() - ".dat.invsee-transaction".length()))); }
                    catch (IllegalArgumentException ignored) { /* not one of our UUID paths */ }
                });
            }
        }
        // References/UUID enumeration only; no live player inventory or entity state is accessed.
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            UUID id = player.getUniqueId();
            OfflineAccessCoordinator.Token token = fence.tryLogin(id);
            if (token != null) logins.put(id, token);
        });
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public CompletableFuture<Lease> open(UUID id) {
        if (closed || recoveryBlocked.contains(id)) return CompletableFuture.failedFuture(new IOException("Offline data is closed or awaiting recovery"));
        OfflineAccessCoordinator.Token token = fence.tryOffline(id);
        if (token == null) return CompletableFuture.failedFuture(new IOException("Player is online, connecting, or already being edited"));
        Lease lease = new Lease(id, token);
        leases.add(lease);
        CompletableFuture<Lease> result = lease.submit(() -> {
            Path file = lease.file();
            if (AtomicPlayerDataFile.needsRecovery(file)) {
                recoveryBlocked.add(id);
                throw new IOException("Unresolved offline transaction: " + AtomicPlayerDataFile.journal(file));
            }
            lease.codec.decode(AtomicPlayerDataFile.read(file)); // reject unknown, corrupt, or incompatible data early
            return lease;
        });
        result.whenComplete((value, error) -> { if (error != null || result.isCancelled()) lease.close(); });
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = event.getUniqueId();
        OfflineAccessCoordinator.Token token = id == null || recoveryBlocked.contains(id) ? null : fence.tryLogin(id);
        if (token == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(
                    id != null && recoveryBlocked.contains(id) ? "Inventory recovery is pending. Please contact an administrator." : "Your inventory is being edited. Please retry shortly."));
            return;
        }
        admissions.put(event, token);
        logins.put(id, token);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginFinished(AsyncPlayerPreLoginEvent event) {
        OfflineAccessCoordinator.Token token = admissions.remove(event);
        if (token != null && !token.playerId().equals(event.getUniqueId())) {
            // A later HIGHEST profile listener may replace the UUID. Never allow the server to
            // consume data under an identity other than the one fenced by this admission.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Login identity changed during inventory admission. Please retry."));
        }
        if (token != null && event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Cancelled prelogin produces NO close event; use the ORIGINAL token identity.
            logins.remove(token.playerId(), token);
            token.close();
        } else if (token == null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Another HIGHEST listener re-allowed an earlier denial, or allowed an initially denied
            // event. It must still obtain a fence. No inventory admission bypass is permitted.
            UUID id = event.getUniqueId();
            OfflineAccessCoordinator.Token late = id == null || recoveryBlocked.contains(id) ? null : fence.tryLogin(id);
            if (late == null) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Your inventory is unavailable. Please retry shortly."));
            else logins.put(id, late);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        // Exact 26.2 server ordering: onDisconnect -> PlayerList.remove -> save -> connection-close.
        // PlayerQuitEvent is too early and deliberately not used here.
        OfflineAccessCoordinator.Token token = logins.remove(event.getPlayerUniqueId());
        if (token != null) token.close();
    }

    /**
     * Read-only confirmation for an online owner-thread save. It does not acquire an offline lease,
     * rewrite player data, or touch a live player. Failure is an indeterminate transfer, not rollback.
     */
    public CompletableFuture<Void> verifySavedSlot(UUID id, boolean ender, int slot, byte[] expected) {
        byte[] copy = expected == null ? null : expected.clone();
        if (closed || !pending.tryAcquire()) return CompletableFuture.failedFuture(new IOException("Offline IO closed or busy"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    Path file = directory.resolve(id + ".dat");
                    NbtInventoryCodec codec = new NbtInventoryCodec(registries);
                    byte[] forcedSnapshot = AtomicPlayerDataFile.readForcedSnapshot(file);
                    if (!codec.matches(codec.decode(forcedSnapshot), ender, slot, copy)) throw new IOException("Native player save did not persist the committed slot");
                    result.complete(null);
                } catch (Throwable ex) { result.completeExceptionally(ex); }
                finally { pending.release(); }
            });
        } catch (RuntimeException ex) { pending.release(); result.completeExceptionally(ex); }
        return result;
    }

    /**
     * Shutdown-only IO barrier: no owner-region task is awaited. Must return only after every writer
     * stops, before Bukkit unregisters the pre-login fence. Do not call during normal gameplay.
     */
    @Override public void close() {
        closed = true;
        fence.close();
        leases.forEach(Lease::close);
        if (leases.isEmpty()) io.shutdown();
        boolean interrupted = false;
        for (;;) {
            try { if (io.awaitTermination(1, TimeUnit.SECONDS)) break; }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public final class Lease implements AutoCloseable {
        private final UUID id;
        private final OfflineAccessCoordinator.Token token;
        private final NbtInventoryCodec codec = new NbtInventoryCodec(registries);
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private boolean closing;
        private final CompletableFuture<Void> released = new CompletableFuture<>();
        private int count;
        private Lease(UUID id, OfflineAccessCoordinator.Token token) { this.id = id; this.token = token; }
        public UUID playerId() { return id; }
        private Path file() { return directory.resolve(id + ".dat"); }

        public CompletableFuture<byte[][]> read(boolean ender) {
            return submit(() -> codec.read(codec.decode(AtomicPlayerDataFile.read(file())), ender));
        }
        public CompletableFuture<Boolean> compareAndSet(boolean ender, int slot, byte[] expected, byte[] replacement) {
            byte[] beforeItem = expected == null ? null : expected.clone();
            byte[] afterItem = replacement == null ? null : replacement.clone();
            return submit(() -> {
                byte[] before = AtomicPlayerDataFile.read(file());
                CompoundTag root = codec.decode(before);
                if (!codec.matches(root, ender, slot, beforeItem)) return false;
                codec.replace(root, ender, slot, afterItem);
                try {
                    AtomicPlayerDataFile.replace(file(), before, NbtInventoryCodec.compress(root));
                    recoveryBlocked.add(id); // keep login fenced until durable transfer acknowledgement
                } catch (IOException ex) {
                    if (AtomicPlayerDataFile.needsRecovery(file())) recoveryBlocked.add(id);
                    throw ex;
                }
                return true;
            });
        }
        public CompletableFuture<Void> acknowledgeCommit() {
            return submit(() -> {
                AtomicPlayerDataFile.acknowledge(file());
                recoveryBlocked.remove(id);
                return null;
            });
        }
        private synchronized <T> CompletableFuture<T> submit(IoTask<T> operation) {
            if (closing || closed || count >= 16 || !pending.tryAcquire()) return CompletableFuture.failedFuture(new IOException("Offline IO is closed or busy; retry"));
            count++;
            CompletableFuture<T> result = new CompletableFuture<>();
            CompletableFuture<Void> previous = tail;
            CompletableFuture<Void> next = new CompletableFuture<>();
            tail = next;
            previous.whenComplete((ignored, previousError) -> {
                try {
                    io.execute(() -> {
                        try { result.complete(operation.run()); }
                        catch (Throwable ex) { result.completeExceptionally(ex); }
                        finally { finished(next); }
                    });
                } catch (RuntimeException rejected) {
                    result.completeExceptionally(rejected);
                    finished(next);
                }
            });
            return result;
        }
        private void finished(CompletableFuture<Void> next) {
            synchronized (this) {
                count--;
                pending.release();
                if (closing && count == 0) release();
            }
            next.complete(null);
        }
        @Override public synchronized void close() {
            closing = true;
            if (count == 0) release();
        }
        /** Completes only when all accepted reads/writes are drained and the UUID fence is released. */
        public CompletableFuture<Void> closeAsync() { close(); return released.copy(); }
        private void release() {
            leases.remove(this);
            token.close();
            released.complete(null);
            if (closed && leases.isEmpty()) io.shutdown();
        }
    }
    @FunctionalInterface private interface IoTask<T> { T run() throws Exception; }
}
