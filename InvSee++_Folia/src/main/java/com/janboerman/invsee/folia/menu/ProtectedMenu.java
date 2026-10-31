package com.janboerman.invsee.folia.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * A display-only native menu. Its contents are snapshots, never transferable
 * vanilla items. Event cancellation alone is insufficient: a disabled plugin's
 * listeners disappear while its menus may still be open on other regions.
 * These unconditional native guards survive listener removal and shutdown.
 *
 * <p>Paper 26.2 dispatches ordinary InventoryClickEvent before calling clicked;
 * the GUI listener can interpret the gesture and start an explicit transaction.
 * QUICK_CRAFT invokes clicked directly and is also harmless here. We deliberately
 * freeze the bottom inventory too: no vanilla action can touch either side.</p>
 */
public final class ProtectedMenu extends ChestMenu {
    private final JavaPlugin plugin;

    private ProtectedMenu(JavaPlugin plugin, int id, net.minecraft.world.entity.player.Inventory viewer,
                          Container display, int rows) {
        super(menuType(rows), id, viewer, display, rows);
        this.plugin = plugin;
    }

    /** Must be called on the viewer's entity owner, with a viewer-owned detached display. */
    public static boolean open(JavaPlugin plugin, Player viewer, Inventory display, String title) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(title, "title");
        if (!Bukkit.isOwnedByCurrentRegion(viewer)) {
            throw new IllegalStateException("Protected menu must be opened on the viewer's owner");
        }
        if (!plugin.isEnabled() || !viewer.isOnline()) return false;
        int size = display.getSize();
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Display must have between one and six complete rows");
        }
        if (!(display instanceof CraftInventory inventory)) {
            throw new IllegalArgumentException("Display must be a server-created detached inventory");
        }
        ServerPlayer player = ((CraftPlayer) viewer).getHandle();
        return player.openMenu(new SimpleMenuProvider(
                (id, bottom, ignored) -> new ProtectedMenu(plugin, id, bottom, inventory.getInventory(), size / 9),
                Component.literal(title))).isPresent();
    }

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported row count");
        };
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, net.minecraft.world.entity.player.Player player) {
        // Never call super: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW,
        // QUICK_CRAFT and PICKUP_ALL must not expose display snapshots.
    }

    @Override
    public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(net.minecraft.world.entity.player.Player player, int button) {
        return false;
    }

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
        return plugin.isEnabled() && super.stillValid(player);
    }
}
