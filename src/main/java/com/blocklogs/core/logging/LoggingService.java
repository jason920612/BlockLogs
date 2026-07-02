package com.blocklogs.core.logging;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;

/**
 * The single funnel every listener uses to record actions. Implemented by {@code QueuedLoggingService},
 * which assigns ids, buffers entries and drains them to the {@link com.blocklogs.core.storage.Database}
 * on a background thread.
 *
 * <p>All methods here are safe to call from the main server thread and are cheap (no I/O).
 *
 * <p>Typical listener usage:
 * <pre>{@code
 * long cause = logging.record(actor, ActionType.BLOCK_PLACE, pos)
 *         .block(mat, null, dataAfter)
 *         .commit();
 * }</pre>
 */
public interface LoggingService {

    /** Begin building an entry. Terminate with {@link LogBuilder#commit()}. */
    LogBuilder record(Actor actor, ActionType action, WorldPos pos);

    /** Block until all queued entries have been written. Used on shutdown and before rollback reads. */
    void flush();

    /** The most recently assigned id (for diagnostics). */
    long lastAssignedId();
}
