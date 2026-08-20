package dev.isylxnt.duskcontracts.application;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class StripedLocks {
    private final ReentrantLock[] locks;
    public StripedLocks(int stripes) { locks = new ReentrantLock[stripes]; for (int i = 0; i < stripes; i++) locks[i] = new ReentrantLock(); }
    public ReentrantLock forId(UUID id) { return locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length]; }
}
