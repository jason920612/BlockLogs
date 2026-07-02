package com.blocklogs.api.log;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;

import java.time.Instant;

/**
 * An item transaction against a container (chest, barrel, hopper, furnace, brewing stand, ...).
 *
 * @param containerType namespaced material of the container block (e.g. {@code minecraft:chest}).
 * @param item          namespaced item key moved (e.g. {@code minecraft:diamond}).
 * @param amount        quantity moved; always positive. The direction is encoded by
 *                      {@link ActionType#CONTAINER_INSERT} vs {@link ActionType#CONTAINER_REMOVE}.
 * @param serializedItem full item stack (NBT/components) serialized to a string for exact restore,
 *                       or {@code null} when only type+amount were captured.
 */
public record ContainerLogEntry(
        long id,
        Instant time,
        Actor actor,
        ActionType action,
        WorldPos pos,
        String containerType,
        String item,
        int amount,
        String serializedItem,
        Long causeId,
        boolean rolledBack
) implements LogEntry {
}
