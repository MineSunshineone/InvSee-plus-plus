package com.janboerman.invsee.folia;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Never fall back to a global thread for an entity operation. */
final class PlayerTasks {
    /** A definitive refusal before any owner action started, safe to treat as CAS=false. */
    static final class NotExecutedException extends CancellationException {
        NotExecutedException(String message) { super(message); }
    }
    private static final java.util.Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private static volatile boolean stopped;
    private static final Object lifecycle = new Object();
    private static int active;
    private static final CompletableFuture<Void> drained = new CompletableFuture<>();
    static CompletableFuture<Void> stop() {
        synchronized (lifecycle) { stopped = true; if (active == 0) drained.complete(null); }
        for (CompletableFuture<?> future : pending) future.completeExceptionally(new CancellationException("Plugin stopping; owner operation outcome must be reviewed"));
        return drained;
    }
    static <T> CompletableFuture<T> call(JavaPlugin plugin, Player player, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (stopped) return CompletableFuture.failedFuture(new NotExecutedException("Plugin stopping"));
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        if (stopped) { result.completeExceptionally(new CancellationException("Plugin stopping")); return result; }
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable retired = () -> {
            if (claimed.compareAndSet(false, true)) result.completeExceptionally(new NotExecutedException("Player disconnected"));
        };
        try {
            if (player.getScheduler().run(plugin, task -> {
                if (!claimed.compareAndSet(false, true) || result.isDone()) return;
                synchronized (lifecycle) {
                    if (stopped || result.isDone()) return;
                    active++;
                }
                try {
                    if (!player.isOnline() || player.getServer().getPlayer(player.getUniqueId()) != player)
                        throw new NotExecutedException("Player session changed");
                    result.complete(action.get());
                } catch (Throwable error) { result.completeExceptionally(error); }
                finally { synchronized (lifecycle) { if (--active == 0 && stopped) drained.complete(null); } }
            }, retired) == null) retired.run();
        } catch (Throwable error) { result.completeExceptionally(error); }
        return result;
    }
}
