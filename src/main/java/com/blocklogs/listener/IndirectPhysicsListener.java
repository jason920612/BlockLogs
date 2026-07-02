package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.logging.LogBuilder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.List;

/**
 * Indirect block changes driven by mechanisms and fluids: piston pushes/pulls, liquid (and dragon-egg)
 * flow, and dispenser fires. Each moved/created block is logged as {@link ActionType#BLOCK_FLOW}. Where
 * an upstream player action can be resolved from the {@link com.blocklogs.core.causal.CausalTracker}
 * (e.g. lever → piston, break-ice → water) the entry is linked with {@code .cause(id)} so it nests under
 * the original action.
 *
 * <p>All handlers capture {@code dataBefore} from the live block (these events fire before the world
 * mutates) and are fully gated on {@code logBlocks()} and {@code worldEnabled(...)}. {@link
 * BlockFromToEvent} can fire very frequently, so its handler does the cheapest possible work.
 *
 * <p>These events are disjoint from the direct break/place listener (which only handles player
 * {@code BlockBreakEvent}/{@code BlockPlaceEvent}), so there is no double-logging.
 */
public final class IndirectPhysicsListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;

    public IndirectPhysicsListener(BlockLogsServices services) {
        this.services = services;
        this.support = new LogSupport(services);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlock(), event.getBlocks());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlock(), event.getBlocks());
    }

    /** Log each block moved by a piston, attributing the piston and chaining to any resolved cause. */
    private void handlePiston(Block piston, List<Block> moved) {
        if (!services.config().logBlocks()) {
            return;
        }
        if (!support.worldEnabled(piston.getWorld().getName())) {
            return;
        }
        // Resolve who powered the piston once (e.g. a lever toggle seeded the piston's position).
        Long cause = support.causal().resolveBlock(LogSupport.pos(piston));
        for (Block block : moved) {
            if (!support.worldEnabled(block.getWorld().getName())) {
                continue;
            }
            String material = LogSupport.material(block);
            String dataBefore = LogSupport.data(block);
            WorldPos pos = LogSupport.pos(block);
            LogBuilder builder = services.logging().record(Actor.block("#piston"), ActionType.BLOCK_FLOW, pos)
                    .block(material, dataBefore, null);
            if (cause != null) {
                builder = builder.cause(cause);
            }
            builder.commit();
            // TODO: seed causal().attributeBlock(destinationPos, id) so a chain reaction of pistons/flow
            //       downstream can resolve back to this move. Requires cross-tick attribution and the
            //       destination position (piston direction), left out to keep this handler cheap.
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        // Kept deliberately cheap: this fires for every water/lava spread tick.
        if (!services.config().logBlocks()) {
            return;
        }
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!support.worldEnabled(to.getWorld().getName())) {
            return;
        }
        // Capture the destination's current state before the liquid overwrites it.
        String material = LogSupport.material(to);
        String dataBefore = LogSupport.data(to);
        WorldPos toPos = LogSupport.pos(to);

        Long cause = support.causal().resolveBlock(LogSupport.pos(from));
        LogBuilder builder = services.logging().record(sourceActor(from), ActionType.BLOCK_FLOW, toPos)
                .block(material, dataBefore, LogSupport.data(from));
        if (cause != null) {
            builder = builder.cause(cause);
        }
        builder.commit();
        // TODO: cross-listener seeding — when the direct break listener removes ice/a source block, it
        //       could attribute the freed position so the resulting flow resolves to the player. That
        //       belongs in the causal wiring rather than editing BlockChangeListener here.
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block dispenser = event.getBlock();
        if (!support.worldEnabled(dispenser.getWorld().getName())) {
            return;
        }
        // A dispenser placing/emitting a block (water/lava bucket, shulker, etc.) counts as indirect flow.
        WorldPos pos = LogSupport.pos(dispenser);
        Long cause = support.causal().resolveBlock(pos);
        LogBuilder builder = services.logging().record(Actor.block("#dispenser"), ActionType.BLOCK_FLOW, pos)
                .block(LogSupport.material(dispenser), null, event.getItem().getType().getKey().asString());
        if (cause != null) {
            builder = builder.cause(cause);
        }
        builder.commit();
    }

    /** Environmental actor for a flowing source block, distinguishing lava from water/other. */
    private static Actor sourceActor(Block from) {
        Material type = from.getType();
        if (type == Material.LAVA) {
            return Actor.natural("#lava");
        }
        return Actor.natural("#water");
    }
}
