package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.logging.LogBuilder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Indirect block changes caused by entities and fire:
 * <ul>
 *   <li>{@link EntityChangeBlockEvent} — enderman pickup/place, falling-block landing, sheep eating
 *       grass, farmland trampling, silverfish infest, etc. Logged as {@link ActionType#BLOCK_BREAK} when
 *       the block becomes air, otherwise {@link ActionType#BLOCK_FLOW} (a transformation).</li>
 *   <li>{@link BlockBurnEvent} — fire consuming a block ({@code BLOCK_BREAK}).</li>
 *   <li>{@link BlockIgniteEvent} — fire spreading to a new block ({@code BLOCK_FLOW}).</li>
 * </ul>
 *
 * <p>Actors are the responsible player where the event exposes one (falling block that a player pushed,
 * a player-ignited fire), otherwise the acting entity type, otherwise the environmental {@code #fire}
 * cause. All handlers capture {@code dataBefore} from the live block before the change and are fully
 * gated. These events do not overlap the direct block listener.
 */
public final class IndirectEntityBlockListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;

    public IndirectEntityBlockListener(BlockLogsServices services) {
        this.services = services;
        this.support = new LogSupport(services);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        String material = LogSupport.material(block);
        String dataBefore = LogSupport.data(block);
        WorldPos pos = LogSupport.pos(block);

        Entity entity = event.getEntity();
        Actor actor = entity instanceof Player player ? LogSupport.actor(player) : LogSupport.actor(entity);

        // Air result = the block was removed (enderman pickup, farmland->dirt via trample is still a
        // change). Treat AIR as a break, anything else as an in-place transformation.
        boolean becomesAir = event.getTo() == Material.AIR;
        ActionType type = becomesAir ? ActionType.BLOCK_BREAK : ActionType.BLOCK_FLOW;
        String dataAfter = becomesAir ? null : event.getBlockData().getAsString();

        LogBuilder builder = services.logging().record(actor, type, pos)
                .block(material, dataBefore, dataAfter);
        Long cause = support.causal().resolveBlock(pos);
        if (cause != null) {
            builder = builder.cause(cause);
        }
        builder.commit();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        String material = LogSupport.material(block);
        String dataBefore = LogSupport.data(block);
        WorldPos pos = LogSupport.pos(block);

        // Fire consuming a block: attribute to the environmental fire cause, chain if the fire's spread
        // was seeded from a player-lit source.
        LogBuilder builder = services.logging().record(Actor.natural("#fire"), ActionType.BLOCK_BREAK, pos)
                .block(material, dataBefore, null);
        Long cause = support.causal().resolveBlock(pos);
        if (cause != null) {
            builder = builder.cause(cause);
        }
        builder.commit();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        String material = LogSupport.material(block);
        String dataBefore = LogSupport.data(block);
        WorldPos pos = LogSupport.pos(block);

        // Prefer the igniting player/entity when the event exposes one (flint & steel, fireball, etc.);
        // otherwise it is ambient/spread fire.
        Player igniter = event.getPlayer();
        Actor actor;
        if (igniter != null) {
            actor = LogSupport.actor(igniter);
        } else if (event.getIgnitingEntity() != null) {
            actor = LogSupport.actor(event.getIgnitingEntity());
        } else {
            actor = Actor.natural("#fire");
        }

        LogBuilder builder = services.logging().record(actor, ActionType.BLOCK_FLOW, pos)
                .block(material, dataBefore, Material.FIRE.getKey().asString());
        Long cause = support.causal().resolveBlock(pos);
        if (cause != null) {
            builder = builder.cause(cause);
        }
        builder.commit();
    }
}
