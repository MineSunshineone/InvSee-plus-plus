package com.janboerman.invsee.folia.offline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Admission fence, not an entity-ownership lock. Never waits for disk or a region. */
public final class OfflineAccessCoordinator {
    private final Map<UUID, Token> owners = new HashMap<>();
    private boolean closed;

    public synchronized Token tryOffline(UUID id) { return acquire(id, false); }
    public synchronized Token tryLogin(UUID id) { return acquire(id, true); }
    private Token acquire(UUID id, boolean login) {
        if (closed || owners.containsKey(id)) return null;
        Token token = new Token(id, login);
        owners.put(id, token);
        return token;
    }
    /** Fail closed for new operations; existing tokens remain owned until actually drained. */
    public synchronized void close() { closed = true; }
    public synchronized boolean isOwned(UUID id) { return owners.containsKey(id); }

    public final class Token implements AutoCloseable {
        private final UUID id;
        private final boolean login;
        private boolean released;
        private Token(UUID id, boolean login) { this.id = id; this.login = login; }
        public UUID playerId() { return id; }
        public boolean isLogin() { return login; }
        @Override public void close() {
            synchronized (OfflineAccessCoordinator.this) {
                if (!released) {
                    released = true;
                    owners.remove(id, this);
                }
            }
        }
    }
}
