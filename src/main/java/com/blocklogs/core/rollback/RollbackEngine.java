package com.blocklogs.core.rollback;

import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.storage.StorageException;

/**
 * Applies and reverses logged changes against the live world.
 *
 * <p>A <b>rollback</b> walks matching entries newest→oldest and inverts each reversible action
 * (place→remove, break→restore-with-exact-{@code BlockData}, container insert→remove the items, etc.),
 * marking them rolled back. A <b>restore</b> replays them oldest→newest and clears the flag. Only
 * {@link com.blocklogs.api.action.ActionType#reversible()} actions are touched.
 *
 * <p>World edits are re-marshalled to the main thread and applied in chunk-batches to avoid stalling
 * the server. Reads run async. Callers should {@code LoggingService.flush()} before rolling back so the
 * queue is consistent, and the engine suppresses logging of its own edits.
 */
public interface RollbackEngine {

    /** Undo matching changes. */
    RollbackResult rollback(QueryParams params) throws StorageException;

    /** Redo previously rolled-back changes. */
    RollbackResult restore(QueryParams params) throws StorageException;

    /** Count what would be affected without touching the world. */
    RollbackResult preview(QueryParams params) throws StorageException;
}
