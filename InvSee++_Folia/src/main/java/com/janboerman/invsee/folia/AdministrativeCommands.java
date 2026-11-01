package com.janboerman.invsee.folia;

import com.janboerman.invsee.folia.offline.OfflineNameResolver;
import com.janboerman.invsee.folia.offline.OfflineStore;
import com.janboerman.invsee.folia.patch.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.*;

/** Authorized administrative changes are whole-target CAS patches, never live shared inventories. */
final class AdministrativeCommands implements CommandExecutor {
    private static final UUID CONSOLE = new UUID(0, 0);
    private final FoliaInvSeePlugin plugin;
    private final PatchService patches;
    private final OfflineStore offline;
    private final OfflineNameResolver names;
    private final Map<UUID, UUID> actors = new ConcurrentHashMap<>();
    private final Semaphore pendingCommands = new Semaphore(16);

    AdministrativeCommands(FoliaInvSeePlugin plugin, PatchService patches, OfflineStore offline, OfflineNameResolver names) {
        this.plugin = plugin; this.patches = patches; this.offline = offline; this.names = names;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        boolean ender = name.startsWith("ender");
        PatchType type = name.endsWith("give") ? PatchType.GIVE : name.endsWith("clear") ? PatchType.CLEAR : PatchType.CLONE;
        String permission = "invseeplusplus." + type.name().toLowerCase(Locale.ROOT) + "." + (ender ? "enderchest" : "inventory");
        if (!plugin.acceptsOperations() || !sender.hasPermission(permission)) { sender.sendMessage("No permission or plugin is stopping."); return true; }
        if (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("Only a player or server console may issue inventory administration commands."); return true;
        }
        final String targetInput, sourceInput;
        final ItemStack giveItem;
        final int giveAmount;
        final String clearMaterial;
        final Integer clearAmount;
        try {
            if (type == PatchType.GIVE) {
                if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("/" + label + " <target> <material> [amount]; component/NBT arguments are not yet supported and are never silently ignored");
                Material material = Material.matchMaterial(args[1]);
                if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("Unknown item; component/NBT syntax is not supported");
                giveItem = new ItemStack(material);
                giveAmount = args.length == 3 ? positive(args[2]) : 1;
                if (giveAmount > 1_000_000) throw new IllegalArgumentException("Requested amount exceeds the bounded command limit");
                targetInput = args[0]; sourceInput = null; clearMaterial = null; clearAmount = null;
            } else if (type == PatchType.CLEAR) {
                if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("/" + label + " <target> [material] [amount]");
                targetInput = args[0]; sourceInput = null; giveItem = null; giveAmount = 0;
                Material filter = args.length >= 2 ? Material.matchMaterial(args[1]) : null;
                if (args.length >= 2 && filter == null) throw new IllegalArgumentException("Unknown material");
                clearMaterial = filter == null ? null : filter.name();
                clearAmount = args.length == 3 ? nonnegative(args[2]) : null;
            } else {
                if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("/" + label + " <source> [target]");
                if (args.length == 1 && !(sender instanceof Player)) throw new IllegalArgumentException("Console must specify a target");
                sourceInput = args[0]; targetInput = args.length == 2 ? args[1] : ((Player) sender).getUniqueId().toString();
                giveItem = null; giveAmount = 0; clearMaterial = null; clearAmount = null;
            }
        } catch (IllegalArgumentException invalid) { sender.sendMessage(invalid.getMessage()); return true; }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : CONSOLE;
        UUID operation = UUID.randomUUID();
        if (!pendingCommands.tryAcquire()) { sender.sendMessage("Inventory administration is busy; retry shortly."); return true; }
        if (actors.putIfAbsent(actor, operation) != null) {
            pendingCommands.release(); sender.sendMessage("Wait for your current inventory command to finish."); return true;
        }
        if (sender instanceof Player player && !plugin.prepareAdministrativeActor(player)) {
            actors.remove(actor, operation); pendingCommands.release();
            sender.sendMessage("Wait for your current GUI transfer to finish."); return true;
        }
        CompletableFuture<UUID> targetFuture = CompletableFuture.completedFuture(null).thenCompose(unused -> resolve(targetInput));
        CompletableFuture<UUID> sourceFuture = sourceInput == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.completedFuture(null).thenCompose(unused -> resolve(sourceInput));
        targetFuture.thenCombine(sourceFuture, Resolved::new).thenCompose(resolved -> {
            if (resolved.target.equals(resolved.source)) return CompletableFuture.completedFuture("Source and target are identical; nothing changed.");
            if (plugin.isQuarantined(resolved.target) || (resolved.source != null && plugin.isQuarantined(resolved.source)))
                return CompletableFuture.failedFuture(new IllegalStateException("Inventory has an unresolved recovery record"));
            if (!plugin.reserveTarget(resolved.target, operation))
                return CompletableFuture.failedFuture(new IllegalStateException("Another operation is pending for this target"));
            CompletableFuture<Source> snapshot = resolved.source == null
                    ? CompletableFuture.completedFuture(new Source(null, false))
                    : readSource(sender, resolved.source, ender, permission, operation);
            return snapshot.thenCompose(source -> open(sender, resolved.target, ender, permission).thenCompose(handle -> {
                CompletableFuture<PatchService.Result> work = use(handle, access -> access.read().thenCompose(before -> {
                    byte[][] after = switch (type) {
                        case GIVE -> InventoryPatchPlanner.give(before, ender, giveItem, giveAmount);
                        case CLEAR -> InventoryPatchPlanner.clear(before, clearMaterial, clearAmount);
                        case CLONE -> InventoryPatchPlanner.cloneContents(source.contents, before);
                    };
                    PatchEndpoint destination = admitted(handle.access.patch(), sender, permission, ender,
                            handle.requireBypass || source.requireBypass, resolved.source);
                    return patches.apply(operation, actor, type, resolved.target, ender ? InventoryKind.ENDER : InventoryKind.MAIN,
                            destination, before, after);
                }));
                return work.thenApply(result -> {
                    if (result.outcome() == PatchService.Outcome.RECOVERY_REQUIRED) plugin.quarantinePatch(result.record());
                    return type + " " + operation + ": " + result.outcome()
                            + (result.outcome() == PatchService.Outcome.CONFLICT ? " (target changed; no stale overwrite applied)" : "");
                });
            })).whenComplete((unused, error) -> plugin.releaseTarget(resolved.target, operation));
        }).whenComplete((message, error) -> {
            actors.remove(actor, operation);
            pendingCommands.release();
            plugin.replyTo(sender, error == null ? message : "Command refused/failed: " + describe(error));
        });
        return true;
    }

    private CompletableFuture<Source> readSource(CommandSender sender, UUID source, boolean ender, String permission, UUID operation) {
        if (!plugin.reserveTarget(source, operation))
            return CompletableFuture.failedFuture(new IllegalStateException("Clone source is busy or quarantined; retry after it settles"));
        return CompletableFuture.completedFuture(null).thenCompose(unused -> open(sender, source, ender, permission))
                .thenCompose(handle -> use(handle, access -> access.read().thenApply(contents -> new Source(contents, handle.requireBypass))))
                .whenComplete((unused, error) -> plugin.releaseTarget(source, operation));
    }
    private static <T> CompletableFuture<T> use(Handle handle, java.util.function.Function<InventoryAccess, CompletableFuture<T>> work) {
        return CompletableFuture.completedFuture(null).thenCompose(unused -> work.apply(handle.access))
                .whenComplete((result, error) -> handle.access.close());
    }
    private PatchEndpoint admitted(PatchEndpoint delegate, CommandSender sender, String permission, boolean ender,
                                   boolean requireBypass, UUID source) {
        return new PatchEndpoint() {
            public String description() { return delegate.description() + (source == null ? "" : ";clone-source=" + source); }
            public CompletableFuture<Boolean> compareAndSet(byte[][] before, byte[][] after) {
                return authorize(sender, permission, ender, requireBypass).thenCompose(allowed ->
                        allowed && (source == null || !plugin.isQuarantined(source))
                                ? delegate.compareAndSet(before, after) : CompletableFuture.completedFuture(false));
            }
            public CompletableFuture<Void> acknowledgeCommit() { return delegate.acknowledgeCommit(); }
        };
    }
    private CompletableFuture<Handle> open(CommandSender sender, UUID target, boolean ender, String permission) {
        return permissions(sender, permission, ender).thenCompose(bypass -> {
            if (plugin.isQuarantined(target)) return CompletableFuture.failedFuture(new IllegalStateException("Inventory became quarantined"));
            Player live = Bukkit.getPlayer(target);
            if (live != null) return CompletableFuture.completedFuture(new Handle(new InventoryAccess.Online(plugin, live, ender, !bypass), bypass));
            if (!bypass) return CompletableFuture.failedFuture(new SecurityException("Offline access requires explicit exemption-bypass permission"));
            return offline.open(target).thenApply(lease -> new Handle(new InventoryAccess.Offline(target, lease, ender), true));
        });
    }
    private CompletableFuture<Boolean> permissions(CommandSender sender, String permission, boolean ender) {
        java.util.function.Supplier<Boolean> check = () -> {
            if (!plugin.acceptsOperations() || !sender.hasPermission(permission)) throw new PlayerTasks.NotExecutedException("Command permission revoked");
            return sender.hasPermission("invseeplusplus.bypass-exempt." + (ender ? "endersee" : "invsee"));
        };
        if (sender instanceof Player player) return PlayerTasks.call(plugin, player, check);
        return PlayerTasks.callGlobal(plugin, check);
    }
    private CompletableFuture<Boolean> authorize(CommandSender sender, String permission, boolean ender, boolean requireBypass) {
        return permissions(sender, permission, ender).handle((bypass, error) -> {
            if (error == null) return !requireBypass || bypass;
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof PlayerTasks.NotExecutedException) return false;
            throw new CompletionException(cause);
        });
    }
    private CompletableFuture<UUID> resolve(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return CompletableFuture.completedFuture(online.getUniqueId());
        try { return CompletableFuture.completedFuture(UUID.fromString(input)); }
        catch (IllegalArgumentException ignored) { }
        return names.resolve(input).thenApply(found -> found.orElseThrow(() -> new IllegalArgumentException("Unknown or ambiguous player name: " + input + "; use UUID")));
    }
    private static int positive(String text) { int value = Integer.parseInt(text); if (value <= 0) throw new IllegalArgumentException("Amount must be positive"); return value; }
    private static int nonnegative(String text) { int value = Integer.parseInt(text); if (value < 0) throw new IllegalArgumentException("Amount cannot be negative"); return value; }
    private static String describe(Throwable error) { while (error.getCause() != null && error instanceof CompletionException) error = error.getCause(); return String.valueOf(error.getMessage()); }
    private record Handle(InventoryAccess access, boolean requireBypass) { }
    private record Source(byte[][] contents, boolean requireBypass) { }
    private record Resolved(UUID target, UUID source) { }
}
