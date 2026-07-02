package com.blocklogs.core.rollback;

import java.time.Duration;
import java.util.Map;

import com.blocklogs.api.action.ActionCategory;

/**
 * Summary of a rollback / restore / preview run.
 *
 * @param restore        false for a rollback (undo), true for a restore (redo)
 * @param preview        true if this was a dry run — nothing was actually applied
 * @param affectedByCategory count of entries acted on, grouped by category
 * @param blocksChanged  total block modifications applied to the world
 * @param chunksTouched  number of distinct chunks modified
 * @param duration       wall-clock time of the operation
 */
public record RollbackResult(
        boolean restore,
        boolean preview,
        Map<ActionCategory, Integer> affectedByCategory,
        int blocksChanged,
        int chunksTouched,
        Duration duration
) {
    public int totalAffected() {
        return affectedByCategory.values().stream().mapToInt(Integer::intValue).sum();
    }
}
