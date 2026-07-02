package com.blocklogs.api.log;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;

import java.time.Instant;

/**
 * A block-affecting log entry: place, break, in-place state change, sign edit, explosion, flow.
 *
 * @param material  namespaced block material key (e.g. {@code minecraft:repeater}).
 * @param dataBefore Paper {@code BlockData} string before the action, or {@code null} if not applicable
 *                   (e.g. placing into air). Used to restore exact state on rollback.
 * @param dataAfter  Paper {@code BlockData} string after the action, or {@code null} (e.g. after a break).
 *                   For {@link ActionType#SIGN_CHANGE} this carries the serialized sign text.
 */
public record BlockLogEntry(
        long id,
        Instant time,
        Actor actor,
        ActionType action,
        WorldPos pos,
        String material,
        String dataBefore,
        String dataAfter,
        Long causeId,
        boolean rolledBack
) implements LogEntry {
}
