package com.janboerman.invsee.folia.offline;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Exact 26.2 layout, verified against the official Folia 26.2 runtime bytecode. */
final class NbtInventoryCodec {
    private static final String[] EQUIPMENT = {"feet", "legs", "chest", "head", "offhand", "body", "saddle"};
    private final RegistryOps<Tag> ops;
    private final int dataVersion;
    NbtInventoryCodec(RegistryAccess registry) {
        ops = registry.createSerializationContext(NbtOps.INSTANCE);
        dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    }
    CompoundTag decode(byte[] bytes) throws IOException {
        CompoundTag root = decompress(bytes);
        if (root.getIntOr("DataVersion", -1) != dataVersion) {
            throw new IOException("Offline data must be saved by this exact Minecraft version first; let the player join once to migrate it");
        }
        return root;
    }
    byte[][] read(CompoundTag root, boolean ender) throws IOException {
        byte[][] result = new byte[ender ? 27 : 43][];
        for (int i = 0; i < result.length; i++) {
            CompoundTag item = itemAt(root, ender, i);
            if (item != null) {
                CompoundTag canonical = canonical(item);
                canonical.putInt("DataVersion", dataVersion);
                result[i] = compress(canonical);
            }
        }
        return result;
    }
    boolean matches(CompoundTag root, boolean ender, int slot, byte[] expected) throws IOException {
        CompoundTag current = itemAt(root, ender, slot);
        return Objects.equals(current == null ? null : canonical(current), expected == null || expected.length == 0 ? null : canonical(decompress(expected)));
    }
    void replace(CompoundTag root, boolean ender, int slot, byte[] replacement) throws IOException {
        validateSlot(ender, slot);
        CompoundTag item = replacement == null || replacement.length == 0 ? null : canonical(decompress(replacement));
        if (!ender && slot >= 36) {
            CompoundTag equipment = compound(root, "equipment");
            if (item == null) equipment.remove(EQUIPMENT[slot - 36]);
            else equipment.put(EQUIPMENT[slot - 36], item);
            root.put("equipment", equipment);
        } else {
            String name = ender ? "EnderItems" : "Inventory";
            ListTag list = list(root, name);
            // Validate all entries before mutation (reject duplicates/corruption, do not silently drop).
            itemAt(root, ender, slot);
            list.removeIf(tag -> ((CompoundTag) tag).getByteOr("Slot", (byte) 0) == (byte) slot);
            if (item != null) { item.putByte("Slot", (byte) slot); list.add(item); }
            root.put(name, list);
        }
    }
    private CompoundTag itemAt(CompoundTag root, boolean ender, int slot) throws IOException {
        validateSlot(ender, slot);
        if (!ender && slot >= 36) {
            CompoundTag equipment = compound(root, "equipment");
            String key = EQUIPMENT[slot - 36];
            if (!equipment.contains(key)) return null;
            return equipment.getCompound(key).orElseThrow(() -> new IOException("Malformed equipment " + key));
        }
        ListTag list = list(root, ender ? "EnderItems" : "Inventory");
        boolean[] seen = new boolean[ender ? 27 : 36];
        CompoundTag found = null;
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag item)) throw new IOException("Malformed inventory entry");
            int index = Byte.toUnsignedInt(item.getByte("Slot").orElseThrow(() -> new IOException("Missing item slot")));
            if (index >= seen.length || seen[index]) throw new IOException("Invalid or duplicate saved slot " + index);
            seen[index] = true;
            if (index == slot) found = item;
        }
        return found;
    }
    private CompoundTag canonical(CompoundTag tag) throws IOException {
        try {
            ItemStack stack = ItemStack.CODEC.parse(ops, tag).getOrThrow();
            if (stack.isEmpty() || stack.getCount() <= 0 || stack.getCount() > stack.getMaxStackSize()) throw new IOException("Invalid saved item count");
            return (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        } catch (RuntimeException ex) { throw new IOException("Invalid offline item", ex); }
    }
    private static void validateSlot(boolean ender, int slot) {
        if (slot < 0 || slot >= (ender ? 27 : 43)) throw new IllegalArgumentException("Invalid slot: " + slot);
    }
    private static CompoundTag compound(CompoundTag root, String key) throws IOException {
        if (!root.contains(key)) return new CompoundTag();
        return root.getCompound(key).orElseThrow(() -> new IOException("Malformed compound " + key));
    }
    private static ListTag list(CompoundTag root, String key) throws IOException {
        if (!root.contains(key)) return new ListTag();
        return root.getList(key).orElseThrow(() -> new IOException("Malformed list " + key));
    }
    static CompoundTag decompress(byte[] bytes) throws IOException {
        if (bytes.length > AtomicPlayerDataFile.MAX_BYTES) throw new IOException("NBT exceeds size limit");
        try { return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(AtomicPlayerDataFile.MAX_BYTES)); }
        catch (RuntimeException ex) { throw new IOException("Malformed/oversized NBT", ex); }
    }
    static byte[] compress(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        if (out.size() > AtomicPlayerDataFile.MAX_BYTES) throw new IOException("NBT exceeds size limit");
        return out.toByteArray();
    }
}
