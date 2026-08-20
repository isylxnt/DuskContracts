package dev.isylxnt.duskcontracts.inventory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final int limit; private final Map<UUID, ArrayDeque<Long>> actions = new ConcurrentHashMap<>();
    public RateLimiter(int limit) { if(limit<1)throw new IllegalArgumentException("limit must be positive");this.limit = limit; }
    public boolean allow(UUID player) {
        long now = System.nanoTime(); long cutoff = now - 1_000_000_000L;
        ArrayDeque<Long> queue = actions.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.removeFirst();
            if (queue.size() >= limit) return false; queue.addLast(now); return true;
        }
    }
    public void remove(UUID player) { actions.remove(player); }
    public void clear() { actions.clear(); }
}
