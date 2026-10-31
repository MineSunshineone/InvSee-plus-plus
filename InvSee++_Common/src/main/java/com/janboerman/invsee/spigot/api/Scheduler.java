package com.janboerman.invsee.spigot.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Scheduler abstraction for scheduling tasks.
 *
 * @see <a href="https://github.com/Jannyboy11/InvSee-plus-plus/wiki/Folia-support">Folia support</a>
 */
public interface Scheduler {

    //TODO on places where this is called, the argument provided to the 'retired' parameter
    //TODO should actually be a value: Usually we want to:
    //TODO  - load the 'offline' player data,
    //TODO  - apply the changes to the player's inventory
    //TODO  - save the player data again.
    public void executeSyncPlayer(UUID playerId, Runnable task, Runnable retired);

    /**
     * Schedule a value-producing operation without leaving its future unresolved if
     * an entity retires before dispatch. The operation must not access another entity.
     * For an absent UUID, implementations may dispatch on the global scheduler;
     * callers must revalidate offline state before accessing player data.
     */
    default <T> CompletableFuture<T> supplySyncPlayer(UUID playerId, Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executeSyncPlayer(playerId, () -> {
                if (result.isDone()) return;
                try { result.complete(operation.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            }, () -> result.completeExceptionally(new CancellationException("Player retired before scheduled operation")));
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    public void executeSyncGlobal(Runnable task);

    public void executeSyncGlobalRepeatedly(Runnable task, long ticksInitialDelay, long ticksPeriod);

    public void executeAsync(Runnable task);

    public void executeLaterGlobal(Runnable task, long delayTicks);

    public void executeLaterAsync(Runnable task, long delayTicks);
}
