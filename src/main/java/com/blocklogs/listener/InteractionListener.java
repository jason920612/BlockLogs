package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.DaylightDetector;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Records in-place block-state changes caused by player interaction, plus (optionally) raw redstone
 * power changes.
 *
 * <p>Interacting with a repeater, comparator, lever, button, door, trapdoor, fence gate, note block or
 * daylight detector mutates the block's {@code BlockData} without placing or breaking anything. The
 * mutation happens <em>after</em> {@link PlayerInteractEvent} fires, so this listener captures the
 * before-state at event time and schedules a 1-tick delayed read of the after-state; it logs a
 * {@link ActionType#BLOCK_CHANGE} only when the two differ.
 *
 * <p>Blocks that open a GUI/container (chests, furnaces, barrels, hoppers, ...) are intentionally not
 * handled here — they are owned by the container listener part. This listener only covers
 * state-toggling / redstone-configuration blocks.
 *
 * <p>{@link BlockRedstoneEvent} is high-volume and disabled by default ({@code logging.redstone});
 * its handler is kept minimal and fully gated.
 */
public final class InteractionListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;
    private final Plugin plugin;

    public InteractionListener(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.support = new LogSupport(services);
        this.plugin = plugin;
    }

    /**
     * Player toggled/configured a state block. Right-click for hand-operated blocks; the physical action
     * covers stepping on pressure plates. We capture the before-state now and re-read after one tick.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!services.config().logInteractions()) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        if (!isStateToggleBlock(block.getBlockData())) {
            return;
        }

        String material = LogSupport.material(block);
        String dataBefore = LogSupport.data(block);
        Actor actor = LogSupport.actor(event.getPlayer());
        WorldPos pos = LogSupport.pos(block);

        // The block's state mutates after the event returns; read it back next tick and only log a real
        // change. Deferred work is deliberately the single non-trivial cost in this listener.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String dataAfter = LogSupport.data(block);
            if (dataAfter.equals(dataBefore)) {
                return;
            }
            services.logging().record(actor, ActionType.BLOCK_CHANGE, pos)
                    .block(material, dataBefore, dataAfter)
                    .commit();
        });
    }

    /**
     * Raw redstone power change. Off by default because it is extremely high-volume; when enabled we keep
     * the payload cheap (the old/new current rendered as short strings rather than full block data).
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (!services.config().logRedstone()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        Actor actor = Actor.natural("#redstone");
        WorldPos pos = LogSupport.pos(block);
        services.logging().record(actor, ActionType.REDSTONE_CHANGE, pos)
                .block(LogSupport.material(block),
                        Integer.toString(event.getOldCurrent()),
                        Integer.toString(event.getNewCurrent()))
                .commit();
    }

    /**
     * Whether the block's data is one this listener owns: a block whose {@code BlockData} meaningfully
     * changes on interaction (delay, mode, powered/open) and which does not open a container GUI.
     *
     * <p>{@link Switch} covers both levers and buttons. TODO: extend for other future state-toggle types
     * (e.g. candles, bells) if they warrant interaction logging.
     */
    private static boolean isStateToggleBlock(BlockData data) {
        return data instanceof Repeater          // delay / locked
                || data instanceof Comparator     // subtract vs compare mode
                || data instanceof Switch          // levers and buttons
                || data instanceof Door            // open / closed
                || data instanceof TrapDoor        // open / closed
                || data instanceof Gate            // fence gate open / closed
                || data instanceof NoteBlock       // note / instrument
                || data instanceof DaylightDetector; // inverted
    }
}
