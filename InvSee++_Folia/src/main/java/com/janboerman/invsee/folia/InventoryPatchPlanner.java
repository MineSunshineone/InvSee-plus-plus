package com.janboerman.invsee.folia;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;
import java.util.Objects;

/** Pure snapshot planning: no live inventory references and no partial-capacity result. */
public final class InventoryPatchPlanner {
    private InventoryPatchPlanner() { }

    public static byte[][] give(byte[][] before, boolean ender, ItemStack item, int amount) {
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Cannot give an empty item");
        return give(before, ender, item, amount, BUKKIT);
    }
    public static byte[][] clear(byte[][] before, String materialOrNull, Integer amountOrNull) {
        String material = materialOrNull == null ? null : Material.valueOf(materialOrNull.toUpperCase(Locale.ROOT)).name();
        return clear(before, material, amountOrNull, BUKKIT);
    }
    public static byte[][] cloneContents(byte[][] source, byte[][] destination) {
        return cloneContents(source, destination, BUKKIT);
    }

    interface StackOps<T> {
        T decode(byte[] bytes);
        byte[] encode(T item);
        T withAmount(T item, int amount);
        int amount(T item);
        int maximum(T item);
        boolean similar(T first, T second);
        String material(T item);
        boolean accepts(int slot, T item);
    }
    private static final StackOps<ItemStack> BUKKIT = new StackOps<>() {
        public ItemStack decode(byte[] bytes) { return bytes == null || bytes.length == 0 ? null : ItemStack.deserializeBytes(bytes); }
        public byte[] encode(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.serializeAsBytes(); }
        public ItemStack withAmount(ItemStack item, int amount) { ItemStack copy = item.clone(); copy.setAmount(amount); return copy; }
        public int amount(ItemStack item) { return item.getAmount(); }
        public int maximum(ItemStack item) { return item.getMaxStackSize(); }
        public boolean similar(ItemStack first, ItemStack second) { return first.isSimilar(second); }
        public String material(ItemStack item) { return item.getType().name(); }
        public boolean accepts(int slot, ItemStack item) { return EquipmentRules.accepts(slot, item); }
    };

    static <T> byte[][] give(byte[][] before, boolean ender, T item, int amount, StackOps<T> ops) {
        shape(before);
        if (before.length != (ender ? 27 : 43)) throw new IllegalArgumentException("Inventory kind does not match snapshot");
        if (item == null || amount <= 0 || ops.amount(item) <= 0 || ops.maximum(item) <= 0)
            throw new IllegalArgumentException("Item and amount must be positive");
        byte[][] result = copy(before);
        int remaining = amount, storage = ender ? 27 : 36;
        for (int i = 0; i < storage && remaining > 0; i++) {
            T existing = decode(result[i], ops);
            if (existing == null || !ops.similar(existing, item)) continue;
            int room = Math.max(0, Math.min(ops.maximum(existing), ops.maximum(item)) - ops.amount(existing));
            int moved = Math.min(room, remaining);
            if (moved > 0) { result[i] = ops.encode(ops.withAmount(existing, ops.amount(existing) + moved)); remaining -= moved; }
        }
        for (int i = 0; i < storage && remaining > 0; i++) {
            if (decode(result[i], ops) != null) continue;
            int moved = Math.min(ops.maximum(item), remaining);
            result[i] = ops.encode(ops.withAmount(item, moved)); remaining -= moved;
        }
        if (remaining != 0) throw new IllegalArgumentException("Insufficient storage capacity; nothing changed");
        return result;
    }

    static <T> byte[][] clear(byte[][] before, String material, Integer amount, StackOps<T> ops) {
        shape(before);
        if (amount != null && amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        byte[][] result = copy(before);
        long remaining = amount == null ? Long.MAX_VALUE : amount;
        for (int i = 0; i < result.length && remaining > 0; i++) {
            T existing = decode(result[i], ops);
            if (existing == null || material != null && !material.equals(ops.material(existing))) continue;
            int removed = (int) Math.min(remaining, ops.amount(existing));
            if (removed <= 0) throw new IllegalArgumentException("Invalid stack amount");
            result[i] = removed == ops.amount(existing) ? null : ops.encode(ops.withAmount(existing, ops.amount(existing) - removed));
            remaining -= removed;
        }
        return result;
    }

    static <T> byte[][] cloneContents(byte[][] source, byte[][] destination, StackOps<T> ops) {
        shape(source); shape(destination);
        if (source.length != destination.length) throw new IllegalArgumentException("Inventory shapes differ");
        for (int i = 0; i < source.length; i++) {
            T item = decode(source[i], ops);
            if (item != null && (ops.amount(item) <= 0 || ops.amount(item) > ops.maximum(item)
                    || source.length == 43 && !ops.accepts(i, item)))
                throw new IllegalArgumentException("Invalid item for slot " + i);
        }
        return copy(source);
    }
    private static <T> T decode(byte[] bytes, StackOps<T> ops) {
        return bytes == null || bytes.length == 0 ? null : ops.decode(bytes);
    }
    private static void shape(byte[][] contents) {
        Objects.requireNonNull(contents, "snapshot");
        if (contents.length != 43 && contents.length != 27) throw new IllegalArgumentException("Expected 43 main or 27 ender slots");
    }
    private static byte[][] copy(byte[][] source) {
        byte[][] result = new byte[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }
}
