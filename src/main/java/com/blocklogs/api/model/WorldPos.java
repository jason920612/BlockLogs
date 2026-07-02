package com.blocklogs.api.model;

/**
 * A block position in a named world. Immutable value type used throughout logging and querying.
 *
 * <p>{@code world} is the Bukkit world name. Storage layers may intern it to a numeric id internally,
 * but the API always speaks in world names to stay decoupled from load order.
 */
public record WorldPos(String world, int x, int y, int z) {

    /** Chunk X (block x >> 4), useful for chunk-scoped rollback batching. */
    public int chunkX() {
        return x >> 4;
    }

    /** Chunk Z (block z >> 4). */
    public int chunkZ() {
        return z >> 4;
    }
}
