package com.blocklogs.api.action;

/**
 * The concrete action recorded for a single log entry.
 *
 * <p>Each type belongs to an {@link ActionCategory} and declares whether it is "reversible" — i.e.
 * whether the rollback engine knows how to undo it. Non-reversible types are still logged and shown
 * in lookups; they are simply skipped by rollback/restore.
 *
 * <p>The {@code id} is a stable numeric code persisted in the database. NEVER renumber an existing
 * entry — only append new ones. This keeps historical data readable across plugin upgrades.
 */
public enum ActionType {
    // --- BLOCK ---
    BLOCK_PLACE(1, ActionCategory.BLOCK, true),
    BLOCK_BREAK(2, ActionCategory.BLOCK, true),
    /** Block state changed in place (repeater delay, orientation, waterlogging, open/closed, ...). */
    BLOCK_CHANGE(3, ActionCategory.BLOCK, true),
    SIGN_CHANGE(4, ActionCategory.BLOCK, true),
    /** Block removed by an explosion (attributed to whoever caused the explosion). */
    BLOCK_EXPLODE(5, ActionCategory.BLOCK, true),
    /** Block created/removed by liquid flow, moved by a piston, fire spread, etc. */
    BLOCK_FLOW(6, ActionCategory.BLOCK, true),

    // --- CONTAINER ---
    CONTAINER_INSERT(20, ActionCategory.CONTAINER, true),
    CONTAINER_REMOVE(21, ActionCategory.CONTAINER, true),

    // --- INTERACTION ---
    /** Toggling a lever/button/door/trapdoor/gate, pressing a plate, using a repeater/comparator. */
    INTERACT(40, ActionCategory.INTERACTION, false),

    // --- REDSTONE ---
    REDSTONE_CHANGE(60, ActionCategory.REDSTONE, false),

    // --- ENTITY ---
    ENTITY_KILL(80, ActionCategory.ENTITY, false),
    ENTITY_SPAWN(81, ActionCategory.ENTITY, false),
    /** Shear, dye, breed, tame, leash/unleash, saddle, name-tag, etc. */
    ENTITY_MODIFY(82, ActionCategory.ENTITY, false),

    // --- ITEM ---
    ITEM_DROP(100, ActionCategory.ITEM, false),
    ITEM_PICKUP(101, ActionCategory.ITEM, false);

    private final int id;
    private final ActionCategory category;
    private final boolean reversible;

    ActionType(int id, ActionCategory category, boolean reversible) {
        this.id = id;
        this.category = category;
        this.reversible = reversible;
    }

    public int id() {
        return id;
    }

    public ActionCategory category() {
        return category;
    }

    public boolean reversible() {
        return reversible;
    }

    /** Resolve a persisted numeric code back to its {@link ActionType}. */
    public static ActionType byId(int id) {
        for (ActionType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown ActionType id: " + id);
    }
}
