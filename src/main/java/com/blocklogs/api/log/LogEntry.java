package com.blocklogs.api.log;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;

import java.time.Instant;

/**
 * A single stored log record as returned by queries.
 *
 * <p>Sealed hierarchy — a record is exactly one of {@link BlockLogEntry}, {@link ContainerLogEntry}
 * or {@link EntityLogEntry}. Use pattern matching to branch:
 *
 * <pre>{@code
 * switch (entry) {
 *     case BlockLogEntry b     -> ...;
 *     case ContainerLogEntry c -> ...;
 *     case EntityLogEntry e    -> ...;
 * }
 * }</pre>
 *
 * <h2>IDs and causality</h2>
 * {@link #id()} is a globally monotonic id assigned by the logging service at record time (see
 * {@code LoggingService}). {@link #causeId()} points at the id of the entry that <em>caused</em> this
 * one, or {@code null} for a root cause. Following {@code causeId} links upward reconstructs the causal
 * tree that the query UI renders git-graph style.
 */
public sealed interface LogEntry permits BlockLogEntry, ContainerLogEntry, EntityLogEntry {

    /** Globally monotonic, plugin-assigned id. */
    long id();

    /** When the action happened. */
    Instant time();

    /** Who/what caused it. */
    Actor actor();

    ActionType action();

    WorldPos pos();

    /** Id of the entry that caused this one, or {@code null} if this is a root cause. */
    Long causeId();

    /** Whether this entry has been reverted by a rollback. */
    boolean rolledBack();
}
