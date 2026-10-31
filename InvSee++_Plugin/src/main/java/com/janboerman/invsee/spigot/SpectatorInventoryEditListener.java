package com.janboerman.invsee.spigot;

import com.janboerman.invsee.spigot.api.EnderSpectatorInventory;
import com.janboerman.invsee.spigot.api.MainSpectatorInventory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public class SpectatorInventoryEditListener implements Listener {

    private static final String
            INVENTORY_EDIT_PERMISSION = "invseeplusplus.invsee.edit",
            ENDERCHEST_EDIT_PERMISSION = "invseeplusplus.endersee.edit";

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getInventory(); //this is the top inventory from the view, not the clicked inventory!
        HumanEntity player = event.getWhoClicked();
        if (cannotEdit(inventory, player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        // Raw slots include both inventories. A bottom-only drag cannot change
        // the inspected inventory and remains available to read-only viewers.
        if (cannotEdit(event.getInventory(), event.getWhoClicked())
                && event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < event.getInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private boolean cannotEdit(Inventory inventory, HumanEntity player) {
        return (inventory instanceof MainSpectatorInventory && !player.hasPermission(INVENTORY_EDIT_PERMISSION))
                || (inventory instanceof EnderSpectatorInventory && !player.hasPermission(ENDERCHEST_EDIT_PERMISSION));
    }
}
