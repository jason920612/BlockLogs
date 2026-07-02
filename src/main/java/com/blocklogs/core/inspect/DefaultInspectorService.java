package com.blocklogs.core.inspect;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link InspectorService}: tracks which players are in inspector mode. Fully functional
 * skeleton plumbing; the inspector subagent adds the click listener that reads this and renders history.
 */
public final class DefaultInspectorService implements InspectorService {

    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();

    @Override
    public boolean toggle(UUID player) {
        if (inspecting.remove(player)) {
            return false;
        }
        inspecting.add(player);
        return true;
    }

    @Override
    public boolean isInspecting(UUID player) {
        return inspecting.contains(player);
    }

    @Override
    public void clear(UUID player) {
        inspecting.remove(player);
    }
}
