package com.janboerman.invsee.folia.offline;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class OfflineAccessCoordinatorTest {
    @Test void offlineBlocksLoginAndSecondInventoryKind() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var lease = coordinator.tryOffline(id);
        assertNotNull(lease);
        assertNull(coordinator.tryLogin(id));
        assertNull(coordinator.tryOffline(id));
        lease.close();
        assertNotNull(coordinator.tryLogin(id));
    }
    @Test void loginBlocksOfflineUntilFinalClose() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var login = coordinator.tryLogin(id);
        assertNull(coordinator.tryOffline(id));
        assertNull(coordinator.tryLogin(id));
        login.close();
        var offline = coordinator.tryOffline(id);
        login.close(); // late duplicate callback must not release the new owner
        assertNull(coordinator.tryLogin(id));
        offline.close();
        assertNotNull(coordinator.tryLogin(id));
    }
    @Test void simultaneousLoginAndOfflineHaveExactlyOneWinner() throws Exception {
        var coordinator = new OfflineAccessCoordinator();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 200; i++) {
                UUID id = UUID.randomUUID();
                var start = new CountDownLatch(1);
                var offline = executor.submit(() -> { start.await(); return coordinator.tryOffline(id); });
                var login = executor.submit(() -> { start.await(); return coordinator.tryLogin(id); });
                start.countDown();
                var a = offline.get(); var b = login.get();
                assertNotEquals(a == null, b == null);
                if (a != null) a.close(); else b.close();
                assertFalse(coordinator.isOwned(id));
            }
        }
    }
    @Test void shutdownRejectsAdmissionsButDoesNotInvalidateExistingFence() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var lease = coordinator.tryOffline(id);
        coordinator.close();
        assertTrue(coordinator.isOwned(id));
        assertNull(coordinator.tryLogin(UUID.randomUUID()));
        assertNull(coordinator.tryOffline(UUID.randomUUID()));
        lease.close();
        assertFalse(coordinator.isOwned(id));
    }
}
