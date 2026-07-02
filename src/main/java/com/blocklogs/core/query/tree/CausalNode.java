package com.blocklogs.core.query.tree;

import com.blocklogs.api.log.LogEntry;

import java.util.List;

/**
 * A node in the causal tree rendered git-graph style in chat.
 *
 * <p>Children are loaded lazily: a node reports its {@link #childCount()} even when {@link #children()}
 * is empty, so the renderer can show a collapsed "{@code ×N [expand]}" summary and fetch children only
 * when the user drills in via {@link com.blocklogs.core.query.QueryEngine#expand(long)}.
 *
 * @param entry      the log entry at this node
 * @param childCount total number of direct children (may exceed {@code children.size()})
 * @param children   the children currently loaded (empty if collapsed/not yet fetched)
 * @param truncated  true if {@code children} is a partial slice of all children
 */
public record CausalNode(LogEntry entry, int childCount, List<CausalNode> children, boolean truncated) {

    public boolean hasChildren() {
        return childCount > 0;
    }

    public boolean isLeaf() {
        return childCount == 0;
    }
}
