package com.janboerman.invsee.folia;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** Player-equipment placement rules derived from native equippable components, not material names. */
public final class EquipmentRules {
    private EquipmentRules() {}

    /** Amount ceiling for an empty main-inventory slot; zero means the item is incompatible. */
    public static int capacity(int index, ItemStack item) {
        if (index < 0 || index > 42 || item == null || item.getType().isAir()) return 0;
        net.minecraft.world.item.ItemStack nativeItem = CraftItemStack.asNMSCopy(item);
        if (nativeItem.isEmpty()) return 0;
        if (index < 36 || index == 40) return nativeItem.getMaxStackSize();
        Equippable component = nativeItem.get(DataComponents.EQUIPPABLE);
        // Players inherit LivingEntity.canUseSlot=true. ArmorSlot.mayPlace checks
        // the component's slot AND allowed entity types; match both without reading a player.
        return componentCapacity(index, nativeItem.getMaxStackSize(), component == null ? null : component.slot(),
                component != null && component.canBeEquippedBy(EntityTypes.PLAYER.builtInRegistryHolder()));
    }

    /** Includes amount validation. Empty values may clear any valid slot. */
    public static boolean accepts(int index, ItemStack item) {
        if (index < 0 || index > 42) return false;
        if (item == null || item.getType().isAir()) return true;
        return item.getAmount() > 0 && item.getAmount() <= capacity(index, item);
    }

    // Pure component-policy seam for unit tests; runtime extraction above uses native data components.
    static int componentCapacity(int index, int itemMaximum, EquipmentSlot declared, boolean playerAllowed) {
        if (index < 0 || index > 42 || itemMaximum <= 0) return 0;
        if (index < 36 || index == 40) return itemMaximum;
        return declared == slot(index) && playerAllowed ? 1 : 0;
    }

    static EquipmentSlot slot(int index) {
        return switch (index) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            case 40 -> EquipmentSlot.OFFHAND;
            case 41 -> EquipmentSlot.BODY;
            case 42 -> EquipmentSlot.SADDLE;
            default -> throw new IllegalArgumentException("Not an equipment slot: " + index);
        };
    }
}
