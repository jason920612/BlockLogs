package com.blocklogs.core.causal;

import com.blocklogs.api.model.WorldPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link CausalTracker}: in-memory attribution maps with a fixed TTL. Fully functional plumbing
 * provided by the skeleton — the indirect-physics subagent populates and reads it from listeners.
 */
public final class SimpleCausalTracker implements CausalTracker {

    private record Attribution(long causeId, long expiresAt) {}

    private final Map<UUID, Attribution> entities = new ConcurrentHashMap<>();
    private final Map<WorldPos, Attribution> blocks = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SimpleCausalTracker(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    @Override
    public void attributeEntity(UUID entity, long causeId) {
        entities.put(entity, new Attribution(causeId, now() + ttlMillis));
    }

    @Override
    public Long resolveEntity(UUID entity) {
        return resolve(entities.get(entity));
    }

    @Override
    public void attributeBlock(WorldPos pos, long causeId) {
        blocks.put(pos, new Attribution(causeId, now() + ttlMillis));
    }

    @Override
    public Long resolveBlock(WorldPos pos) {
        return resolve(blocks.get(pos));
    }

    @Override
    public void expireStale() {
        long t = now();
        entities.values().removeIf(a -> a.expiresAt() <= t);
        blocks.values().removeIf(a -> a.expiresAt() <= t);
    }

    private Long resolve(Attribution a) {
        if (a == null || a.expiresAt() <= now()) {
            return null;
        }
        return a.causeId();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
