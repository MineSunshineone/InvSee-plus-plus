package com.janboerman.invsee.folia.offline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Pure admission state. A login reserves priority without unlocking the UUID between owners. */
public final class OfflineAccessCoordinator {
    private final Map<UUID, Token> owners = new HashMap<>();
    private final Map<UUID, LoginRequest> waiting = new HashMap<>();
    private boolean closed;

    public synchronized Token tryOffline(UUID id) { return acquire(id, false); }
    public synchronized Token tryLogin(UUID id) { return acquire(id, true); }
    private Token acquire(UUID id, boolean login) {
        if (closed || owners.containsKey(id) || waiting.containsKey(id)) return null;
        Token token = new Token(id, login);
        owners.put(id, token);
        return token;
    }

    public LoginRequest requestLogin(UUID id) {
        LoginRequest request = new LoginRequest(id);
        Token notify = null;
        synchronized (this) {
            Token owner = owners.get(id);
            if (closed || waiting.containsKey(id) || (owner != null && owner.login)) {
                request.failure = new IllegalStateException("Login already admitted or admission closed");
            } else if (owner == null) {
                request.granted = new Token(id, true);
                owners.put(id, request.granted);
            } else {
                waiting.put(id, request);
                owner.loginPending = true;
                notify = owner;
            }
        }
        // User callbacks can take session/lease locks. Never run them under this monitor.
        if (notify != null) notify.loginIntent.complete(null);
        request.publish();
        return request;
    }

    public void close() {
        LoginRequest[] requests;
        synchronized (this) { closed = true; requests = waiting.values().toArray(LoginRequest[]::new); }
        for (LoginRequest request : requests) request.cancel();
    }
    public synchronized boolean isOwned(UUID id) { return owners.containsKey(id); }

    public final class LoginRequest {
        private final UUID id;
        private final CompletableFuture<Token> ready = new CompletableFuture<>();
        private volatile Token granted;
        private volatile Throwable failure;
        private boolean cancelled;
        private LoginRequest(UUID id) { this.id = id; }
        public CompletableFuture<Token> ready() { return ready.copy(); }
        private void publish() {
            if (failure != null) ready.completeExceptionally(failure);
            else if (granted != null && !ready.complete(granted) && ready.isCompletedExceptionally()) granted.close();
        }
        /** A denied/timed-out prelogin never gets a connection-close callback. Retire late grants too. */
        public void cancel() {
            Token release;
            synchronized (OfflineAccessCoordinator.this) {
                cancelled = true;
                waiting.remove(id, this);
                release = granted;
            }
            ready.completeExceptionally(new CancellationException("Login handoff abandoned"));
            if (release != null) release.close();
        }
    }

    public final class Token implements AutoCloseable {
        private final UUID id;
        private final boolean login;
        private final CompletableFuture<Void> loginIntent = new CompletableFuture<>();
        private volatile boolean loginPending;
        private boolean released;
        private Token(UUID id, boolean login) { this.id = id; this.login = login; }
        public UUID playerId() { return id; }
        public boolean isLogin() { return login; }
        public boolean isLoginRequested() { return loginPending; }
        public CompletableFuture<Void> loginRequested() { return loginIntent.copy(); }
        @Override public void close() {
            LoginRequest handoff = null;
            synchronized (OfflineAccessCoordinator.this) {
                if (released) return;
                released = true;
                owners.remove(id, this);
                LoginRequest pending = waiting.remove(id);
                if (pending != null && !pending.cancelled && !closed) {
                    pending.granted = new Token(id, true);
                    owners.put(id, pending.granted);
                    handoff = pending;
                }
            }
            if (handoff != null) handoff.publish();
        }
    }
}
