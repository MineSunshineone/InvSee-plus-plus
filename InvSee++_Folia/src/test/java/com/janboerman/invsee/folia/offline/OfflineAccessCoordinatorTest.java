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
    @Test void loginPrioritySignalsOldLeaseAndTransfersWithoutAdmissionGap() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var old = coordinator.tryOffline(id);
        var signal = old.loginRequested();
        var login = coordinator.requestLogin(id);
        assertTrue(signal.isDone());
        assertTrue(old.isLoginRequested());
        assertFalse(login.ready().isDone());
        assertNull(coordinator.tryOffline(id));
        old.close();
        var admitted = login.ready().join();
        assertTrue(admitted.isLogin());
        assertNull(coordinator.tryOffline(id));
        old.close();
        assertTrue(coordinator.isOwned(id));
        admitted.close();
        assertFalse(coordinator.isOwned(id));
    }
    @Test void timedOutPendingLoginCannotLeakLateOwner() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var old = coordinator.tryOffline(id);
        var login = coordinator.requestLogin(id);
        login.cancel();
        assertTrue(login.ready().isCompletedExceptionally());
        assertTrue(coordinator.isOwned(id));
        old.close();
        assertFalse(coordinator.isOwned(id));
        assertNotNull(coordinator.tryOffline(id));
    }
    @Test void cancelledAlreadyGrantedLoginReleasesItsOwner() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var login = coordinator.requestLogin(id);
        assertTrue(login.ready().isDone());
        login.cancel();
        assertFalse(coordinator.isOwned(id));
    }
    @Test void duplicateLoginCannotStealExistingPendingAdmission() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var old = coordinator.tryOffline(id);
        var first = coordinator.requestLogin(id);
        var second = coordinator.requestLogin(id);
        assertTrue(second.ready().isCompletedExceptionally());
        second.cancel();
        old.close();
        assertTrue(first.ready().join().isLogin());
        first.cancel();
        assertFalse(coordinator.isOwned(id));
    }
    @Test void shutdownCancelsWaitingLoginButKeepsOldWriterUntilDrained() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var old = coordinator.tryOffline(id);
        var login = coordinator.requestLogin(id);
        coordinator.close();
        assertTrue(login.ready().isCompletedExceptionally());
        assertTrue(coordinator.isOwned(id));
        old.close();
        assertFalse(coordinator.isOwned(id));
        assertNull(coordinator.tryOffline(id));
    }
    @Test void callbacksNeverRunUnderCoordinatorMonitor() {
        var coordinator = new OfflineAccessCoordinator();
        UUID id = UUID.randomUUID();
        var old = coordinator.tryOffline(id);
        var signal = old.loginRequested().thenRun(() -> assertFalse(Thread.holdsLock(coordinator)));
        var login = coordinator.requestLogin(id);
        signal.join();
        var observed = login.ready().thenRun(() -> assertFalse(Thread.holdsLock(coordinator)));
        old.close(); observed.join(); login.cancel();
    }
    @Test void cancellationRacingAtomicHandoffNeverLeaksLoginToken() throws Exception {
        var coordinator = new OfflineAccessCoordinator();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 200; i++) {
                UUID id = UUID.randomUUID();
                var old = coordinator.tryOffline(id);
                var login = coordinator.requestLogin(id);
                var start = new CountDownLatch(1);
                var release = executor.submit(() -> { start.await(); old.close(); return null; });
                var cancel = executor.submit(() -> { start.await(); login.cancel(); return null; });
                start.countDown(); release.get(); cancel.get();
                assertFalse(coordinator.isOwned(id));
            }
        }
    }
}
