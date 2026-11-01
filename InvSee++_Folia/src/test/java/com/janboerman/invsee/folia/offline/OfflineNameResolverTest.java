package com.janboerman.invsee.folia.offline;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OfflineNameResolverTest {
    @TempDir Path directory;

    @Test void authoritativeCacheAvoidsReadingCorruptData() throws Exception {
        UUID id = UUID.randomUUID();
        Files.write(directory.resolve(id + ".dat"), new byte[]{1});
        try (OfflineNameResolver resolver = new OfflineNameResolver(directory, name -> Optional.of(id))) {
            assertEquals(Optional.of(id), resolver.resolve("Known").join());
        }
    }
    @Test void resolvesUniqueHistoricalNameCaseInsensitively() throws Exception {
        UUID id = UUID.randomUUID(); save(id, "Known");
        try (OfflineNameResolver resolver = resolver()) {
            assertEquals(Optional.of(id), resolver.resolve("kNoWn").join());
            assertEquals(Optional.empty(), resolver.resolve("missing").join());
        }
    }
    @Test void refusesAmbiguousHistoricalName() throws Exception {
        save(UUID.randomUUID(), "Known"); save(UUID.randomUUID(), "KNOWN");
        try (OfflineNameResolver resolver = resolver()) { assertEquals(Optional.empty(), resolver.resolve("Known").join()); }
    }
    @Test void revalidatesFileAfterIndexWasCached() throws Exception {
        UUID id = UUID.randomUUID(); save(id, "Before");
        try (OfflineNameResolver resolver = resolver()) {
            assertEquals(Optional.of(id), resolver.resolve("Before").join());
            save(id, "After");
            assertEquals(Optional.empty(), resolver.resolve("Before").join());
        }
    }
    @Test void cacheUpdateDuringIndexTakesPrecedence() {
        UUID id = UUID.randomUUID(); AtomicInteger calls = new AtomicInteger();
        try (OfflineNameResolver resolver = new OfflineNameResolver(directory,
                name -> calls.incrementAndGet() == 1 ? Optional.empty() : Optional.of(id))) {
            assertEquals(Optional.of(id), resolver.resolve("Known").join());
        }
    }
    @Test void corruptIndexFailsClosedRatherThanSilentlyDroppingPossibleDuplicate() throws Exception {
        save(UUID.randomUUID(), "Known");
        Files.write(directory.resolve(UUID.randomUUID() + ".dat"), new byte[]{1});
        try (OfflineNameResolver resolver = resolver()) {
            assertThrows(CompletionException.class, () -> resolver.resolve("Known").join());
        }
    }
    @Test void closedResolverRejectsEvenCachedLookups() {
        OfflineNameResolver resolver = new OfflineNameResolver(directory, name -> Optional.of(UUID.randomUUID()));
        resolver.close();
        assertThrows(CompletionException.class, () -> resolver.resolve("Known").join());
    }
    private OfflineNameResolver resolver() { return new OfflineNameResolver(directory, name -> Optional.empty()); }
    private void save(UUID id, String name) throws Exception {
        CompoundTag bukkit = new CompoundTag(); bukkit.putString("lastKnownName", name);
        CompoundTag root = new CompoundTag(); root.put("bukkit", bukkit);
        Files.write(directory.resolve(id + ".dat"), NbtInventoryCodec.compress(root));
    }
}
