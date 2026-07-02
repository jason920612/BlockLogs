package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.List;
import java.util.StringJoiner;

/**
 * Records direct, player-caused block changes: placing a block, breaking a block, multi-block placement
 * (e.g. beds, doors, tall flowers) and editing sign text. Every handler enqueues cheaply through
 * {@link com.blocklogs.core.logging.LoggingService} — no I/O happens on the main thread.
 *
 * <p>Indirect changes (pistons, explosions, liquid flow, redstone, containers, entities) are owned by
 * other listener parts; this one only covers what a player does hands-on.
 */
public final class BlockChangeListener implements Listener {

    private final BlockLogsServices services;
    private final LogSupport support;

    public BlockChangeListener(BlockLogsServices services) {
        this.services = services;
        this.support = new LogSupport(services);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        Actor actor = LogSupport.actor(event.getPlayer());
        WorldPos pos = LogSupport.pos(block);
        services.logging().record(actor, ActionType.BLOCK_PLACE, pos)
                .block(LogSupport.material(block), null, LogSupport.data(block))
                .commit();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        // Capture material + state now, while the block is still present.
        String material = LogSupport.material(block);
        String dataBefore = LogSupport.data(block);
        Actor actor = LogSupport.actor(event.getPlayer());
        WorldPos pos = LogSupport.pos(block);
        services.logging().record(actor, ActionType.BLOCK_BREAK, pos)
                .block(material, dataBefore, null)
                .commit();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Actor actor = LogSupport.actor(event.getPlayer());
        List<BlockState> replaced = event.getReplacedBlockStates();
        for (BlockState state : replaced) {
            Block block = state.getBlock();
            if (!support.worldEnabled(block.getWorld().getName())) {
                continue;
            }
            WorldPos pos = LogSupport.pos(block);
            // state carries the new (placed) block; the live block still holds the old data.
            services.logging().record(actor, ActionType.BLOCK_PLACE, pos)
                    .block(LogSupport.material(state.getBlock()), null, LogSupport.data(state.getBlockData()))
                    .commit();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!services.config().logBlocks()) {
            return;
        }
        Block block = event.getBlock();
        if (!support.worldEnabled(block.getWorld().getName())) {
            return;
        }
        Actor actor = LogSupport.actor(event.getPlayer());
        WorldPos pos = LogSupport.pos(block);
        // Capture FRONT-side text before/after so rollback can restore it. A back-side edit leaves the
        // front unchanged, so before == after and rollback/restore become safe no-ops on the front.
        String before = frontText(block);
        String after = event.getSide() == org.bukkit.block.sign.Side.FRONT
                ? joinLines(event.lines())
                : before;
        services.logging().record(actor, ActionType.SIGN_CHANGE, pos)
                .block(LogSupport.material(block), before, after)
                .commit();
    }

    /** Current FRONT-side plain text of a sign block, newline-joined, or {@code null} if not a sign. */
    private static String frontText(Block block) {
        if (block.getState() instanceof org.bukkit.block.Sign sign) {
            PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
            StringJoiner joiner = new StringJoiner("\n");
            for (Component line : sign.getSide(org.bukkit.block.sign.Side.FRONT).lines()) {
                joiner.add(line == null ? "" : serializer.serialize(line));
            }
            return joiner.toString();
        }
        return null;
    }

    /** Join sign lines into a single newline-separated plain-text string. */
    private static String joinLines(List<Component> lines) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        StringJoiner joiner = new StringJoiner("\n");
        for (Component line : lines) {
            joiner.add(line == null ? "" : serializer.serialize(line));
        }
        return joiner.toString();
    }
}
