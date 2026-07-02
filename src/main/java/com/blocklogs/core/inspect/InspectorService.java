package com.blocklogs.core.inspect;

import java.util.UUID;

/**
 * Manages per-player "inspector" mode. While active, a player's left/right clicks on a block are
 * intercepted (and cancelled) by the inspector listener, which then queries the history at that block
 * and shows it as a causal tree in chat.
 */
public interface InspectorService {

    /** Flip inspector mode for the player; returns the new state (true = now inspecting). */
    boolean toggle(UUID player);

    boolean isInspecting(UUID player);

    /** Clear state on quit. */
    void clear(UUID player);
}
