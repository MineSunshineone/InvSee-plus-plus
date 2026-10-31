package com.janboerman.invsee.folia;

import com.janboerman.invsee.folia.transfer.SlotEndpoint;
import com.janboerman.invsee.folia.transfer.StackValue;
import com.janboerman.invsee.folia.offline.OfflineStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

interface InventoryAccess extends AutoCloseable {
    UUID id();
    CompletableFuture<byte[][]> read();
    SlotEndpoint slot(int index);
    void close();

    static byte[] bytes(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.serializeAsBytes();
    }
    static ItemStack item(byte[] bytes) { return bytes == null || bytes.length == 0 ? null : ItemStack.deserializeBytes(bytes); }
    static StackValue value(byte[] bytes) { return bytes == null || bytes.length == 0 ? StackValue.EMPTY : new StackValue(bytes); }
    static byte[] nullable(StackValue value) { return value.isEmpty() ? null : value.bytes(); }

    final class Online implements InventoryAccess {
        private final FoliaInvSeePlugin plugin;
        private final Player player;
        private final boolean ender;
        private final boolean enforceExempt;
        private final java.util.function.BooleanSupplier authorized;
        Online(FoliaInvSeePlugin plugin, Player player, boolean ender, boolean enforceExempt) {
            this(plugin, player, ender, enforceExempt, () -> true);
        }
        Online(FoliaInvSeePlugin plugin, Player player, boolean ender, boolean enforceExempt,
               java.util.function.BooleanSupplier authorized) {
            this.plugin = plugin; this.player = player; this.ender = ender; this.enforceExempt = enforceExempt;
            this.authorized = authorized;
        }
        public UUID id() { return player.getUniqueId(); }
        private Inventory inventory() {
            if (!authorized.getAsBoolean()) throw new PlayerTasks.NotExecutedException("Permission was revoked before mutation");
            if (enforceExempt && player.hasPermission("invseeplusplus.exempt." + (ender ? "endersee" : "invsee")))
                throw new PlayerTasks.NotExecutedException("Target is exempt from inspection");
            return ender ? player.getEnderChest() : player.getInventory();
        }
        public CompletableFuture<byte[][]> read() {
            return PlayerTasks.call(plugin, player, () -> {
                ItemStack[] contents = inventory().getContents();
                byte[][] snapshot = new byte[contents.length][];
                for (int i = 0; i < contents.length; i++) snapshot[i] = bytes(contents[i]);
                return snapshot;
            });
        }
        public SlotEndpoint slot(int index) {
            return new SlotEndpoint() {
                public String description() { return id() + ":" + (ender ? "ender" : "main") + ":" + index; }
                public CompletableFuture<StackValue> read() {
                    return PlayerTasks.call(plugin, player, () -> value(bytes(inventory().getItem(index))));
                }
                public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                    return PlayerTasks.call(plugin, player, () -> {
                        Inventory inventory = inventory();
                        if (!ender && !EquipmentRules.accepts(index, item(update.bytes()))) return false;
                        ItemStack current = inventory.getItem(index);
                        if (!java.util.Objects.equals(item(bytes(current)), item(expected.bytes()))) return false;
                        inventory.setItem(index, item(update.bytes()));
                        // Bukkit swallows persistence exceptions; success is independently
                        // checked and fsynced before the transfer observes this CAS.
                        player.saveData();
                        return true;
                    }).thenCompose(changed -> changed
                            ? plugin.verifySavedSlot(id(), ender, index, nullable(update)).thenApply(unused -> true)
                            : CompletableFuture.completedFuture(false)).handle((changed, error) -> {
                        if (error == null) return changed;
                        Throwable cause = error;
                        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                        if (cause instanceof PlayerTasks.NotExecutedException) return false;
                        throw new java.util.concurrent.CompletionException(cause);
                    });
                }
            };
        }
        public void close() { }
    }

    final class Offline implements InventoryAccess {
        private final UUID id;
        private final OfflineStore.Lease lease;
        private final boolean ender;
        Offline(UUID id, OfflineStore.Lease lease, boolean ender) { this.id = id; this.lease = lease; this.ender = ender; }
        public UUID id() { return id; }
        public CompletableFuture<byte[][]> read() { return lease.read(ender); }
        public SlotEndpoint slot(int index) {
            return new SlotEndpoint() {
                public String description() { return id + ":" + (ender ? "ender" : "main") + ":" + index; }
                public CompletableFuture<StackValue> read() { return lease.read(ender).thenApply(items -> value(items[index])); }
                public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue update) {
                    if (!ender && !EquipmentRules.accepts(index, item(update.bytes()))) return CompletableFuture.completedFuture(false);
                    return lease.compareAndSet(ender, index, nullable(expected), nullable(update));
                }
                public CompletableFuture<Void> acknowledgeCommit() { return lease.acknowledgeCommit(); }
            };
        }
        public void close() { lease.close(); }
    }
}
