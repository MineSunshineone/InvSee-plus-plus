package com.janboerman.invsee.folia;

import com.janboerman.invsee.folia.menu.ProtectedMenu;
import com.janboerman.invsee.folia.offline.OfflineStore;
import com.janboerman.invsee.folia.offline.OfflineNameResolver;
import com.janboerman.invsee.folia.transfer.*;
import com.janboerman.invsee.folia.patch.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** Separate Folia path: no legacy API, shared live inventories, or global entity work. */
public final class FoliaInvSeePlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> targetBusy = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> openRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> quarantined = new ConcurrentHashMap<>();
    private ExecutorService journalExecutor;
    private TransferJournal journal;
    private TransferService transfers;
    private HeldEscrowRecovery heldRecovery;
    private PatchJournal patchJournal;
    private PatchService patches;
    private OfflineStore offline;
    private OfflineNameResolver names;
    private volatile boolean accepting;

    @Override public void onEnable() {
        if (!Set.of("26.2", "26.3").contains(Bukkit.getMinecraftVersion())) {
            throw new IllegalStateException("This adapter permits only Folia 26.2/26.3; refusing an unverified server version");
        }
        if (getServer().getPluginManager().isPluginEnabled("InvSeePlusPlus")) {
            throw new IllegalStateException("Remove legacy InvSeePlusPlus and its addons before loading the Folia adapter");
        }
        journalExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), runnable -> { Thread t = new Thread(runnable, "InvSee-Folia-Journal"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        try {
            journal = new FileJournal(getDataFolder().toPath().resolve("transactions"), journalExecutor);
            transfers = new TransferService(journal);
            heldRecovery = new HeldEscrowRecovery(journal, transfers);
            patchJournal = new FilePatchJournal(getDataFolder().toPath().resolve("patches"), journalExecutor);
            patches = new PatchService(patchJournal);
            offline = new OfflineStore(this);
            names = new OfflineNameResolver(this);
        } catch (java.io.IOException error) {
            journalExecutor.shutdown();
            throw new IllegalStateException("Cannot initialize durable inventory storage", error);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("invsee")).setExecutor(this);
        Objects.requireNonNull(getCommand("endersee")).setExecutor(this);
        Objects.requireNonNull(getCommand("invseerecovery")).setExecutor(this);
        AdministrativeCommands administration = new AdministrativeCommands(this, patches, offline, names);
        for (String command : new String[]{"invgive", "endergive", "invclear", "enderclear", "invclone", "enderclone"})
            Objects.requireNonNull(getCommand(command)).setExecutor(administration);
        heldRecovery.reconcileCompletedClaims().thenCompose(unused -> journal.loadUnresolved())
                .thenCombine(patchJournal.loadUnresolved(), (records, patchRecords) -> {
                    for (TransferRecord record : records) if (!record.state().terminal()) quarantine(record);
                    for (PatchRecord record : patchRecords) quarantinePatch(record);
                    return true;
                }).whenComplete((ready, error) -> {
                    if (error != null) { getLogger().log(Level.SEVERE, "Cannot inspect recovery ledger; inventory access remains disabled", error); return; }
                    if (isEnabled()) accepting = true;
                });
        getLogger().info("Folia 26.2 adapter enabled. Left selects full stack, right selects half; compatible merges and shift transfers are supported.");
    }

    @Override public void onDisable() {
        accepting = false;
        openRequests.clear();
        // Native ProtectedMenu intrinsically rejects vanilla edits even after
        // listeners are unregistered; stillValid closes it on the next owner tick.
        for (Session session : sessions.values()) session.close();
        sessions.clear();
        // Shutdown-only barriers: no region waits during commands or transfers.
        // Fail observations of accepted owner tasks first. Never wait on an entity
        // here: another plugin can disable us from an event inside our owner task.
        // Such in-progress mutations are explicitly quarantined as uncertain.
        PlayerTasks.stop();
        CompletableFuture<Void> transferDrain = transfers == null ? CompletableFuture.completedFuture(null) : transfers.stopAccepting();
        CompletableFuture<Void> patchDrain = patches == null ? CompletableFuture.completedFuture(null) : patches.stopAccepting();
        CompletableFuture.allOf(transferDrain, patchDrain).join();
        if (offline != null) offline.close();
        if (names != null) names.close();
        if (journalExecutor != null) {
            journalExecutor.shutdown();
            boolean interrupted = false;
            while (!journalExecutor.isTerminated()) {
                try { journalExecutor.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("invseerecovery")) {
            if (!sender.hasPermission("invseeplusplus.recovery")) { sender.sendMessage("No recovery-report permission."); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("patches")) {
                patchJournal.loadUnresolved().whenComplete((records, error) -> {
                    if (error != null) { reply(sender, "Cannot read patch recovery: " + describe(error)); return; }
                    StringBuilder report = new StringBuilder("Unresolved administrative patches: ").append(records.size());
                    int count = 0;
                    for (PatchRecord record : records) { if (count++ == 10) break; report.append("\n").append(patchDescription(record)); }
                    reply(sender, report.toString());
                });
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("patch")) {
                final UUID id;
                try { id = UUID.fromString(args[1]); }
                catch (IllegalArgumentException error) { sender.sendMessage("Invalid patch UUID."); return true; }
                patchJournal.load(id).whenComplete((record, error) -> reply(sender, error != null
                        ? "Cannot read patch: " + describe(error) : record.map(FoliaInvSeePlugin::patchDescription).orElse("Patch not found")));
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
                if (!(sender instanceof Player receiver)) { sender.sendMessage("Run the escrow claim in game with a free storage slot."); return true; }
                UUID original;
                try { original = UUID.fromString(args[1]); }
                catch (IllegalArgumentException error) { sender.sendMessage("Invalid transaction UUID."); return true; }
                claimHeld(receiver, original);
                return true;
            }
            if (args.length > 1) { sender.sendMessage("/invseerecovery [transaction UUID] or /invseerecovery claim <held transaction UUID>"); return true; }
            if (args.length == 1) {
                final UUID id;
                try { id = UUID.fromString(args[0]); }
                catch (IllegalArgumentException error) { sender.sendMessage("Invalid transaction UUID."); return true; }
                journal.load(id).whenComplete((record, error) -> {
                    if (error != null) reply(sender, "Cannot read recovery record: " + describe(error));
                    else if (record.isEmpty()) reply(sender, "No record for " + id);
                    else reply(sender, recoveryDescription(record.get()));
                });
            } else {
                journal.loadUnresolved().whenComplete((records, error) -> {
                    if (error != null) { reply(sender, "Cannot read recovery journal: " + describe(error)); return; }
                    StringBuilder report = new StringBuilder("Unresolved transfers: ").append(records.size());
                    int count = 0;
                    for (TransferRecord record : records) {
                        if (count++ == 10) { report.append("\nMore records exist; inspect the transaction directory."); break; }
                        report.append("\n").append(recoveryDescription(record));
                    }
                    report.append("\nUse /invseerecovery patches for administrative give/clear/clone records. No automatic replay; back up data and journals before reconciliation.");
                    reply(sender, report.toString());
                });
            }
            return true;
        }
        if (!(sender instanceof Player viewer)) { sender.sendMessage("Run this command in game."); return true; }
        boolean ender = command.getName().equalsIgnoreCase("endersee");
        String type = ender ? "endersee" : "invsee";
        if (!accepting || !viewer.hasPermission("invseeplusplus." + type + ".view")) { viewer.sendMessage("No permission or plugin is stopping."); return true; }
        if (args.length != 1) { viewer.sendMessage("/" + label + " <player name | UUID>. Unknown or ambiguous offline names require a UUID."); return true; }
        if (!isEmpty(viewer.getItemOnCursor())) { viewer.sendMessage("Empty your cursor before opening the inspector."); return true; }
        Session prior = sessions.get(viewer.getUniqueId());
        if (prior != null && prior.busy) { viewer.sendMessage("Wait for your current transfer to finish."); return true; }
        Player online = Bukkit.getPlayerExact(args[0]);
        UUID targetId;
        if (online != null) targetId = online.getUniqueId();
        else {
            try { targetId = UUID.fromString(args[0]); }
            catch (IllegalArgumentException error) {
                org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[0]);
                if (cached == null) {
                    UUID lookupId = UUID.randomUUID();
                    openRequests.put(viewer.getUniqueId(), lookupId);
                    names.resolve(args[0]).whenComplete((resolved, failure) -> PlayerTasks.call(this, viewer, () -> {
                        if (!openRequests.remove(viewer.getUniqueId(), lookupId) || !accepting) return null;
                        if (failure != null) viewer.sendMessage("Offline name lookup failed; use UUID: " + describe(failure));
                        else if (resolved.isEmpty()) viewer.sendMessage("Name is unknown or ambiguous; use the exact UUID.");
                        else onCommand(viewer, command, label, new String[]{resolved.get().toString()});
                        return null;
                    }));
                    return true;
                }
                targetId = cached.getUniqueId();
            }
            online = Bukkit.getPlayer(targetId);
        }
        if (targetId.equals(viewer.getUniqueId())) { viewer.sendMessage("Inspect another player; self-transfers are not supported."); return true; }
        boolean bypass = viewer.hasPermission("invseeplusplus.bypass-exempt." + type);
        if (online == null && !bypass) { viewer.sendMessage("Offline access requires bypass-exempt permission because offline permissions cannot be verified safely."); return true; }
        final UUID resolvedTargetId = targetId;
        UUID request = UUID.randomUUID();
        openRequests.put(viewer.getUniqueId(), request);
        CompletableFuture<InventoryAccess> accessFuture = online == null
                ? offline.open(resolvedTargetId).thenApply(lease -> new InventoryAccess.Offline(resolvedTargetId, lease, ender))
                : CompletableFuture.completedFuture(new InventoryAccess.Online(this, online, ender, !bypass));
        accessFuture.thenCompose(access -> access.read().handle((contents, error) -> {
            if (error != null) { access.close(); throw new CompletionException(error); }
            return new Loaded(access, contents);
        })).whenComplete((loaded, error) -> {
            if (error != null) { reply(viewer, "Could not open inventory: " + describe(error)); openRequests.remove(viewer.getUniqueId(), request); return; }
            PlayerTasks.call(this, viewer, () -> {
                if (!accepting || loaded.access.loginPending() || !openRequests.remove(viewer.getUniqueId(), request) || !isEmpty(viewer.getItemOnCursor())
                        || !viewer.hasPermission("invseeplusplus." + type + ".view")
                        || (bypass && !viewer.hasPermission("invseeplusplus.bypass-exempt." + type))) {
                    openRequests.remove(viewer.getUniqueId(), request);
                    loaded.access.close(); return null;
                }
                Session session = new Session(viewer, loaded.access, ender, bypass);
                if (session.closed || loaded.access.loginPending()) { session.close(); return null; }
                session.render(loaded.contents);
                if (!ProtectedMenu.open(this, viewer, session.inventory, ender ? "Folia Ender Inspector" : "Folia Inventory Inspector")) { session.close(); return null; }
                Session old = sessions.put(viewer.getUniqueId(), session);
                if (old != null) old.close();
                session.startRefresh();
                viewer.sendMessage("Select a source (left: all; right: half), then an empty/compatible destination. Shift auto-selects a destination; clock refreshes.");
                return null;
            }).exceptionally(failure -> { loaded.access.close(); return null; });
        });
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer) || session.viewer != viewer || !accepting) return;
        if (sessions.get(viewer.getUniqueId()) != session || session.closed) return;
        if (!session.authorized()) { viewer.closeInventory(); return; }
        if (session.busy) { viewer.sendMessage("A transfer is pending; wait for its result."); return; }
        int raw = event.getRawSlot();
        if (raw == 49) { session.selection = null; refresh(session); return; }
        boolean target = raw >= 0 && raw < 54;
        int slot = target ? raw : event.getSlot();
        if (raw < 0 || (target && slot >= session.contents.length) || (!target && (slot < 0 || slot >= 36))) return;
        if (!viewer.hasPermission("invseeplusplus." + (session.ender ? "endersee" : "invsee") + ".edit")) { viewer.sendMessage("This inventory is read-only for you."); return; }
        byte[] value = target ? session.contents[slot] : InventoryAccess.bytes(viewer.getInventory().getItem(slot));
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            if (!target) return;
            int viewerSlot = click == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
            if (viewerSlot < 0 || viewerSlot > 40) return;
            byte[] own = InventoryAccess.bytes(viewer.getInventory().getItem(viewerSlot));
            if (value != null) {
                session.selection = select(true, slot, value, false);
                transfer(session, false, viewerSlot, own);
            } else if (own != null) {
                session.selection = select(false, viewerSlot, own, false);
                transfer(session, true, slot, null);
            }
            return;
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (value == null) return;
            session.selection = select(target, slot, value, false);
            byte[][] destinations;
            int limit;
            if (target) {
                ItemStack[] own = viewer.getInventory().getStorageContents();
                destinations = new byte[own.length][];
                for (int i = 0; i < own.length; i++) destinations[i] = InventoryAccess.bytes(own[i]);
                limit = destinations.length;
            } else { destinations = session.contents; limit = session.ender ? 27 : 36; }
            int destination = findDestination(destinations, InventoryAccess.item(value), limit);
            if (destination < 0) { viewer.sendMessage("No compatible destination has capacity."); session.selection = null; return; }
            transfer(session, !target, destination, destinations[destination]);
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            viewer.sendMessage("That action is refused. Use left/right, shift, or an empty/compatible hotbar destination; drag, drop and creative clone are disabled."); return;
        }
        if (session.selection != null && session.selection.target != target) {
            transfer(session, target, slot, value);
            return;
        }
        if (value == null) { viewer.sendMessage("Select an occupied source first, then a destination in the other inventory."); return; }
        session.selection = select(target, slot, value, click == ClickType.RIGHT);
        ItemStack moved = InventoryAccess.item(session.selection.transferred);
        viewer.sendMessage("Selected " + moved.getAmount() + " × " + moved.getType() + ". Click an empty or compatible destination in the other inventory.");
    }

    private static Selection select(boolean target, int slot, byte[] bytes, boolean half) {
        ItemStack full = InventoryAccess.item(bytes);
        ItemStack moved = full.clone(); moved.setAmount(half ? (full.getAmount() + 1) / 2 : full.getAmount());
        ItemStack remainder = full.clone(); remainder.setAmount(full.getAmount() - moved.getAmount());
        return new Selection(target, slot, bytes, InventoryAccess.bytes(remainder), InventoryAccess.bytes(moved));
    }

    private static int findDestination(byte[][] contents, ItemStack moved, int limit) {
        int empty = -1;
        for (int i = 0; i < Math.min(contents.length, limit); i++) {
            ItemStack item = InventoryAccess.item(contents[i]);
            if (item == null) { if (empty == -1) empty = i; }
            else if (item.isSimilar(moved) && item.getAmount() < item.getMaxStackSize()) return i;
        }
        return empty;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            sessions.remove(session.viewer.getUniqueId(), session); session.close();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        openRequests.remove(event.getPlayer().getUniqueId());
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) session.close();
    }

    private void transfer(Session session, boolean destinationTarget, int destinationSlot, byte[] destinationBefore) {
        Selection selected = session.selection;
        if (selected == null) return;
        ItemStack moved = InventoryAccess.item(selected.transferred);
        ItemStack destinationItem = InventoryAccess.item(destinationBefore);
        if (destinationItem != null && !destinationItem.isSimilar(moved)) {
            session.viewer.sendMessage("Occupied-slot swaps are refused; select an empty or compatible destination."); return;
        }
        int maximum = destinationTarget && session.ender ? moved.getMaxStackSize() : EquipmentRules.capacity(destinationSlot, moved);
        int capacity = maximum - (destinationItem == null ? 0 : destinationItem.getAmount());
        if (capacity <= 0) { session.viewer.sendMessage("Destination stack is full."); return; }
        moved.setAmount(Math.min(moved.getAmount(), capacity));
        ItemStack original = InventoryAccess.item(selected.expected);
        ItemStack remainder = original.clone(); remainder.setAmount(original.getAmount() - moved.getAmount());
        ItemStack destinationAfter = moved.clone();
        if (destinationItem != null) destinationAfter.setAmount(destinationItem.getAmount() + moved.getAmount());
        Selection selection = new Selection(selected.target, selected.slot, selected.expected,
                InventoryAccess.bytes(remainder), InventoryAccess.bytes(moved));
        UUID operationId = UUID.randomUUID();
        if (targetBusy.putIfAbsent(session.access.id(), operationId) != null) { session.viewer.sendMessage("Another operation is pending for this target. Try again."); return; }
        if (targetBusy.putIfAbsent(session.viewer.getUniqueId(), operationId) != null) {
            targetBusy.remove(session.access.id(), operationId);
            session.viewer.sendMessage("Another operation is pending for your inventory. Try again."); return;
        }
        if (quarantined.containsKey(session.access.id()) || quarantined.containsKey(session.viewer.getUniqueId())) {
            releaseTransferSlots(session, operationId);
            session.viewer.sendMessage("An unresolved transfer locks one of these inventories. Ask an operator to inspect /invseerecovery.");
            return;
        }
        if (!session.beginTransfer()) { releaseTransferSlots(session, operationId); return; }
        session.selection = null;
        InventoryAccess viewerAccess = new InventoryAccess.Online(this, session.viewer, false, false);
        SlotEndpoint source = admittedSource((selection.target ? session.access : viewerAccess).slot(selection.slot), session);
        SlotEndpoint destination = (destinationTarget ? session.access : viewerAccess).slot(destinationSlot);
        CompletableFuture<TransferService.Result> transfer;
        try {
            transfer = transfers.transfer(operationId, source, destination, InventoryAccess.value(selection.expected),
                    InventoryAccess.value(selection.remainder), InventoryAccess.value(selection.transferred),
                    InventoryAccess.value(destinationBefore), InventoryAccess.value(InventoryAccess.bytes(destinationAfter)));
        } catch (RuntimeException failure) {
            releaseTransferSlots(session, operationId);
            session.finishTransfer();
            session.viewer.sendMessage("Transfer refused before mutation: " + describe(failure));
            return;
        }
        transfer.whenComplete((result, error) -> {
            if (error != null || result.outcome() == TransferService.Outcome.RECOVERY_REQUIRED) {
                quarantine(session.access.id(), operationId); quarantine(session.viewer.getUniqueId(), operationId);
            }
            releaseTransferSlots(session, operationId);
            // close() and completion can run on different owners; release once only.
            session.finishTransfer();
            String message = error == null ? "Transfer " + operationId + ": " + result.outcome() : "Transfer " + operationId + " requires review: " + describe(error);
            if (error != null) getLogger().log(Level.SEVERE, message, error);
            PlayerTasks.call(this, session.viewer, () -> {
                session.viewer.sendMessage(message);
                if (!session.closed && sessions.get(session.viewer.getUniqueId()) == session) refresh(session);
                return null;
            }).exceptionally(failure -> null);
        });
    }

    private void releaseTransferSlots(Session session, UUID operation) {
        targetBusy.remove(session.access.id(), operation);
        targetBusy.remove(session.viewer.getUniqueId(), operation);
    }

    /** Authorization linearizes immediately before source reservation, not at command lookup. */
    private SlotEndpoint admittedSource(SlotEndpoint delegate, Session session) {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return new SlotEndpoint() {
            public String description() { return delegate.description(); }
            public CompletableFuture<StackValue> read() { return delegate.read(); }
            public CompletableFuture<Void> acknowledgeCommit() { return delegate.acknowledgeCommit(); }
            public CompletableFuture<Boolean> compareAndSet(StackValue expected, StackValue replacement) {
                // Rollback must remain possible after close/revocation; only admission
                // is permission checked. Already-admitted operations finish or recover.
                if (!first.getAndSet(false)) return delegate.compareAndSet(expected, replacement);
                return PlayerTasks.call(FoliaInvSeePlugin.this, session.viewer, () -> {
                    if (!session.authorized()) { session.viewer.closeInventory(); return false; }
                    return accepting && !session.closed && sessions.get(session.viewer.getUniqueId()) == session
                            && session.viewer.hasPermission("invseeplusplus." + (session.ender ? "endersee" : "invsee") + ".edit");
                })
                        .thenCompose(allowed -> allowed ? delegate.compareAndSet(expected, replacement)
                                : CompletableFuture.completedFuture(false));
            }
        };
    }

    boolean acceptsOperations() { return accepting; }
    boolean reserveTarget(UUID target, UUID operation) {
        if (!accepting || targetBusy.putIfAbsent(target, operation) != null) return false;
        // Previous operations publish quarantine before releasing their account.
        if (!accepting || quarantined.containsKey(target)) {
            targetBusy.remove(target, operation); return false;
        }
        return true;
    }
    void releaseTarget(UUID target, UUID operation) { targetBusy.remove(target, operation); }
    boolean isQuarantined(UUID target) { return quarantined.containsKey(target); }
    boolean prepareAdministrativeActor(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.busy) return false;
        if (session != null) player.closeInventory();
        return true;
    }
    void replyTo(CommandSender sender, String message) { reply(sender, message); }
    void quarantinePatch(PatchRecord record) { quarantine(record.target(), record.id()); }
    CompletableFuture<Void> verifySavedContents(UUID id, boolean ender, byte[][] expected) {
        return offline.verifySavedContents(id, ender, expected);
    }

    CompletableFuture<Void> verifySavedSlot(UUID id, boolean ender, int slot, byte[] expected) {
        return offline.verifySavedSlot(id, ender, slot, expected);
    }

    private void quarantine(UUID player, UUID operation) {
        quarantined.compute(player, (id, reasons) -> {
            if (reasons == null) reasons = ConcurrentHashMap.newKeySet();
            reasons.add(operation); return reasons;
        });
    }
    private void releaseQuarantine(UUID operation) {
        for (UUID player : quarantined.keySet()) quarantined.computeIfPresent(player, (id, reasons) -> {
            reasons.remove(operation); return reasons.isEmpty() ? null : reasons;
        });
    }
    private void claimHeld(Player receiver, UUID original) {
        if (!accepting) { receiver.sendMessage("Plugin is not accepting operations."); return; }
        Session session = sessions.get(receiver.getUniqueId());
        if (session != null && session.busy) { receiver.sendMessage("Wait for your active transfer."); return; }
        int slot = receiver.getInventory().firstEmpty();
        if (slot < 0 || slot >= 36) { receiver.sendMessage("Make an empty storage slot before claiming escrow."); return; }
        UUID child = UUID.randomUUID();
        if (targetBusy.putIfAbsent(receiver.getUniqueId(), child) != null) { receiver.sendMessage("Your inventory has a pending operation."); return; }
        receiver.closeInventory();
        SlotEndpoint destination = new InventoryAccess.Online(this, receiver, false, false,
                () -> receiver.hasPermission("invseeplusplus.recovery")).slot(slot);
        heldRecovery.claim(original, child, destination).whenComplete((result, error) -> {
            try {
                if (error != null) { reply(receiver, "Escrow claim refused: " + describe(error)); return; }
                if (result.outcome() == TransferService.Outcome.COMPLETED) releaseQuarantine(original);
                else if (result.outcome() == TransferService.Outcome.RECOVERY_REQUIRED) quarantine(result.record());
                reply(receiver, "Escrow claim " + child + ": " + result.outcome() + ". Only proven held escrow is claimable; uncertain transfers are never replayed.");
            } finally { targetBusy.remove(receiver.getUniqueId(), child); }
        });
    }

    private void quarantine(TransferRecord record) {
        for (String endpoint : new String[]{record.source(), record.destination()}) {
            try { quarantine(UUID.fromString(endpoint.substring(0, 36)), record.operationId()); }
            catch (RuntimeException error) { getLogger().severe("Unrecognized recovery endpoint: " + endpoint); }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void creative(InventoryCreativeEvent event) {
        if (sessions.containsKey(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    private void refresh(Session session) {
        if (session.closed || session.busy) return;
        if (!session.authorized()) { session.viewer.closeInventory(); return; }
        if (!session.refreshing.compareAndSet(false, true)) return;
        long revision = session.revision;
        session.access.read().whenComplete((contents, error) -> PlayerTasks.call(this, session.viewer, () -> {
            if (session.closed || session.busy || session.revision != revision || sessions.get(session.viewer.getUniqueId()) != session) return null;
            if (!session.authorized()) {
                session.viewer.closeInventory(); return null;
            }
            if (error != null) { session.viewer.sendMessage("Refresh refused: " + describe(error)); session.viewer.closeInventory(); }
            else {
                Selection selected = session.selection;
                if (selected != null && selected.target && (selected.slot >= contents.length
                        || !Arrays.equals(selected.expected, contents[selected.slot]))) {
                    session.selection = null;
                    session.viewer.sendMessage("Selected target slot changed; select it again.");
                }
                session.render(contents);
            }
            return null;
        }).whenComplete((unused, ignored) -> session.refreshing.set(false)));
    }
    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) PlayerTasks.call(this, player, () -> { player.sendMessage(message); return null; });
        else getLogger().info(message);
    }
    private static boolean isEmpty(ItemStack item) { return InventoryAccess.bytes(item) == null; }
    private static String patchDescription(PatchRecord record) {
        return record.id() + " | " + record.type() + " | " + record.state() + " | " + java.time.Instant.ofEpochMilli(record.updatedAt())
                + "\ntarget=" + record.target() + " kind=" + record.kind() + " actor=" + record.actor()
                + "\n" + record.description() + " | " + record.detail()
                + "\nFull before/after snapshots are retained. Never automatically overwrite a newer player inventory.";
    }
    private static String recoveryDescription(TransferRecord record) {
        return record.operationId() + " | " + record.state() + " | " + java.time.Instant.ofEpochMilli(record.updatedAt())
                + "\nsource=" + record.source() + " -> destination=" + record.destination()
                + "\nescrow bytes=" + record.item().bytes().length + " | " + record.detail()
                + "\nAmbiguous records must be compared with BOTH inventories; do not blindly give or replay their items.";
    }
    private static String describe(Throwable error) {
        while (error.getCause() != null && (error instanceof CompletionException || error instanceof ExecutionException)) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private record Loaded(InventoryAccess access, byte[][] contents) { }
    private record Selection(boolean target, int slot, byte[] expected, byte[] remainder, byte[] transferred) { }

    private final class Session implements InventoryHolder {
        final Player viewer;
        final InventoryAccess access;
        final boolean ender;
        final boolean requireBypass;
        final Inventory inventory;
        byte[][] contents;
        Selection selection;
        volatile boolean busy, closed;
        volatile long revision;
        private boolean released;
        private Runnable releaseOperation;
        private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();
        private io.papermc.paper.threadedregions.scheduler.ScheduledTask refreshTask;
        Session(Player viewer, InventoryAccess access, boolean ender, boolean bypass) {
            this.viewer = viewer; this.access = access; this.ender = ender;
            this.requireBypass = bypass || access instanceof InventoryAccess.Offline;
            inventory = Bukkit.createInventory(this, 54, "Folia Inspector");
            if (access instanceof InventoryAccess.Offline offlineAccess) {
                offlineAccess.loginRequested().thenRun(() -> {
                    // Invalidate admission immediately, but let any admitted protocol finish.
                    close();
                    PlayerTasks.call(FoliaInvSeePlugin.this, viewer, () -> {
                        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                            viewer.closeInventory();
                            viewer.sendMessage("The player is joining. Offline editing has ended; reopen after they join to edit online.");
                        }
                        return null;
                    }).exceptionally(error -> null);
                });
            }
        }
        public Inventory getInventory() { return inventory; }
        /** Called only on the viewer owner thread. Opening grants are revalidated. */
        boolean authorized() {
            String type = ender ? "endersee" : "invsee";
            return viewer.hasPermission("invseeplusplus." + type + ".view")
                    && (!requireBypass || viewer.hasPermission("invseeplusplus.bypass-exempt." + type));
        }
        void render(byte[][] snapshot) {
            int length = Math.min(snapshot.length, ender ? 27 : 43);
            contents = new byte[length][];
            inventory.clear();
            for (int i = 0; i < length; i++) {
                contents[i] = snapshot[i] == null ? null : snapshot[i].clone();
                inventory.setItem(i, InventoryAccess.item(contents[i]));
            }
            ItemStack refresh = new ItemStack(Material.CLOCK);
            ItemMeta meta = refresh.getItemMeta(); meta.setDisplayName("Refresh snapshot / clear selection"); refresh.setItemMeta(meta);
            inventory.setItem(49, refresh);
        }
        void startRefresh() {
            // Only online targets can change independently. Offline leases are
            // exclusive and do not need periodic playerdata disk reads.
            if (!(access instanceof InventoryAccess.Online)) return;
            refreshTask = viewer.getScheduler().runAtFixedRate(FoliaInvSeePlugin.this,
                    task -> refresh(this), this::close, 20L, 20L);
            if (refreshTask == null) close();
        }
        synchronized boolean beginTransfer() {
            if (closed || busy || released || access.loginPending()) return false;
            try { releaseOperation = access.beginOperation(); }
            catch (IllegalStateException refused) { close(); return false; }
            revision++;
            busy = true; return true;
        }
        synchronized void close() {
            closed = true;
            if (refreshTask != null) refreshTask.cancel();
            releaseIfReady();
        }
        synchronized void finishTransfer() {
            busy = false;
            if (releaseOperation != null) {
                Runnable release = releaseOperation; releaseOperation = null; release.run();
            }
            releaseIfReady();
        }
        private void releaseIfReady() { if (closed && !busy && !released) { released = true; access.close(); } }
    }
}
