package com.blocklogs.api.action;

/**
 * Broad grouping of {@link ActionType}s, used for filtering in lookups
 * (e.g. {@code /bl lookup ... include:container}) and for choosing which log table to hit.
 */
public enum ActionCategory {
    /** Block placed, broken, or its state changed. */
    BLOCK,
    /** Items inserted into / removed from a container. */
    CONTAINER,
    /** Player interactions that toggle or reconfigure blocks (levers, buttons, repeaters, signs). */
    INTERACTION,
    /** Redstone power / signal changes. */
    REDSTONE,
    /** Entity / animal actions (kill, spawn, shear, breed, tame, leash, dye). */
    ENTITY,
    /** Item drops and pickups. */
    ITEM
}
