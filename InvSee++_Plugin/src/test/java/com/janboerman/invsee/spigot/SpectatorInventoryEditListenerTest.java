package com.janboerman.invsee.spigot;

import com.janboerman.invsee.spigot.api.EnderSpectatorInventory;
import com.janboerman.invsee.spigot.api.MainSpectatorInventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpectatorInventoryEditListenerTest {
    @Test void deniesReadOnlyMainDrag() { assertTrue(drag(MainSpectatorInventory.class, false, 0).isCancelled()); }
    @Test void deniesReadOnlyEnderDrag() { assertTrue(drag(EnderSpectatorInventory.class, false, 26).isCancelled()); }
    @Test void deniesMixedDrag() { assertTrue(drag(MainSpectatorInventory.class, false, 0, 60).isCancelled()); }
    @Test void permitsBottomOnlyDrag() { assertFalse(drag(MainSpectatorInventory.class, false, 54, 60).isCancelled()); }
    @Test void permitsAuthorizedMainDrag() { assertFalse(drag(MainSpectatorInventory.class, true, 0).isCancelled()); }
    @Test void permitsAuthorizedEnderDrag() { assertFalse(drag(EnderSpectatorInventory.class, true, 0).isCancelled()); }
    @Test void ignoresOrdinaryInventory() { assertFalse(drag(Inventory.class, false, 0).isCancelled()); }

    private InventoryDragEvent drag(Class<? extends Inventory> type, boolean allowed, int... slots) {
        Inventory top = proxy(type, (method, args) -> method.equals("getSize") ? 54 : null);
        HumanEntity player = proxy(HumanEntity.class, (method, args) -> method.equals("hasPermission") ? allowed && args[0].equals(type == EnderSpectatorInventory.class
                ? "invseeplusplus.endersee.edit" : "invseeplusplus.invsee.edit") : null);
        InventoryView view = proxy(InventoryView.class, (method, args) -> {
            switch (method) {
                case "getTopInventory": return top;
                case "getPlayer": return player;
                case "convertSlot": return (Integer) args[0];
                default: return null;
            }
        });
        Map<Integer, ItemStack> changed = new HashMap<>();
        for (int slot : slots) changed.put(slot, new ItemStack() {});
        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack() {}, false, changed);
        new SpectatorInventoryEditListener().onDrag(event);
        return event;
    }

    private interface Invocation { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.invoke(method.getName(), args)));
    }
}
