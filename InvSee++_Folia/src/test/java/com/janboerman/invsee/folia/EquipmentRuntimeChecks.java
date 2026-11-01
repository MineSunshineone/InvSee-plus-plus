package com.janboerman.invsee.folia;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Explicit acceptance-probe entry point. Requires a running, fully initialized 26.2 server. */
public final class EquipmentRuntimeChecks {
    private EquipmentRuntimeChecks() {}
    public static String run() {
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        check(EquipmentRules.accepts(36, boots), "boots feet");
        check(!EquipmentRules.accepts(39, boots), "boots rejected from head");
        check(EquipmentRules.accepts(39, new ItemStack(Material.CARVED_PUMPKIN)), "pumpkin head component");
        check(EquipmentRules.accepts(38, new ItemStack(Material.ELYTRA)), "elytra chest component");
        check(EquipmentRules.accepts(40, new ItemStack(Material.STONE, 64)), "ordinary offhand stack");
        check(!EquipmentRules.accepts(42, new ItemStack(Material.SADDLE)), "animal saddle restriction");
        check(!EquipmentRules.accepts(41, new ItemStack(Material.DIAMOND_HORSE_ARMOR)), "horse body restriction");
        for (int index : new int[]{41, 42}) {
            EquipmentSlot slot = index == 41 ? EquipmentSlot.BODY : EquipmentSlot.SADDLE;
            net.minecraft.world.item.ItemStack custom = CraftItemStack.asNMSCopy(new ItemStack(Material.STONE));
            custom.set(DataComponents.EQUIPPABLE, Equippable.builder(slot).setAllowedEntities(EntityTypes.PLAYER).build());
            ItemStack item = CraftItemStack.asBukkitCopy((net.minecraft.world.item.ItemInstance) custom);
            check(EquipmentRules.accepts(index, item), "custom player component " + slot);
            check(EquipmentRules.capacity(index, item) == 1, "single equipment capacity " + slot);
            item.setAmount(2);
            check(!EquipmentRules.accepts(index, item), "overstack equipment rejected " + slot);
        }
        check(EquipmentRules.accepts(42, null), "equipment clearing");
        return "PASS EquipmentRuntimeChecks: 14 native component/amount assertions";
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError("Equipment runtime check failed: " + message);
    }
}
