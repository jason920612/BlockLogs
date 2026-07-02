package com.blocklogs.core.rollback;

import com.blocklogs.api.action.ActionCategory;
import com.blocklogs.api.log.BlockLogEntry;
import com.blocklogs.api.log.ContainerLogEntry;
import com.blocklogs.api.log.EntityLogEntry;
import com.blocklogs.api.log.LogEntry;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.logging.LoggingService;
import com.blocklogs.core.query.LookupResult;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

/**
 * Default {@link RollbackEngine}.
 *
 * <p>Reads matching entries via {@link Database#query(QueryParams)} (paged to gather ALL matches),
 * then inverts each {@link com.blocklogs.api.action.ActionType#reversible() reversible} action against
 * the live world. Non-reversible matches are counted only in the query pass but never touched.
 *
 * <p><b>Direction.</b> A rollback processes newest&rarr;oldest and inverts each action
 * (place&rarr;air, break&rarr;restore, insert&rarr;remove-items, ...). A restore replays them
 * oldest&rarr;newest (the exact inverse) and clears the rolled-back flag.
 *
 * <p><b>Threading.</b> {@code database.query} runs off the main thread (this engine is called from the
 * async command layer). All world mutations — {@code setBlockData}, inventory edits, the implicit chunk
 * loads they trigger — MUST run on the main thread, so they are marshalled there via the scheduler and
 * applied in batches of {@link #BLOCKS_PER_TICK} per tick. The calling (async) thread blocks on a
 * {@link CountDownLatch} until all scheduled batches finish, then returns the aggregated result.
 *
 * <p><b>No re-logging.</b> Mutating blocks / inventories through the Bukkit API here does NOT fire
 * Bukkit events, so the engine's own edits are never observed by the listeners and therefore never
 * re-logged. No explicit suppression is needed.
 */
public final class DefaultRollbackEngine implements RollbackEngine {

    /**
     * World edits applied per server tick.
     * TODO: read {@code config.rollbackBlocksPerTick()} — the constructor is a fixed contract
     * ({@code (Plugin, Database, LoggingService)}) and cannot take the config, so a constant is used.
     */
    private static final int BLOCKS_PER_TICK = 1000;

    /** Page size for gathering all matches from {@link Database#query}. */
    private static final int QUERY_PAGE_SIZE = 10_000;

    private final Plugin plugin;
    private final Database database;
    private final LoggingService logging;

    public DefaultRollbackEngine(Plugin plugin, Database database, LoggingService logging) {
        this.plugin = plugin;
        this.database = database;
        this.logging = logging;
    }

    @Override
    public RollbackResult rollback(QueryParams params) throws StorageException {
        return run(params, false);
    }

    @Override
    public RollbackResult restore(QueryParams params) throws StorageException {
        return run(params, true);
    }

    @Override
    public RollbackResult preview(QueryParams params) throws StorageException {
        long start = System.nanoTime();
        List<LogEntry> matches = gatherAll(params);
        Map<ActionCategory, Integer> byCategory = new EnumMap<>(ActionCategory.class);
        for (LogEntry entry : matches) {
            if (entry.action().reversible()) {
                byCategory.merge(entry.action().category(), 1, Integer::sum);
            }
        }
        // Dry run: nothing touched in the world, no ids flipped.
        return new RollbackResult(false, true, byCategory, 0, 0,
                Duration.ofNanos(System.nanoTime() - start));
    }

    // --- core -----------------------------------------------------------------------------------

    private RollbackResult run(QueryParams params, boolean restore) throws StorageException {
        long start = System.nanoTime();

        List<LogEntry> matches = gatherAll(params);

        // Only reversible entries are acted upon; others were already counted out above.
        List<LogEntry> reversible = new ArrayList<>();
        for (LogEntry entry : matches) {
            if (entry.action().reversible()) {
                reversible.add(entry);
            }
        }

        // Rollback (undo) walks newest -> oldest; restore (redo) walks oldest -> newest.
        Comparator<LogEntry> byTime = Comparator
                .comparing(LogEntry::time)
                .thenComparingLong(LogEntry::id);
        reversible.sort(restore ? byTime : byTime.reversed());

        Aggregator agg = new Aggregator();
        applyOnMainThread(reversible, restore, agg);

        if (!agg.appliedIds.isEmpty()) {
            // rollback => set flag true; restore => clear it.
            database.markRolledBack(agg.appliedIds, !restore);
        }

        return new RollbackResult(
                restore,
                false,
                agg.byCategory,
                agg.blocksChanged,
                agg.touchedChunks.size(),
                Duration.ofNanos(System.nanoTime() - start));
    }

    /** Page through the database to collect every matching entry regardless of the caller's limit. */
    private List<LogEntry> gatherAll(QueryParams params) throws StorageException {
        List<LogEntry> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            QueryParams page = QueryParams.builder()
                    .actorUuids(params.actorUuids())
                    .actorNames(params.actorNames())
                    .actions(params.actions())
                    .categories(params.categories())
                    .world(params.world())
                    .center(params.center())
                    .radius(params.radius())
                    .since(params.since())
                    .until(params.until())
                    .materials(params.materials())
                    .excludeRolledBack(params.excludeRolledBack())
                    .limit(QUERY_PAGE_SIZE)
                    .offset(offset)
                    .build();

            LookupResult result = database.query(page);
            List<LogEntry> entries = result.entries();
            if (entries == null || entries.isEmpty()) {
                break;
            }
            all.addAll(entries);
            offset += entries.size();

            // Stop once we've seen every matching row (or the page came back short).
            if (all.size() >= result.totalCount() || entries.size() < QUERY_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /**
     * Marshal the world edits onto the main thread in {@link #BLOCKS_PER_TICK} batches and block the
     * calling async thread until they all complete.
     */
    private void applyOnMainThread(List<LogEntry> ordered, boolean restore, Aggregator agg) {
        if (ordered.isEmpty()) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        // Self-rescheduling runnable: each tick applies up to BLOCKS_PER_TICK entries, then yields.
        Runnable batch = new Runnable() {
            private int index = 0;

            @Override
            public void run() {
                try {
                    int end = Math.min(index + BLOCKS_PER_TICK, ordered.size());
                    for (; index < end; index++) {
                        applyOne(ordered.get(index), restore, agg);
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Rollback batch failed", ex);
                }
                if (index >= ordered.size()) {
                    done.countDown();
                } else {
                    plugin.getServer().getScheduler().runTask(plugin, this);
                }
            }
        };
        plugin.getServer().getScheduler().runTask(plugin, batch);

        try {
            done.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Apply a single entry (already resolved to reversible). Runs on the main thread. */
    private void applyOne(LogEntry entry, boolean restore, Aggregator agg) {
        boolean changed = switch (entry) {
            case BlockLogEntry b     -> applyBlock(b, restore);
            case ContainerLogEntry c -> applyContainer(c, restore);
            case EntityLogEntry _    -> false; // no reversible entity actions today
        };
        if (changed) {
            agg.record(entry);
        }
    }

    // --- block edits -----------------------------------------------------------------------------

    private boolean applyBlock(BlockLogEntry entry, boolean restore) {
        World world = plugin.getServer().getWorld(entry.pos().world());
        if (world == null) {
            return false;
        }
        WorldPos pos = entry.pos();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());

        // For a rollback we want the "before" state; for a restore we want the "after" state.
        // Undo semantics per action are described inline.
        return switch (entry.action()) {
            case BLOCK_PLACE -> {
                if (restore) {
                    // Redo the placement: reinstate the placed block (dataAfter), else material.
                    yield setState(block, entry.dataAfter(), entry.material());
                }
                // Undo the placement: clear to what was there before, else AIR.
                if (entry.dataBefore() != null) {
                    yield setState(block, entry.dataBefore(), null);
                }
                yield setToAir(block);
            }
            case BLOCK_BREAK, BLOCK_EXPLODE, BLOCK_FLOW -> {
                if (restore) {
                    // Redo the removal: put back whatever the action left (dataAfter), else AIR.
                    if (entry.dataAfter() != null) {
                        yield setState(block, entry.dataAfter(), null);
                    }
                    yield setToAir(block);
                }
                // Undo the removal: restore the pre-action block data, else skip.
                if (entry.dataBefore() != null) {
                    yield setState(block, entry.dataBefore(), null);
                }
                yield false;
            }
            case BLOCK_CHANGE -> {
                // In-place state change: undo -> dataBefore, redo -> dataAfter.
                String target = restore ? entry.dataAfter() : entry.dataBefore();
                if (target != null) {
                    yield setState(block, target, null);
                }
                yield false;
            }
            case SIGN_CHANGE -> {
                // SIGN_CHANGE stores the sign text in dataAfter (see BlockLogEntry javadoc); dataBefore
                // would hold the prior text. Restoring raw sign text requires re-parsing the serialized
                // lines onto a Sign state.
                // TODO: parse the serialized sign text (dataBefore for undo / dataAfter for redo) back
                // onto the Sign BlockState. Skipped for now — sign text format is owned by the sign
                // listener part and not yet finalized.
                yield false;
            }
            default -> false;
        };
    }

    /** Apply a BlockData string to a block. Returns true if applied. */
    private boolean setState(Block block, String blockData, String fallbackMaterial) {
        if (blockData != null) {
            try {
                BlockData data = Bukkit.createBlockData(blockData);
                // Bukkit API edit -> fires no events -> not re-logged. applyPhysics=false to avoid
                // cascading updates while we batch-restore neighbouring blocks.
                block.setBlockData(data, false);
                return true;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Rollback: unparseable BlockData '" + blockData + "'", ex);
                return false;
            }
        }
        if (fallbackMaterial != null) {
            Material mat = matchMaterial(fallbackMaterial);
            if (mat != null && mat.isBlock()) {
                block.setType(mat, false);
                return true;
            }
        }
        return false;
    }

    private boolean setToAir(Block block) {
        if (block.getType() == Material.AIR) {
            return false;
        }
        block.setType(Material.AIR, false);
        return true;
    }

    // --- container edits -------------------------------------------------------------------------

    private boolean applyContainer(ContainerLogEntry entry, boolean restore) {
        World world = plugin.getServer().getWorld(entry.pos().world());
        if (world == null) {
            return false;
        }
        WorldPos pos = entry.pos();
        BlockState state = world.getBlockAt(pos.x(), pos.y(), pos.z()).getState();
        if (!(state instanceof Container container)) {
            return false;
        }
        Inventory inv = container.getInventory();

        ItemStack stack = rebuildItem(entry);
        if (stack == null) {
            return false;
        }

        // INSERT put items IN -> undo removes them / redo re-adds them.
        // REMOVE took items OUT -> undo adds them back / redo removes them.
        boolean addToContainer = switch (entry.action()) {
            case CONTAINER_INSERT -> restore;   // undo=remove, redo=add
            case CONTAINER_REMOVE -> !restore;  // undo=add,    redo=remove
            default -> false;
        };

        if (addToContainer) {
            inv.addItem(stack);
        } else {
            inv.removeItem(stack);
        }
        return true;
    }

    /** Rebuild the moved item: prefer the exact serialized stack, else Material + amount. */
    private ItemStack rebuildItem(ContainerLogEntry entry) {
        if (entry.serializedItem() != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(entry.serializedItem());
                return ItemStack.deserializeBytes(bytes);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Rollback: unreadable serialized item for entry " + entry.id(), ex);
                // fall through to material+amount
            }
        }
        Material mat = matchMaterial(entry.item());
        if (mat == null) {
            return null;
        }
        int amount = Math.max(1, entry.amount());
        return new ItemStack(mat, amount);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Resolve a (possibly namespaced) material key to a {@link Material}, or null. */
    private Material matchMaterial(String key) {
        if (key == null) {
            return null;
        }
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase());
        if (nk != null) {
            Material byKey = Material.matchMaterial(nk.getKey());
            if (byKey != null) {
                return byKey;
            }
        }
        return Material.matchMaterial(key);
    }

    /** Mutable tally of what a run touched. Mutated only on the main thread. */
    private static final class Aggregator {
        final Map<ActionCategory, Integer> byCategory = new EnumMap<>(ActionCategory.class);
        final List<Long> appliedIds = new ArrayList<>();
        final java.util.Set<Long> touchedChunks = new java.util.HashSet<>();
        int blocksChanged = 0;

        void record(LogEntry entry) {
            byCategory.merge(entry.action().category(), 1, Integer::sum);
            appliedIds.add(entry.id());
            blocksChanged++;
            WorldPos pos = entry.pos();
            // Pack chunk (x,z) into a single long key for distinct-count.
            long chunkKey = (((long) pos.chunkX()) << 32) ^ (pos.chunkZ() & 0xffffffffL);
            touchedChunks.add(chunkKey);
        }
    }
}
