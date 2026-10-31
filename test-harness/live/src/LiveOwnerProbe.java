package harness;

import com.janboerman.invsee.folia.transfer.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Isolated integration probe of TransferService and real Folia entity scheduling.
 * Does NOT exercise InvSee GUI, production InventoryAccess, or offline NBT adapter. */
public final class LiveOwnerProbe extends JavaPlugin {
    private volatile boolean running;
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return false;
        if (command.getName().equals("probepermission") && args.length == 3) {
            Player player = Bukkit.getPlayerExact(args[0]);
            if (player == null) return false;
            owned(player, () -> { player.addAttachment(this, args[1], Boolean.parseBoolean(args[2])); return true; })
                .whenComplete((v,e) -> getLogger().info("Permission test attachment: " + (e == null ? "applied" : e)));
            return true;
        }
        if (command.getName().equals("codeccheck")) {
            var plugin = Bukkit.getPluginManager().getPlugin("InvSeePlusPlusFolia");
            Bukkit.getAsyncScheduler().runNow(this, ignored -> {
                try {
                    Class<?> checks = plugin.getClass().getClassLoader().loadClass("com.janboerman.invsee.folia.offline.OfflineCodecRuntimeChecks");
                    Object report = checks.getMethod("run").invoke(null);
                    getLogger().info("CODEC: " + report);
                    Object storage = checks.getMethod("runStorage", JavaPlugin.class).invoke(null, plugin);
                    getLogger().info("STORAGE: " + storage);
                } catch (Throwable e) { getLogger().log(java.util.logging.Level.SEVERE, "CODEC/STORAGE FAILED", e); }
            });
            return true;
        }
        if (args.length != 3 || running) return false;
        Player[] players = Arrays.stream(args).map(Bukkit::getPlayerExact).toArray(Player[]::new);
        if (Arrays.stream(players).anyMatch(Objects::isNull) || Arrays.stream(players).map(Player::getUniqueId).distinct().count() != 3) {
            getLogger().warning("BLOCKED: require three different connected test players"); return true;
        }
        running = true;
        CompletableFuture.runAsync(() -> runProbe(players)).whenComplete((v, e) -> {
            running = false;
            if (e != null) getLogger().severe("FAIL/INCONCLUSIVE: " + e + "; inspect test-player slot 0 before retrying");
        });
        return true;
    }
    private <T> CompletableFuture<T> owned(Player p, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            if (p.getScheduler().run(this, ignored -> {
                try {
                    if (!p.isOnline() || Bukkit.getPlayer(p.getUniqueId()) != p || !Bukkit.isOwnedByCurrentRegion(p))
                        throw new IllegalStateException("retired or incorrect owner");
                    future.complete(task.get());
                } catch (Throwable e) { future.completeExceptionally(e); }
            }, () -> future.completeExceptionally(new IllegalStateException("retired"))) == null)
                future.completeExceptionally(new IllegalStateException("scheduler rejected"));
        } catch (Throwable e) { future.completeExceptionally(e); }
        return future.orTimeout(15, TimeUnit.SECONDS);
    }
    private final class Endpoint implements SlotEndpoint {
        final Player p;
        Endpoint(Player p) { this.p = p; }
        public String description() { return p.getUniqueId() + ":probe:0"; }
        StackValue snapshot() {
            ItemStack i = p.getInventory().getItem(0);
            return i == null || i.getType().isAir() ? StackValue.EMPTY : new StackValue(i.serializeAsBytes());
        }
        public CompletableFuture<StackValue> read() { return owned(p, this::snapshot); }
        public CompletableFuture<Boolean> compareAndSet(StackValue old, StackValue value) {
            return owned(p, () -> {
                if (!snapshot().equals(old)) return false;
                p.getInventory().setItem(0, value.isEmpty() ? null : ItemStack.deserializeBytes(value.bytes()));
                return true;
            });
        }
    }
    private void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private void runProbe(Player[] players) {
        Endpoint[] ep = Arrays.stream(players).map(Endpoint::new).toArray(Endpoint[]::new);
        for (Player p : players) owned(p, () -> {
            for (Player other : players) if (p != other) require(!Bukkit.isOwnedByCurrentRegion(other), "players share region; separate by thousands of blocks");
            return true;
        }).join();
        for (Endpoint e : ep) require(e.read().join().isEmpty(), "slot 0 must be empty for every test player");
        StackValue marker = owned(players[0], () -> new StackValue(new ItemStack(Material.DIAMOND, 64).serializeAsBytes())).join();
        require(ep[0].compareAndSet(StackValue.EMPTY, marker).join(), "seed conflict");
        Map<UUID, TransferRecord> records = new ConcurrentHashMap<>();
        TransferService service = new TransferService(new TransferJournal() {
            public CompletableFuture<Optional<TransferRecord>> load(UUID id) { return CompletableFuture.completedFuture(Optional.ofNullable(records.get(id))); }
            public CompletableFuture<List<TransferRecord>> loadAll() { return CompletableFuture.completedFuture(new ArrayList<>(records.values())); }
            public CompletableFuture<Void> write(TransferRecord r) { records.put(r.operationId(), r); return CompletableFuture.completedFuture(null); }
        });
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            CompletableFuture<TransferService.Result> a = service.transfer(UUID.randomUUID(), ep[0], ep[1], marker);
            CompletableFuture<TransferService.Result> b = service.transfer(UUID.randomUUID(), ep[0], ep[2], marker);
            CompletableFuture.allOf(a,b).orTimeout(30, TimeUnit.SECONDS).join();
            require(Set.of(a.join().outcome(),b.join().outcome()).equals(Set.of(TransferService.Outcome.COMPLETED,TransferService.Outcome.CONFLICT)), "unexpected outcome");
            require(ep[0].read().join().isEmpty(), "source not empty");
            boolean one = ep[1].read().join().equals(marker), two = ep[2].read().join().equals(marker);
            require(one ^ two, "conservation failure");
            require(service.transfer(UUID.randomUUID(),ep[one ? 1 : 2],ep[0],marker).join().outcome() == TransferService.Outcome.COMPLETED, "return failure");
            nanos[i] = System.nanoTime()-start;
        }
        require(ep[0].compareAndSet(marker, StackValue.EMPTY).join(), "cleanup conflict");
        Arrays.sort(nanos);
        getLogger().info("PASS real-player TransferService probe: 100 three-owner races, p50="+nanos[50]/1e6+"ms p95="+nanos[95]/1e6+"ms; NOT GUI/offline/durable-journal coverage");
    }
}
