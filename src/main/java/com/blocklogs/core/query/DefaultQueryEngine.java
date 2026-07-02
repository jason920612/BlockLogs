package com.blocklogs.core.query;

import com.blocklogs.api.log.LogEntry;
import com.blocklogs.core.query.tree.CausalNode;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link QueryEngine}.
 *
 * <p>Turns {@link Database} reads into the two presentations the UI needs:
 * <ul>
 *   <li>{@link #lookup(QueryParams)} — a flat, paged, chronological page (delegates straight to the DB).</li>
 *   <li>{@link #tree(QueryParams)} — the roots of the causal forest, each with its {@code childCount}
 *       filled in but children left collapsed until the user drills in.</li>
 *   <li>{@link #expand(long)} — a single node with its direct children loaded (each of those children
 *       again collapsed, so drilling stays lazy one level at a time).</li>
 * </ul>
 *
 * <p>Sibling aggregation for rendering ("×N …") happens in the render layer, not here.
 */
public final class DefaultQueryEngine implements QueryEngine {

    private final Database database;

    public DefaultQueryEngine(Database database) {
        this.database = database;
    }

    @Override
    public LookupResult lookup(QueryParams params) throws StorageException {
        return database.query(params);
    }

    @Override
    public List<CausalNode> tree(QueryParams params) throws StorageException {
        List<LogEntry> roots = database.roots(params);
        List<CausalNode> out = new ArrayList<>(roots.size());
        for (LogEntry root : roots) {
            out.add(new CausalNode(root, database.childCount(root.id()), List.of(), false));
        }
        return out;
    }

    @Override
    public CausalNode expand(long nodeId) throws StorageException {
        LogEntry entry = database.byId(nodeId);
        if (entry == null) {
            return null;
        }
        List<LogEntry> childEntries = database.children(nodeId);
        List<CausalNode> children = new ArrayList<>(childEntries.size());
        for (LogEntry child : childEntries) {
            children.add(new CausalNode(child, database.childCount(child.id()), List.of(), false));
        }
        return new CausalNode(entry, database.childCount(nodeId), children, false);
    }
}
