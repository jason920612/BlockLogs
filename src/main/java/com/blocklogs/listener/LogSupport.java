package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.causal.CausalTracker;
import com.blocklogs.core.logging.LoggingService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Shared conversion + gate helpers for all listener parts. Using these keeps capture logic identical
 * across listeners (one way to turn a {@link Block} into a {@link WorldPos}, one way to read a
 * {@code BlockData} string, one way to build a player {@link Actor}), which is what lets the separately
 * built listener pieces assemble cleanly.
 *
 * <p>Construct one per listener (or share one) with the plugin's {@link BlockLogsServices}.
 */
public final class LogSupport {

    private final BlockLogsServices services;

    public LogSupport(BlockLogsServices services) {
        this.services = services;
    }

    public LoggingService logging() {
        return services.logging();
    }

    public CausalTracker causal() {
        return services.causalTracker();
    }

    /** Whether logging is enabled for this world at all. Listeners should early-return if false. */
    public boolean worldEnabled(String world) {
        return services.config().worldEnabled(world);
    }

    // --- pure conversions (also usable statically) ---

    public static WorldPos pos(Location loc) {
        return new WorldPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static WorldPos pos(Block block) {
        return new WorldPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Actor for a real player. */
    public static Actor actor(Player player) {
        return Actor.player(player.getUniqueId(), player.getName());
    }

    /** Actor for a non-player entity cause, e.g. {@code minecraft:creeper}. */
    public static Actor actor(Entity entity) {
        return Actor.entity(entity.getType().getKey().asString());
    }

    /** Namespaced material key of a block, e.g. {@code minecraft:repeater}. */
    public static String material(Block block) {
        return block.getType().getKey().asString();
    }

    /** Paper {@code BlockData} string of a block (captures full state). */
    public static String data(Block block) {
        return block.getBlockData().getAsString();
    }

    public static String data(BlockData blockData) {
        return blockData.getAsString();
    }
}
