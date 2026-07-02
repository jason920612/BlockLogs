package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.model.WorldPos;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Attributes item transactions on <em>block-backed</em> containers to the responsible player (or, for
 * automated transfers, to a {@code #hopper} block actor).
 *
 * <p>Direct player interactions ({@link InventoryClickEvent}, {@link InventoryDragEvent}) are handled by
 * snapshotting the container inventory contents <em>before</em> the vanilla logic runs and re-reading it
 * one tick later. The per-item-type delta between the two snapshots is the net effect of the interaction,
 * which makes this robust against every action variant (normal place/take, shift-click / move-to-other,
 * drag distribution, hotbar/number-key swap, collect-to-cursor, double-click gather) without having to
 * reverse-engineer each {@code InventoryAction} by hand — and, crucially, never double-counting. A
 * positive delta for an item type is logged as {@link ActionType#CONTAINER_INSERT}; a negative delta as
 * {@link ActionType#CONTAINER_REMOVE}. The delayed read requires the {@link Plugin} for scheduling.
 *
 * <p>Automated transfers ({@link InventoryMoveItemEvent}: hoppers, droppers, hopper-minecarts feeding
 * blocks) are logged directly from the event, attributed to a {@code #hopper} block actor, as a REMOVE
 * from a block source and/or an INSERT to a block destination.
 */
public final class ContainerListener implements Listener {

    private final BlockLogsServices services;
    private final Plugin plugin;
    private final LogSupport support;

    public ContainerListener(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.plugin = plugin;
        this.support = new LogSupport(services);
    }

    // --- player-driven interactions -------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // A click can move items into either the clicked (top) inventory or the shift-target. The top
        // inventory of the view is the only one that can be a block container here; the bottom is always
        // the player's own inventory. Snapshot the top inventory if it is a block container.
        scheduleDiff(event.getView().getTopInventory(), player);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        scheduleDiff(event.getView().getTopInventory(), player);
    }

    /**
     * If {@code inv} is a block-backed container we care about, snapshot it now and schedule a one-tick
     * later re-read that diffs the two and logs the net per-item changes.
     */
    private void scheduleDiff(Inventory inv, Player player) {
        if (!services.config().logContainers()) {
            return;
        }
        Location loc = containerBlockLocation(inv);
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        if (!support.worldEnabled(loc.getWorld().getName())) {
            return;
        }

        Map<String, ItemStack> before = snapshot(inv);
        Actor actor = LogSupport.actor(player);
        WorldPos pos = LogSupport.pos(loc);
        String containerType = loc.getBlock().getType().getKey().asString();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Map<String, ItemStack> after = snapshot(inv);
            logDelta(actor, pos, containerType, before, after);
        });
    }

    // --- automated transfers (hoppers/droppers) -------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(InventoryMoveItemEvent event) {
        if (!services.config().logContainers()) {
            return;
        }
        Actor actor = Actor.block("#hopper");
        ItemStack moved = event.getItem();
        if (moved == null || moved.getAmount() <= 0) {
            return;
        }

        Location source = containerBlockLocation(event.getSource());
        if (source != null && source.getWorld() != null
                && support.worldEnabled(source.getWorld().getName())) {
            record(actor, ActionType.CONTAINER_REMOVE, source, moved);
        }

        Location dest = containerBlockLocation(event.getDestination());
        if (dest != null && dest.getWorld() != null
                && support.worldEnabled(dest.getWorld().getName())) {
            record(actor, ActionType.CONTAINER_INSERT, dest, moved);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Resolves the world location of the block backing {@code inv}, or {@code null} if this is not a
     * block-backed container (e.g. the player's own inventory, a crafting grid, an entity inventory such
     * as a horse or a hopper-minecart, or a virtual inventory with no location).
     */
    private static Location containerBlockLocation(Inventory inv) {
        if (inv == null) {
            return null;
        }
        InventoryHolder holder = inv.getHolder();
        if (holder instanceof Player) {
            return null;
        }
        // A BlockState holder (Chest, Barrel, ShulkerBox, Furnace, Hopper block, Dispenser, ...) is
        // exactly a block-backed container. Double chests report a DoubleChest holder, which is not a
        // BlockState — fall through to getLocation() for those.
        if (holder instanceof BlockState state) {
            return state.getLocation();
        }
        Location loc = inv.getLocation();
        if (loc == null) {
            return null;
        }
        // getLocation() may point at an entity (minecart/boat/horse); only accept it when the block at
        // that position is itself a container-type block-state holder.
        Block block = loc.getBlock();
        if (block.getState() instanceof org.bukkit.block.Container) {
            return loc;
        }
        return null;
    }

    /** Sum item amounts by a stable per-type key (type + meta), returning one representative stack each. */
    private static Map<String, ItemStack> snapshot(Inventory inv) {
        Map<String, ItemStack> totals = new HashMap<>();
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            String key = itemKey(item);
            ItemStack existing = totals.get(key);
            if (existing == null) {
                ItemStack copy = item.clone();
                totals.put(key, copy);
            } else {
                existing.setAmount(existing.getAmount() + item.getAmount());
            }
        }
        return totals;
    }

    /**
     * Diffs before/after per-item totals and logs one entry per changed item type. A net gain in the
     * container is an INSERT; a net loss is a REMOVE. {@code amount} is always the positive magnitude.
     */
    private void logDelta(Actor actor, WorldPos pos, String containerType,
                          Map<String, ItemStack> before, Map<String, ItemStack> after) {
        // Union of keys present in either snapshot.
        for (Map.Entry<String, ItemStack> entry : after.entrySet()) {
            int had = amountOf(before, entry.getKey());
            int now = entry.getValue().getAmount();
            int delta = now - had;
            if (delta != 0) {
                emit(actor, pos, containerType, entry.getValue(), delta);
            }
        }
        for (Map.Entry<String, ItemStack> entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) {
                continue; // already handled above
            }
            int delta = -entry.getValue().getAmount(); // fully removed
            emit(actor, pos, containerType, entry.getValue(), delta);
        }
    }

    private void emit(Actor actor, WorldPos pos, String containerType, ItemStack representative, int delta) {
        ActionType type = delta > 0 ? ActionType.CONTAINER_INSERT : ActionType.CONTAINER_REMOVE;
        int amount = Math.abs(delta);
        // The representative carries the per-type meta; use it (with amount 1) as the serialized shape.
        ItemStack single = representative.clone();
        single.setAmount(1);
        services.logging()
                .record(actor, type, pos)
                .container(containerType, single.getType().getKey().asString(), amount, serialize(single))
                .commit();
    }

    /** Log a whole {@link ItemStack} for an automated transfer (amount taken from the stack). */
    private void record(Actor actor, ActionType type, Location loc, ItemStack item) {
        WorldPos pos = LogSupport.pos(loc);
        String containerType = loc.getBlock().getType().getKey().asString();
        ItemStack single = item.clone();
        single.setAmount(1);
        services.logging()
                .record(actor, type, pos)
                .container(containerType, item.getType().getKey().asString(), item.getAmount(), serialize(single))
                .commit();
    }

    private static int amountOf(Map<String, ItemStack> map, String key) {
        ItemStack s = map.get(key);
        return s == null ? 0 : s.getAmount();
    }

    /** Stable per-type identity: material key plus a hash of the item meta so distinct NBT is separated. */
    private static String itemKey(ItemStack item) {
        ItemStack one = item.clone();
        one.setAmount(1);
        return one.getType().getKey().asString() + "#" + one.hashCode();
    }

    /**
     * Compact, self-describing serialization of a single item: Base64 of Paper's native
     * {@link ItemStack#serializeAsBytes()}. Returns {@code null} if serialization fails, in which case
     * the entry still records type + amount.
     */
    private static String serialize(ItemStack item) {
        try {
            return Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (Throwable t) {
            return null;
        }
    }
}
