package com.blocklogs.api.log;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;

import java.time.Instant;
import java.util.UUID;

/**
 * An entity/animal-affecting log entry: kill, spawn, shear, breed, tame, leash, dye, etc.
 *
 * @param entityType namespaced entity type key (e.g. {@code minecraft:cow}).
 * @param entityUuid the affected entity's UUID, or {@code null} if unknown/not applicable.
 * @param data       free-form detail string (e.g. new color for dye, partner uuid for breeding),
 *                   or {@code null}.
 */
public record EntityLogEntry(
        long id,
        Instant time,
        Actor actor,
        ActionType action,
        WorldPos pos,
        String entityType,
        UUID entityUuid,
        String data,
        Long causeId,
        boolean rolledBack
) implements LogEntry {
}
