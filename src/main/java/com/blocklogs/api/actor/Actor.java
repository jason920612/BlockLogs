package com.blocklogs.api.actor;

import java.util.Objects;
import java.util.UUID;

/**
 * Who (or what) is responsible for a logged action.
 *
 * <p>For players, {@link #uuid()} is non-null. For every other {@link ActorType}, {@code uuid} is
 * {@code null} and {@link #name()} holds a stable identifier such as {@code "#tnt"}, {@code "#piston"},
 * {@code "#water"}, {@code "minecraft:enderman"} or a plugin name. The leading {@code #} convention is
 * used for environmental causes so they never collide with a real player name.
 *
 * <p>Immutable. Safe to share across threads and to use as a map key.
 */
public record Actor(ActorType type, UUID uuid, String name) {

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (type == ActorType.PLAYER && uuid == null) {
            throw new IllegalArgumentException("PLAYER actors require a uuid");
        }
    }

    public static Actor player(UUID uuid, String name) {
        return new Actor(ActorType.PLAYER, uuid, name);
    }

    public static Actor block(String name) {
        return new Actor(ActorType.BLOCK, null, name);
    }

    public static Actor entity(String name) {
        return new Actor(ActorType.ENTITY, null, name);
    }

    public static Actor natural(String name) {
        return new Actor(ActorType.NATURAL, null, name);
    }

    public static Actor plugin(String name) {
        return new Actor(ActorType.PLUGIN, null, name);
    }

    public boolean isPlayer() {
        return type == ActorType.PLAYER;
    }

    /** Human-readable name for chat output. */
    public String displayName() {
        return name;
    }
}
