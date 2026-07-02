package com.blocklogs.core.query.session;

import com.blocklogs.core.query.QueryParams;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A saved, navigable exploration of the log — the "git-like" query state.
 *
 * <p>Holds the originating {@link QueryParams} plus the user's navigation state: a drill-in stack
 * (whose top is the current {@code HEAD} node) and the set of expanded node ids. Sessions are
 * persisted (survive restarts) and shareable — another admin can open the same session id to see the
 * exact same view.
 *
 * <p>Mutable and single-owner-at-a-time; navigation happens on the main thread in response to clicks
 * or {@code /bl} subcommands.
 */
public final class QuerySession {

    private int id;                 // 0 until first persisted
    private final UUID owner;
    private final Instant createdAt;
    private QueryParams params;

    /** Node ids the user has drilled into; {@code peek()} is the current HEAD ({@code null} = roots). */
    private final Deque<Long> navStack = new ArrayDeque<>();
    /** Node ids currently expanded inline. */
    private final Set<Long> expanded = new HashSet<>();
    private int page = 0;

    public QuerySession(UUID owner, Instant createdAt, QueryParams params) {
        this.owner = owner;
        this.createdAt = createdAt;
        this.params = params;
    }

    /** Full constructor used when rehydrating from storage. */
    public QuerySession(int id, UUID owner, Instant createdAt, QueryParams params,
                        List<Long> navStack, Set<Long> expanded, int page) {
        this.id = id;
        this.owner = owner;
        this.createdAt = createdAt;
        this.params = params;
        navStack.forEach(this.navStack::push);
        this.expanded.addAll(expanded);
        this.page = page;
    }

    public int id() { return id; }
    public void id(int id) { this.id = id; }
    public UUID owner() { return owner; }
    public Instant createdAt() { return createdAt; }
    public QueryParams params() { return params; }
    public void params(QueryParams params) { this.params = params; }

    /** Current HEAD node id, or {@code null} when viewing the roots. */
    public Long head() { return navStack.peek(); }
    public void drillInto(long nodeId) { navStack.push(nodeId); }
    public Long back() { return navStack.poll(); }

    public boolean isExpanded(long nodeId) { return expanded.contains(nodeId); }
    public void expand(long nodeId) { expanded.add(nodeId); }
    public void collapse(long nodeId) { expanded.remove(nodeId); }

    public int page() { return page; }
    public void page(int page) { this.page = page; }

    /** Snapshot of the drill stack, root-first, for persistence. */
    public List<Long> navStackSnapshot() {
        List<Long> out = new ArrayList<>(navStack);
        java.util.Collections.reverse(out);
        return out;
    }

    public Set<Long> expandedSnapshot() {
        return new HashSet<>(expanded);
    }
}
