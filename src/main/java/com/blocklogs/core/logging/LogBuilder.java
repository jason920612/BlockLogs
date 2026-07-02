package com.blocklogs.core.logging;

import java.time.Instant;
import java.util.UUID;

/**
 * Fluent builder for a single log entry, obtained from
 * {@link LoggingService#record(com.blocklogs.api.actor.Actor, com.blocklogs.api.action.ActionType,
 * com.blocklogs.api.model.WorldPos)}.
 *
 * <p>Set exactly one payload ({@link #block}, {@link #container} or {@link #entity}) then call
 * {@link #commit()}. {@code commit()} returns the entry's monotonic id <em>synchronously</em>, so a
 * listener can immediately use it as the {@link #cause(long)} of follow-up (indirect) entries — this
 * is how causal trees are built even though the actual disk write happens later on a background thread.
 */
public interface LogBuilder {

    /** Link this entry to the entry that caused it. Omit for a root cause. */
    LogBuilder cause(long causeId);

    /** Override the timestamp (defaults to now). */
    LogBuilder at(Instant time);

    /**
     * Block payload.
     *
     * @param material   namespaced key, e.g. {@code minecraft:repeater}
     * @param dataBefore Paper {@code BlockData} string before, or {@code null}
     * @param dataAfter  Paper {@code BlockData} string after, or {@code null}
     */
    LogBuilder block(String material, String dataBefore, String dataAfter);

    /** Container item transaction payload. {@code amount} is always positive. */
    LogBuilder container(String containerType, String item, int amount, String serializedItem);

    /** Entity/animal payload. */
    LogBuilder entity(String entityType, UUID entityUuid, String data);

    /**
     * Enqueue the entry for asynchronous persistence.
     *
     * @return the monotonic id assigned to this entry; usable as {@code cause()} for child entries.
     */
    long commit();
}
