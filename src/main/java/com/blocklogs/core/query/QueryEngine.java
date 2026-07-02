package com.blocklogs.core.query;

import com.blocklogs.core.query.tree.CausalNode;
import com.blocklogs.core.storage.StorageException;

import java.util.List;

/**
 * Turns {@link QueryParams} into results, in both the flat and the causal-tree presentations.
 *
 * <p>All methods hit the {@link com.blocklogs.core.storage.Database} and must be invoked off the main
 * thread; callers marshal the formatted output back to the main thread before sending chat.
 */
public interface QueryEngine {

    /** Flat, paged, chronological lookup — the {@code /bl flat} view. */
    LookupResult lookup(QueryParams params) throws StorageException;

    /**
     * The roots of the causal forest matching {@code params}, each with its {@code childCount} filled
     * in but children collapsed. This is the top level of the {@code /bl lookup} tree view.
     */
    List<CausalNode> tree(QueryParams params) throws StorageException;

    /** Load the direct children of a node when the user expands/drills into it. */
    CausalNode expand(long nodeId) throws StorageException;
}
