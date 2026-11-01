package com.janboerman.invsee.folia.offline;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftRegistry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import java.io.IOException;
import java.util.List;

/** Test-only. Invoke inside an initialized Folia server; never shipped in the production artifact. */
public final class OfflineCodecRuntimeChecks {
    private OfflineCodecRuntimeChecks() {}
    public static String run() throws Exception {
        NbtInventoryCodec codec = new NbtInventoryCodec(CraftRegistry.getMinecraftRegistry());
        CompoundTag root = new CompoundTag();
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        root.putInt("DataVersion", version);
        root.putString("Dimension", "minecraft:the_end");
        root.putInt("XpTotal", 12345);
        root.putFloat("Health", 17.5F);
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("owned-by-other-plugin", "must survive unchanged");
        unrelated.putLongArray("arbitrary", new long[] {1L, Long.MAX_VALUE, Long.MIN_VALUE});
        root.put("BukkitValues", unrelated);
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(meta -> {
            meta.displayName(Component.text("Offline custom sword"));
            meta.lore(List.of(Component.text("Preserve components"), Component.text("Line two")));
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(NamespacedKey.fromString("invsee_test:payload"), PersistentDataType.STRING, "opaque payload");
        });
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 17);
        ItemStack shield = new ItemStack(Material.SHIELD);
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        ItemStack saddle = new ItemStack(Material.SADDLE);
        ListTag main = new ListTag();
        CompoundTag swordTag = item(sword); swordTag.putByte("Slot", (byte) 0); main.add(swordTag);
        root.put("Inventory", main);
        ListTag ender = new ListTag();
        CompoundTag diamondTag = item(diamonds); diamondTag.putByte("Slot", (byte) 26); ender.add(diamondTag);
        root.put("EnderItems", ender);
        CompoundTag equipment = new CompoundTag();
        equipment.put("feet", item(boots)); equipment.put("offhand", item(shield));
        equipment.put("body", item(boots)); equipment.put("saddle", item(saddle));
        root.put("equipment", equipment);

        byte[] serialized = NbtInventoryCodec.compress(root);
        CompoundTag decoded = codec.decode(serialized);
        require(decoded.equals(root), "whole player NBT roundtrip");
        byte[][] contents = codec.read(decoded, false);
        require(contents.length == 43, "main slot count");
        equalItem(sword, contents[0], "sword components");
        equalItem(boots, contents[36], "armor");
        equalItem(shield, contents[40], "offhand");
        equalItem(boots, contents[41], "body");
        equalItem(saddle, contents[42], "saddle");
        equalItem(diamonds, codec.read(decoded, true)[26], "ender");
        require(codec.matches(decoded, false, 0, sword.serializeAsBytes()), "semantic compare");
        require(codec.matches(decoded, false, 5, new byte[0]), "empty byte array compare");
        require(!codec.matches(decoded, false, 0, diamonds.serializeAsBytes()), "stale compare");
        CompoundTag unchanged = decoded.copy(); unchanged.remove("Inventory");
        codec.replace(decoded, false, 0, diamonds.serializeAsBytes());
        CompoundTag after = decoded.copy(); after.remove("Inventory");
        require(unchanged.equals(after), "all unrelated root/ender/equipment NBT preserved");
        equalItem(diamonds, codec.read(decoded, false)[0], "replacement");
        codec.replace(decoded, false, 0, new byte[0]);
        require(codec.read(decoded, false)[0] == null, "empty replacement removes slot");
        codec.replace(decoded, true, 26, sword.serializeAsBytes());
        equalItem(sword, codec.read(decoded, true)[26], "ender replacement components");
        require(codec.read(decoded, false)[0] == null, "ender does not resurrect main");
        codec.replace(decoded, false, 40, null);
        require(codec.read(decoded, false)[40] == null, "offhand removal");
        equalItem(boots, codec.read(decoded, false)[36], "other equipment untouched");
        CompoundTag absent = new CompoundTag(); absent.putInt("DataVersion", version);
        require(codec.read(absent, false).length == 43 && codec.read(absent, true).length == 27, "absent lists supported");
        CompoundTag old = root.copy(); old.putInt("DataVersion", version - 1);
        fails(() -> codec.decode(NbtInventoryCodec.compress(old)), "old version rejected");
        CompoundTag future = root.copy(); future.putInt("DataVersion", version + 1);
        fails(() -> codec.decode(NbtInventoryCodec.compress(future)), "future version rejected");
        CompoundTag malformed = root.copy(); malformed.putString("Inventory", "not a list");
        fails(() -> codec.read(malformed, false), "malformed inventory rejected");
        CompoundTag duplicate = root.copy(); duplicate.getListOrEmpty("Inventory").add(swordTag.copy());
        fails(() -> codec.read(duplicate, false), "duplicate slot rejected");
        fails(() -> NbtInventoryCodec.decompress(new byte[] {1, 2, 3}), "corrupt compression rejected");
        return "OFFLINE_CODEC_CHECKS_PASS: components, enchantment, lore, PDC, armor/offhand/body/saddle, main/ender, unrelated NBT, stale/empty/malformed/version checks";
    }
    /** Invoke on a TEST async thread only: waits on the store's own bounded IO executor. */
    public static String runStorage(org.bukkit.plugin.java.JavaPlugin plugin) throws Exception {
        java.lang.reflect.Field field = plugin.getClass().getDeclaredField("offline");
        field.setAccessible(true);
        OfflineStore store = (OfflineStore) field.get(plugin);
        java.util.UUID id = java.util.UUID.randomUUID();
        java.nio.file.Path file = ((org.bukkit.craftbukkit.CraftServer) plugin.getServer()).getHandle().playerIo.getPlayerDir().toPath().resolve(id + ".dat");
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putString("invsee-test-sentinel", "untouched");
        java.nio.file.Files.write(file, NbtInventoryCodec.compress(root), java.nio.file.StandardOpenOption.CREATE_NEW);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 17);
        ItemStack gold = new ItemStack(Material.GOLD_INGOT, 9);
        try (OfflineStore.Lease lease = store.open(id).get(10, java.util.concurrent.TimeUnit.SECONDS)) {
            require(lease.read(false).get().length == 43, "storage main read");
            require(lease.read(true).get().length == 27, "storage ender read");
            require(store.open(id).isCompletedExceptionally(), "second offline session excluded across kinds");
            var busy = prelogin(id);
            store.onPreLogin(busy); store.onPreLoginFinished(busy);
            require(busy.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "login blocked while editing");
            require(lease.compareAndSet(false, 4, new byte[0], diamonds.serializeAsBytes()).get(), "main CAS");
            require(AtomicPlayerDataFile.needsRecovery(file), "journal retained until transfer ack");
            lease.acknowledgeCommit().get();
            require(!AtomicPlayerDataFile.needsRecovery(file), "journal acknowledged");
            require(lease.compareAndSet(true, 26, null, gold.serializeAsBytes()).get(), "ender CAS");
            lease.acknowledgeCommit().get();
            equalItem(diamonds, lease.read(false).get()[4], "main survived ender save");
            equalItem(gold, lease.read(true).get()[26], "ender persisted");
            require(!lease.compareAndSet(false, 4, null, gold.serializeAsBytes()).get(), "stale CAS rejected without write");
            require("untouched".equals(NbtInventoryCodec.decompress(java.nio.file.Files.readAllBytes(file)).getStringOr("invsee-test-sentinel", "")), "unrelated field survived both commits");
            byte[][] originalMain = lease.read(false).get();
            byte[][] originalEnder = lease.read(true).get();
            byte[][] batchMain = lease.read(false).get();
            batchMain[4] = null;
            batchMain[7] = gold.serializeAsBytes();
            batchMain[36] = new ItemStack(Material.NETHERITE_BOOTS).serializeAsBytes();
            batchMain[40] = new ItemStack(Material.SHIELD).serializeAsBytes();
            batchMain[41] = new ItemStack(Material.IRON_HORSE_ARMOR).serializeAsBytes();
            batchMain[42] = new ItemStack(Material.SADDLE).serializeAsBytes();
            require(lease.compareAndSetContents(false, originalMain, batchMain).get(), "whole main atomic patch");
            require(AtomicPlayerDataFile.needsRecovery(file), "whole patch retains journal until ack");
            lease.acknowledgeCommit().get();
            store.verifySavedContents(id, false, batchMain).get();
            equalItem(gold, lease.read(false).get()[7], "whole main replacement persisted");
            equalItem(gold, lease.read(true).get()[26], "whole main preserves ender");
            require(!lease.compareAndSetContents(false, originalMain, new byte[43][]).get(), "whole CAS rejects any stale slot");
            require(!AtomicPlayerDataFile.needsRecovery(file), "stale whole CAS creates no journal");
            require(lease.compareAndSetContents(false, batchMain, new byte[43][]).get(), "whole inventory clear");
            lease.acknowledgeCommit().get();
            for (byte[] item : lease.read(false).get()) require(item == null, "clear includes armor/offhand/body/saddle");
            byte[][] batchEnder = lease.read(true).get();
            batchEnder[26] = null; batchEnder[3] = diamonds.serializeAsBytes();
            require(lease.compareAndSetContents(true, originalEnder, batchEnder).get(), "whole ender atomic patch");
            lease.acknowledgeCommit().get();
            store.verifySavedContents(id, true, batchEnder).get();
            require(lease.read(false).get()[7] == null, "whole ender preserves cleared main");
            require(lease.compareAndSetContents(false, new byte[43][], originalMain).get(), "restore main test fixture");
            lease.acknowledgeCommit().get();
            require(lease.compareAndSetContents(true, batchEnder, originalEnder).get(), "restore ender test fixture");
            lease.acknowledgeCommit().get();
            require(lease.compareAndSetContents(false, new byte[42][], new byte[43][]).isCompletedExceptionally(), "batch main rejects truncated snapshot");
            require(store.verifySavedContents(id, true, new byte[28][]).isCompletedExceptionally(), "batch verify rejects extra slots");
            require("untouched".equals(NbtInventoryCodec.decompress(java.nio.file.Files.readAllBytes(file)).getStringOr("invsee-test-sentinel", "")), "unrelated NBT survives whole patches");
            lease.closeAsync().get();
        }
        var admitted = prelogin(id);
        store.onPreLogin(admitted); store.onPreLoginFinished(admitted);
        require(admitted.getLoginResult() == org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "admission resumes after close");
        require(store.open(id).isCompletedExceptionally(), "connecting player excludes offline open");
        var duplicate = prelogin(id);
        store.onPreLogin(duplicate); store.onPreLoginFinished(duplicate);
        require(duplicate.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "duplicate admission rejected");
        require(store.open(id).isCompletedExceptionally(), "duplicate denial did not release first login token");
        store.onConnectionClose(connectionClose(id));
        try (var lease = store.open(id).get()) { require(lease.read(true).get()[26] != null, "close releases final save fence"); lease.closeAsync().get(); }
        var cancelled = prelogin(id);
        store.onPreLogin(cancelled);
        cancelled.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Test cancellation"));
        store.onPreLoginFinished(cancelled);
        try (var lease = store.open(id).get()) { require(lease.read(false).get()[4] != null, "cancelled prelogin releases without close event"); lease.closeAsync().get(); }
        var changedIdentity = prelogin(id);
        store.onPreLogin(changedIdentity);
        changedIdentity.setPlayerProfile(org.bukkit.Bukkit.createProfile(java.util.UUID.randomUUID(), "OfflineTest"));
        store.onPreLoginFinished(changedIdentity);
        require(changedIdentity.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "changed UUID fails closed");
        try (var lease = store.open(id).get()) { lease.closeAsync().get(); }
        try (var lease = store.open(id).get()) {
            var reallowed = prelogin(id); store.onPreLogin(reallowed);
            reallowed.allow(); // Simulate a later HIGHEST listener attempting to bypass the busy denial.
            store.onPreLoginFinished(reallowed);
            require(reallowed.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "reallow cannot bypass offline fence");
            lease.closeAsync().get();
        }
        // Deterministically exercise bounded lease queuing and close while accepted IO is waiting.
        try (var lease = store.open(id).get()) {
            java.lang.reflect.Field ioField = OfflineStore.class.getDeclaredField("io"); ioField.setAccessible(true);
            var io = (java.util.concurrent.ThreadPoolExecutor) ioField.get(store);
            var started = new java.util.concurrent.CountDownLatch(2);
            var unblock = new java.util.concurrent.CountDownLatch(1);
            Runnable block = () -> { started.countDown(); try { unblock.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } };
            io.execute(block); io.execute(block);
            require(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "test IO workers occupied");
            var reads = new java.util.ArrayList<java.util.concurrent.CompletableFuture<byte[][]>>();
            try {
                for (int i = 0; i < 16; i++) reads.add(lease.read(i % 2 == 0));
                require(lease.read(false).isCompletedExceptionally(), "per-lease pending limit enforced");
                lease.close();
                require(!lease.closeAsync().isDone(), "closing lease retains accepted IO fence");
                var draining = prelogin(id); store.onPreLogin(draining); store.onPreLoginFinished(draining);
                require(draining.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED, "login blocked during IO drain");
            } finally { unblock.countDown(); }
            for (var read : reads) read.get(10, java.util.concurrent.TimeUnit.SECONDS);
            lease.closeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var lease = store.open(id).get()) { lease.closeAsync().get(); }
        java.nio.file.Files.delete(file);
        return "OFFLINE_STORAGE_CHECKS_PASS: main+ender CAS preservation, stale compare, durable ack, exclusive lease, connecting/duplicate/cancelled prelogin and close fence, bounded queue/drain, atomic whole-inventory patch/clear";
    }
    private static com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent connectionClose(java.util.UUID id) throws Exception {
        Class<com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent> type = com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent.class;
        try {
            return type.getConstructor(java.util.UUID.class, String.class, java.net.InetAddress.class, boolean.class)
                    .newInstance(id, "OfflineTest", java.net.InetAddress.getLoopbackAddress(), true);
        } catch (NoSuchMethodException newerApi) {
            return type.getConstructor(java.util.UUID.class, String.class, java.net.SocketAddress.class, boolean.class)
                    .newInstance(id, "OfflineTest", new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), true);
        }
    }
    @SuppressWarnings("deprecation")
    private static org.bukkit.event.player.AsyncPlayerPreLoginEvent prelogin(java.util.UUID id) {
        return new org.bukkit.event.player.AsyncPlayerPreLoginEvent("OfflineTest", java.net.InetAddress.getLoopbackAddress(), id);
    }
    private static CompoundTag item(ItemStack item) throws IOException {
        CompoundTag tag = NbtInventoryCodec.decompress(item.serializeAsBytes()); tag.remove("DataVersion"); return tag;
    }
    private static void equalItem(ItemStack expected, byte[] actual, String label) {
        require(actual != null && expected.equals(ItemStack.deserializeBytes(actual)), label);
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    private static void fails(Checked action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
