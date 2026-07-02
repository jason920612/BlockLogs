package com.blocklogs.core.logging;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.log.BlockLogEntry;
import com.blocklogs.api.log.ContainerLogEntry;
import com.blocklogs.api.log.EntityLogEntry;
import com.blocklogs.api.log.LogEntry;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link LoggingService}: assigns monotonic ids on the calling (main) thread and drains queued
 * entries to the {@link Database} on a single background writer thread in batches.
 *
 * <p>This is core plumbing owned by the skeleton — feature subagents should not need to touch it.
 */
public final class QueuedLoggingService implements LoggingService {

    private final Database database;
    private final Logger logger;
    private final AtomicLong idSequence;
    private final int batchSize;
    private final long flushIntervalMs;

    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = false;
    private Thread writerThread;

    /**
     * @param seedMaxId the largest id already in storage; the next assigned id is {@code seedMaxId + 1}.
     */
    public QueuedLoggingService(Database database, Logger logger, long seedMaxId,
                                int batchSize, long flushIntervalMs) {
        this.database = database;
        this.logger = logger;
        this.idSequence = new AtomicLong(seedMaxId);
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        writerThread = new Thread(this::drainLoop, "BlockLogs-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /** Stop the writer and flush anything still queued. */
    public synchronized void stop() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        flush();
    }

    @Override
    public LogBuilder record(Actor actor, ActionType action, WorldPos pos) {
        return new BuilderImpl(Objects.requireNonNull(actor), Objects.requireNonNull(action),
                Objects.requireNonNull(pos));
    }

    @Override
    public void flush() {
        List<LogEntry> drained = new ArrayList<>();
        queue.drainTo(drained);
        if (!drained.isEmpty()) {
            writeBatch(drained);
        }
    }

    @Override
    public long lastAssignedId() {
        return idSequence.get();
    }

    private void drainLoop() {
        List<LogEntry> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                LogEntry first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                writeBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void writeBatch(List<LogEntry> batch) {
        try {
            database.insert(batch);
        } catch (StorageException e) {
            logger.log(Level.SEVERE, "Failed to persist " + batch.size() + " log entries", e);
        }
    }

    /** Builds one entry and enqueues it on {@link #commit()}, returning the assigned id synchronously. */
    private final class BuilderImpl implements LogBuilder {
        private final Actor actor;
        private final ActionType action;
        private final WorldPos pos;
        private Long causeId;
        private Instant time = Instant.now();

        // payload (exactly one group is set)
        private Payload payload = Payload.NONE;
        private String s1, s2, s3, s4;
        private int i1;
        private UUID u1;

        private BuilderImpl(Actor actor, ActionType action, WorldPos pos) {
            this.actor = actor;
            this.action = action;
            this.pos = pos;
        }

        @Override public LogBuilder cause(long causeId) { this.causeId = causeId; return this; }
        @Override public LogBuilder at(Instant t) { this.time = t; return this; }

        @Override public LogBuilder block(String material, String dataBefore, String dataAfter) {
            this.payload = Payload.BLOCK;
            this.s1 = material; this.s2 = dataBefore; this.s3 = dataAfter;
            return this;
        }

        @Override public LogBuilder container(String type, String item, int amount, String serialized) {
            this.payload = Payload.CONTAINER;
            this.s1 = type; this.s2 = item; this.i1 = amount; this.s3 = serialized;
            return this;
        }

        @Override public LogBuilder entity(String entityType, UUID entityUuid, String data) {
            this.payload = Payload.ENTITY;
            this.s1 = entityType; this.u1 = entityUuid; this.s4 = data;
            return this;
        }

        @Override public long commit() {
            long id = idSequence.incrementAndGet();
            LogEntry entry = switch (payload) {
                case BLOCK -> new BlockLogEntry(id, time, actor, action, pos, s1, s2, s3, causeId, false);
                case CONTAINER -> new ContainerLogEntry(id, time, actor, action, pos, s1, s2, i1, s3, causeId, false);
                case ENTITY -> new EntityLogEntry(id, time, actor, action, pos, s1, u1, s4, causeId, false);
                case NONE -> throw new IllegalStateException("commit() called without a payload");
            };
            queue.add(entry);
            return id;
        }
    }

    private enum Payload { NONE, BLOCK, CONTAINER, ENTITY }
}
