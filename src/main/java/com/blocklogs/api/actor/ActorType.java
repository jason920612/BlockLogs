package com.blocklogs.api.actor;

/**
 * The kind of thing that caused a logged action.
 *
 * <p>Every {@link Actor} has exactly one type. Only {@link #PLAYER} actors carry a UUID.
 */
public enum ActorType {
    /** A real player. Carries a UUID + name. */
    PLAYER,
    /** A non-player entity cause (e.g. an enderman moving a block, a creeper explosion). */
    ENTITY,
    /** A block-driven cause (e.g. a piston, dispenser, TNT, spreading fire). */
    BLOCK,
    /** A natural/world cause with no player attribution (e.g. leaf decay, liquid physics with no source). */
    NATURAL,
    /** Another plugin, via the public API. */
    PLUGIN,
    /** Attribution could not be determined. */
    UNKNOWN
}
