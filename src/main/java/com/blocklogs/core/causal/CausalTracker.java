package com.blocklogs.core.causal;

import com.blocklogs.api.model.WorldPos;

import java.util.UUID;

/**
 * Short-lived attribution memory that links <em>indirect</em> effects back to the player action that
 * set them in motion. This is the shared brain the indirect-physics listeners populate and read.
 *
 * <p>Examples of the chains it enables:
 * <ul>
 *   <li>Player primes TNT → {@link #attributeEntity} the primed-TNT entity to the player's cause id.
 *       On explosion, each destroyed block resolves its cause via {@link #resolveEntity}.</li>
 *   <li>Player toggles a lever → piston fires → {@link #attributeBlock} the moved blocks so the piston
 *       listener can attribute them.</li>
 *   <li>Player breaks ice → water flows → the flow listener resolves the source block's cause.</li>
 * </ul>
 *
 * <p>Entries expire after a short, configurable TTL so stale attributions don't leak. Implementations
 * must be thread-safe for main-thread writes/reads.
 */
public interface CausalTracker {

    /** Remember that {@code entity} was set in motion by the action with id {@code causeId}. */
    void attributeEntity(UUID entity, long causeId);

    /** Resolve the cause id for an entity, or {@code null} if unknown/expired. */
    Long resolveEntity(UUID entity);

    /** Remember that the block at {@code pos} owes its current state to {@code causeId}. */
    void attributeBlock(WorldPos pos, long causeId);

    /** Resolve the cause id for a block position, or {@code null} if unknown/expired. */
    Long resolveBlock(WorldPos pos);

    /** Drop expired attributions. Called periodically by the plugin scheduler. */
    void expireStale();
}
