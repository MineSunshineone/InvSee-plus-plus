package com.janboerman.invsee.folia;

import com.janboerman.invsee.spigot.InvseePlusPlus;
import com.janboerman.invsee.spigot.api.Scheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler implementation based on {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler},
 * {@link io.papermc.paper.threadedregions.scheduler.AsyncScheduler} and {@link EntityScheduler}.
 */
public class FoliaScheduler implements Scheduler {

    private final InvseePlusPlus plugin;

    public FoliaScheduler(InvseePlusPlus plugin) {
        this.plugin = plugin;
    }

    @Override
    public void executeSyncPlayer(UUID playerId, Runnable task, Runnable retired) {
        Server server = plugin.getServer();
        Player player = server.getPlayer(playerId);
        if (player != null) {
            executeSyncPlayer(player, task, retired);
        } else {
            executeSyncGlobal(task);
        }
    }

    public void executeSyncPlayer(HumanEntity player, Runnable task, Runnable retired) {
        // run() returns null for an already retired entity; that path does not
        // promise to call the retirement callback. Arbitrate both paths once.
        AtomicBoolean dispatched = new AtomicBoolean();
        Runnable onRetired = () -> {
            if (dispatched.compareAndSet(false, true) && retired != null) retired.run();
        };
        if (player.getScheduler().run(plugin, scheduledTask -> {
            if (dispatched.compareAndSet(false, true)) task.run();
        }, onRetired) == null) {
            onRetired.run();
            if (retired == null) throw new RejectedExecutionException("Player entity is retired");
        }
    }

    @Override
    public void executeSyncGlobal(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void executeSyncGlobalRepeatedly(Runnable task, long ticksInitialDelay, long ticksPeriod) {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), Math.max(1L, ticksInitialDelay), ticksPeriod);
    }

    @Override
    public void executeAsync(Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void executeLaterGlobal(Runnable task, long delayTicks) {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), Math.max(1L, delayTicks));
    }

    @Override
    public void executeLaterAsync(Runnable task, long delayTicks) {
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks * 50, TimeUnit.MILLISECONDS);
    }
}
