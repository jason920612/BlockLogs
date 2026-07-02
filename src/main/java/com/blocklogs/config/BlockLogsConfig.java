package com.blocklogs.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Typed view over {@code config.yml}. Reloadable — build a fresh instance on {@code /bl reload}.
 */
public final class BlockLogsConfig {

    private final List<String> enabledWorlds;
    private final boolean logBlocks;
    private final boolean logContainers;
    private final boolean logInteractions;
    private final boolean logRedstone;
    private final boolean logEntities;
    private final boolean logItems;
    private final int retentionDays;
    private final int writerBatchSize;
    private final long writerFlushIntervalMs;
    private final long causalTtlMillis;
    private final int rollbackBlocksPerTick;

    public BlockLogsConfig(FileConfiguration c) {
        this.enabledWorlds = c.getStringList("worlds.enabled");
        this.logBlocks = c.getBoolean("logging.blocks", true);
        this.logContainers = c.getBoolean("logging.containers", true);
        this.logInteractions = c.getBoolean("logging.interactions", true);
        this.logRedstone = c.getBoolean("logging.redstone", false);
        this.logEntities = c.getBoolean("logging.entities", true);
        this.logItems = c.getBoolean("logging.items", true);
        this.retentionDays = c.getInt("storage.retention-days", 60);
        this.writerBatchSize = c.getInt("storage.writer.batch-size", 500);
        this.writerFlushIntervalMs = c.getLong("storage.writer.flush-interval-ms", 1000L);
        this.causalTtlMillis = c.getLong("causal.ttl-ms", 5000L);
        this.rollbackBlocksPerTick = c.getInt("rollback.blocks-per-tick", 1000);
    }

    /** Empty list means "all worlds". */
    public List<String> enabledWorlds() { return enabledWorlds; }
    public boolean worldEnabled(String world) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(world);
    }

    public boolean logBlocks() { return logBlocks; }
    public boolean logContainers() { return logContainers; }
    public boolean logInteractions() { return logInteractions; }
    public boolean logRedstone() { return logRedstone; }
    public boolean logEntities() { return logEntities; }
    public boolean logItems() { return logItems; }

    public int retentionDays() { return retentionDays; }
    public int writerBatchSize() { return writerBatchSize; }
    public long writerFlushIntervalMs() { return writerFlushIntervalMs; }
    public long causalTtlMillis() { return causalTtlMillis; }
    public int rollbackBlocksPerTick() { return rollbackBlocksPerTick; }
}
