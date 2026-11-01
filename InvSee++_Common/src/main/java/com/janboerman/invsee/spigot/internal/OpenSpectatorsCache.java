package com.janboerman.invsee.spigot.internal;

import com.janboerman.invsee.spigot.api.EnderSpectatorInventory;
import com.janboerman.invsee.spigot.api.MainSpectatorInventory;
import com.janboerman.invsee.utils.WeakValueCache;
import java.util.UUID;

public class OpenSpectatorsCache {
    private final WeakValueCache<UUID, MainSpectatorInventory> openInventories = new WeakValueCache<>();
    private final WeakValueCache<UUID, EnderSpectatorInventory> openEnderChests = new WeakValueCache<>();

    public void cache(MainSpectatorInventory inventory) { cache(inventory, false); }
    public void cache(EnderSpectatorInventory inventory) { cache(inventory, false); }

    public void cache(MainSpectatorInventory inventory, boolean force) {
        if (force) openInventories.put(inventory.getSpectatedPlayerId(), inventory);
        else openInventories.putIfAbsent(inventory.getSpectatedPlayerId(), inventory);
    }

    public void cache(EnderSpectatorInventory inventory, boolean force) {
        if (force) openEnderChests.put(inventory.getSpectatedPlayerId(), inventory);
        else openEnderChests.putIfAbsent(inventory.getSpectatedPlayerId(), inventory);
    }

    /** Publish once and return the canonical live inventory for concurrent loaders. */
    public MainSpectatorInventory cacheAndGet(MainSpectatorInventory inventory) {
        return openInventories.putIfAbsent(inventory.getSpectatedPlayerId(), inventory);
    }

    public EnderSpectatorInventory cacheAndGet(EnderSpectatorInventory inventory) {
        return openEnderChests.putIfAbsent(inventory.getSpectatedPlayerId(), inventory);
    }

    public MainSpectatorInventory getMainSpectatorInventory(UUID id) {
        return openInventories.get(id);
    }

    public EnderSpectatorInventory getEnderSpectatorInventory(UUID id) {
        return openEnderChests.get(id);
    }
}
