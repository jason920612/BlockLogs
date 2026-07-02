package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Indirect block changes caused by explosions.
 *
 * <p>Every explosion destroys many blocks at once, so this listener follows the causal model: it first
 * records a single <em>root</em> {@link ActionType#BLOCK_EXPLODE} entry describing the explosion itself
 * (at the explosion location, with {@code material = null}) and captures the returned id, then logs one
 * {@code BLOCK_EXPLODE} child per destroyed block linked back with {@link
 * com.blocklogs.core.logging.LogBuilder#cause(long)}. This produces a collapsed causal tree
 * (player → TNT → explosion → destroyed blocks) instead of a flat wall of rows.
 *
 * <p>The root actor is resolved to the responsible player when possible:
 * <ul>
 *   <li>primed TNT whose source is a player (via the causal tracker or {@link TNTPrimed#getSource()}),</li>
 *   <li>otherwise the exploding entity itself ({@code minecraft:creeper}, {@code minecraft:tnt}, …),</li>
 *   <li>for block-initiated explosions (bed/respawn anchor in the wrong dimension) an environmental
 *       {@code #explosion} actor.</li>
 * </ul>
 *
 * <p>These events fire <em>before</em> the world mutates, so {@code dataBefore} is captured from the live
 * block. They do not overlap with the direct break listener (that handles {@code BlockBreakEvent} only).
 */
public final class IndirectExplosionListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;

    public IndirectExplosionListener(BlockLogsServices services) {
        this.services = services;
        this.support = new LogSupport(services);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Entity entity = event.getEntity();
        Location origin = entity != null ? entity.getLocation() : event.getLocation();
        String world = origin.getWorld().getName();
        if (!support.worldEnabled(world)) {
            return;
        }

        Actor rootActor = resolveEntityExplosionActor(entity);
        WorldPos originPos = LogSupport.pos(origin);
        // Root: the explosion itself. material=null so it renders as a cause node, not a destroyed block.
        long rootId = services.logging().record(rootActor, ActionType.BLOCK_EXPLODE, originPos)
                .block(null, null, null)
                .commit();

        logDestroyed(event.blockList(), world, rootId);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block source = event.getBlock();
        String world = source.getWorld().getName();
        if (!support.worldEnabled(world)) {
            return;
        }

        WorldPos originPos = LogSupport.pos(source);
        // Block-initiated explosion (bed/respawn anchor). Try to resolve who set it off; else environmental.
        Long resolved = support.causal().resolveBlock(originPos);
        var rootBuilder = services.logging().record(Actor.block("#explosion"), ActionType.BLOCK_EXPLODE, originPos)
                .block(null, null, null);
        if (resolved != null) {
            rootBuilder = rootBuilder.cause(resolved);
        }
        long rootId = rootBuilder.commit();

        logDestroyed(event.blockList(), world, rootId);
    }

    /** Log each destroyed block as a BLOCK_EXPLODE child of {@code rootId}, capturing state before removal. */
    private void logDestroyed(List<Block> blocks, String world, long rootId) {
        for (Block block : blocks) {
            if (!support.worldEnabled(block.getWorld().getName())) {
                continue;
            }
            // Capture material + state now — the event fires before the blocks are removed.
            String material = LogSupport.material(block);
            String dataBefore = LogSupport.data(block);
            WorldPos pos = LogSupport.pos(block);
            services.logging().record(Actor.block("#explosion"), ActionType.BLOCK_EXPLODE, pos)
                    .block(material, dataBefore, null)
                    .cause(rootId)
                    .commit();
        }
    }

    /** Resolve the responsible actor for an entity explosion, preferring the triggering player. */
    private Actor resolveEntityExplosionActor(Entity entity) {
        if (entity == null) {
            return Actor.block("#tnt");
        }
        // Prefer a player attributed when the TNT was primed (survives across the fuse via CausalTracker).
        Long cause = support.causal().resolveEntity(entity.getUniqueId());
        if (entity instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player player) {
                return LogSupport.actor(player);
            }
            // Attributed but source no longer a live player -> fall back to the tnt marker.
            if (cause == null) {
                return Actor.block("#tnt");
            }
        }
        // Non-TNT entities (creeper, ender crystal, wither, fireball, …) attribute to the entity type.
        return LogSupport.actor(entity);
    }
}
