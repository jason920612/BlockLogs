package com.blocklogs.render;

import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.log.BlockLogEntry;
import com.blocklogs.api.log.ContainerLogEntry;
import com.blocklogs.api.log.EntityLogEntry;
import com.blocklogs.api.log.LogEntry;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.LookupResult;
import com.blocklogs.core.query.QueryEngine;
import com.blocklogs.core.query.session.QuerySession;
import com.blocklogs.core.query.tree.CausalNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders causal trees and flat lookups as Adventure {@link Component}s for chat.
 *
 * <p>The tree view is drawn git-graph style with box-drawing characters (● │ ├─ └─). Nodes that have
 * children but are collapsed carry a clickable {@code [展開]} that runs {@code /bl expand <id>}; every
 * node line has a {@code [鑽入]} to drill into it ({@code /bl goto <id>}). Consecutive sibling leaves
 * with the same {@code (action, material)} are aggregated into one {@code ×N …} line. Each line hovers
 * to reveal coordinates, block-data, the full timestamp and the actor.
 *
 * <p>Pure formatting — no I/O. Callers run the DB work off-thread and hand the rendered component back
 * to the main thread.
 */
public final class TreeRenderer {

    /** Short relative clock (HH:mm:ss) for the inline line; full timestamp lives in the hover. */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** How many roots / expanded children we render per screen before offering paging. */
    private static final int PAGE_SIZE = 10;

    // ── colors ───────────────────────────────────────────────────────────────
    private static final NamedTextColor C_TIME = NamedTextColor.DARK_GRAY;
    private static final NamedTextColor C_ACTOR = NamedTextColor.AQUA;
    private static final NamedTextColor C_VERB = NamedTextColor.YELLOW;
    private static final NamedTextColor C_MATERIAL = NamedTextColor.WHITE;
    private static final NamedTextColor C_COORDS = NamedTextColor.GRAY;
    private static final NamedTextColor C_GRAPH = NamedTextColor.DARK_GRAY;
    private static final NamedTextColor C_LINK = NamedTextColor.GREEN;
    private static final NamedTextColor C_LINK2 = NamedTextColor.LIGHT_PURPLE;

    // ─────────────────────────────────────────────────────────────────────────
    //  Tree view
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Render the current view of a session: either the forest roots ({@code head == null}) or the
     * expanded subtree of the current HEAD node.
     *
     * @param roots   the roots (or the single HEAD's children) already fetched off-thread
     * @param session the owning session (for HEAD / paging state and shareable id)
     * @param engine  unused directly here but kept in the signature so a future renderer can lazily
     *                fetch more; children are pre-fetched by the command layer today
     */
    public Component renderTree(List<CausalNode> roots, QuerySession session, QueryEngine engine) {
        Component out = header(session);

        Long head = session.head();
        if (head != null) {
            out = out.append(Component.newline())
                    .append(Component.text("● HEAD ", NamedTextColor.GOLD)
                            .append(Component.text("#" + head, NamedTextColor.GRAY)));
        }

        List<CausalNode> visible = paginate(roots, session.page());
        List<Row> rows = aggregate(visible);

        int i = 0;
        for (Row row : rows) {
            boolean last = (i == rows.size() - 1);
            out = out.append(Component.newline()).append(renderRow(row, last));
            i++;
        }

        if (roots.isEmpty()) {
            out = out.append(Component.newline())
                    .append(Component.text("  (沒有符合條件的紀錄)", NamedTextColor.DARK_GRAY));
        }

        out = out.append(Component.newline()).append(footer(session, roots.size()));
        return out;
    }

    /** Header line with the session id and a shareable hint. */
    private Component header(QuerySession session) {
        Component title = Component.text("BlockLogs ", NamedTextColor.AQUA)
                .append(Component.text("因果樹", NamedTextColor.GOLD));
        if (session.id() > 0) {
            title = title.append(Component.text("  [#" + session.id() + "]", NamedTextColor.DARK_GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "以 /bl session " + session.id() + " 分享此查詢", NamedTextColor.GRAY))));
        }
        return title;
    }

    /** Footer: paging + back navigation, all clickable. */
    private Component footer(QuerySession session, int visibleCount) {
        Component footer = Component.text("─".repeat(20), C_GRAPH);
        List<Component> controls = new ArrayList<>();

        if (session.page() > 0) {
            controls.add(link("[上一頁]", "/bl page " + (session.page() - 1),
                    "回到上一頁", C_LINK));
        }
        if (visibleCount > (session.page() + 1) * PAGE_SIZE) {
            controls.add(link("[下一頁]", "/bl page " + (session.page() + 1),
                    "查看下一頁", C_LINK));
        }
        if (session.head() != null) {
            controls.add(link("[返回]", "/bl back", "回到上一層", C_LINK2));
        }
        if (session.id() > 0) {
            controls.add(link("[重新整理]", "/bl session " + session.id(),
                    "重新載入此查詢", NamedTextColor.GRAY));
        }

        if (controls.isEmpty()) {
            return footer;
        }
        footer = footer.append(Component.newline());
        for (int i = 0; i < controls.size(); i++) {
            if (i > 0) {
                footer = footer.append(Component.text("  ", NamedTextColor.GRAY));
            }
            footer = footer.append(controls.get(i));
        }
        return footer;
    }

    /** One rendered tree row (single node line). */
    private Component renderRow(Row row, boolean last) {
        String branch = last ? "└─ " : "├─ ";
        Component line = Component.text(branch, C_GRAPH);

        if (row.count() > 1) {
            // aggregated sibling leaves of the same (action, material)
            line = line.append(Component.text("×" + row.count() + " ", NamedTextColor.GOLD))
                    .append(Component.text(verb(row.representative().entry().action()) + " ", C_VERB))
                    .append(Component.text(shortMaterial(materialOf(row.representative())), C_MATERIAL));
            // aggregate hover summarises the range
            line = line.hoverEvent(HoverEvent.showText(aggregateHover(row)));
            // aggregated rows still let you drill into the first; expand shows them individually
            if (row.anyHasChildren()) {
                line = line.append(Component.space())
                        .append(link("[展開]", "/bl expand " + row.representative().entry().id(),
                                "展開這些節點的子項", C_LINK));
            }
            return line;
        }

        // single node
        CausalNode node = row.node();
        LogEntry e = node.entry();
        Component marker = Component.text(node.hasChildren() ? "● " : "○ ", C_GRAPH);
        line = Component.text(branch, C_GRAPH).append(marker);

        Component body = Component.text(CLOCK.format(e.time()) + " ", C_TIME)
                .append(Component.text(e.actor().displayName() + " ", C_ACTOR))
                .append(Component.text(verb(e.action()) + " ", C_VERB))
                .append(Component.text(shortMaterial(describe(e)), C_MATERIAL))
                .append(Component.text("  @ " + coords(e.pos()), C_COORDS));
        if (e.rolledBack()) {
            body = body.decorate(net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH)
                    .append(Component.text(" (已還原)", NamedTextColor.RED));
        }
        body = body.hoverEvent(HoverEvent.showText(nodeHover(e)));
        line = line.append(body);

        // controls
        if (node.hasChildren()) {
            line = line.append(Component.text("  ×" + node.childCount(), NamedTextColor.GOLD))
                    .append(Component.space())
                    .append(link("[展開]", "/bl expand " + e.id(),
                            "展開 " + node.childCount() + " 個子項", C_LINK))
                    .append(Component.space())
                    .append(link("[鑽入]", "/bl goto " + e.id(),
                            "鑽入此節點", C_LINK2));
        }
        return line;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Flat view
    // ─────────────────────────────────────────────────────────────────────────

    /** Render one page of a flat, chronological lookup with clickable paging. */
    public Component renderFlat(LookupResult page) {
        Component out = Component.text("BlockLogs ", NamedTextColor.AQUA)
                .append(Component.text("時間軸", NamedTextColor.GOLD))
                .append(Component.text("  (第 " + (page.page() + 1) + "/" + page.totalPages()
                        + " 頁, 共 " + page.totalCount() + " 筆)", NamedTextColor.DARK_GRAY));

        if (page.entries().isEmpty()) {
            return out.append(Component.newline())
                    .append(Component.text("  (沒有符合條件的紀錄)", NamedTextColor.DARK_GRAY));
        }

        for (LogEntry e : page.entries()) {
            Component body = Component.text(CLOCK.format(e.time()) + " ", C_TIME)
                    .append(Component.text(e.actor().displayName() + " ", C_ACTOR))
                    .append(Component.text(verb(e.action()) + " ", C_VERB))
                    .append(Component.text(shortMaterial(describe(e)), C_MATERIAL))
                    .append(Component.text("  @ " + coords(e.pos()), C_COORDS));
            if (e.rolledBack()) {
                body = body.decorate(net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH);
            }
            body = body.hoverEvent(HoverEvent.showText(nodeHover(e)));
            out = out.append(Component.newline())
                    .append(Component.text(" • ", C_GRAPH)).append(body);
        }

        // paging controls (flat uses page <n> too — the command layer routes it by view kind)
        List<Component> controls = new ArrayList<>();
        if (page.page() > 0) {
            controls.add(link("[上一頁]", "/bl page " + (page.page() - 1), "上一頁", C_LINK));
        }
        if (page.hasNext()) {
            controls.add(link("[下一頁]", "/bl page " + (page.page() + 1), "下一頁", C_LINK));
        }
        if (!controls.isEmpty()) {
            out = out.append(Component.newline()).append(Component.text("─".repeat(20), C_GRAPH))
                    .append(Component.newline());
            for (int i = 0; i < controls.size(); i++) {
                if (i > 0) {
                    out = out.append(Component.text("  ", NamedTextColor.GRAY));
                }
                out = out.append(controls.get(i));
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Aggregation
    // ─────────────────────────────────────────────────────────────────────────

    /** A rendered row: either a single node, or a run of aggregated sibling leaves. */
    private record Row(CausalNode node, int count, CausalNode representative, boolean anyHasChildren) {
        static Row single(CausalNode n) {
            return new Row(n, 1, n, n.hasChildren());
        }
        static Row aggregate(List<CausalNode> group) {
            boolean anyChildren = group.stream().anyMatch(CausalNode::hasChildren);
            return new Row(null, group.size(), group.get(0), anyChildren);
        }
    }

    /**
     * Collapse consecutive siblings that share {@code (action, material)} into aggregate rows. Nodes are
     * only folded together when neither breaks the run; a node with children is still foldable (its
     * children are reachable via [展開]).
     */
    private List<Row> aggregate(List<CausalNode> siblings) {
        List<Row> rows = new ArrayList<>();
        int i = 0;
        while (i < siblings.size()) {
            CausalNode first = siblings.get(i);
            int j = i + 1;
            while (j < siblings.size() && sameKind(first, siblings.get(j))) {
                j++;
            }
            int run = j - i;
            if (run >= 2) {
                rows.add(Row.aggregate(siblings.subList(i, j)));
            } else {
                rows.add(Row.single(first));
            }
            i = j;
        }
        return rows;
    }

    private boolean sameKind(CausalNode a, CausalNode b) {
        return a.entry().action() == b.entry().action()
                && Objects.equals(materialOf(a), materialOf(b));
    }

    private List<CausalNode> paginate(List<CausalNode> all, int page) {
        int from = Math.max(0, page * PAGE_SIZE);
        int to = Math.min(all.size(), from + PAGE_SIZE);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, to);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Hover tooltips
    // ─────────────────────────────────────────────────────────────────────────

    private Component nodeHover(LogEntry e) {
        Component h = Component.text("#" + e.id(), NamedTextColor.GOLD)
                .append(Component.text("  " + verb(e.action()), C_VERB))
                .append(Component.newline())
                .append(Component.text("時間 ", NamedTextColor.GRAY))
                .append(Component.text(FULL.format(e.time()), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("執行者 ", NamedTextColor.GRAY))
                .append(Component.text(e.actor().displayName(), C_ACTOR))
                .append(Component.newline())
                .append(Component.text("座標 ", NamedTextColor.GRAY))
                .append(Component.text(fullCoords(e.pos()), NamedTextColor.WHITE));

        switch (e) {
            case BlockLogEntry b -> {
                h = h.append(Component.newline())
                        .append(Component.text("方塊 ", NamedTextColor.GRAY))
                        .append(Component.text(b.material(), C_MATERIAL));
                if (b.dataBefore() != null) {
                    h = h.append(Component.newline())
                            .append(Component.text("之前 ", NamedTextColor.GRAY))
                            .append(Component.text(b.dataBefore(), NamedTextColor.DARK_GRAY));
                }
                if (b.dataAfter() != null) {
                    h = h.append(Component.newline())
                            .append(Component.text("之後 ", NamedTextColor.GRAY))
                            .append(Component.text(b.dataAfter(), NamedTextColor.DARK_GRAY));
                }
            }
            case ContainerLogEntry c -> h = h.append(Component.newline())
                    .append(Component.text("容器 ", NamedTextColor.GRAY))
                    .append(Component.text(c.containerType(), C_MATERIAL))
                    .append(Component.newline())
                    .append(Component.text("物品 ", NamedTextColor.GRAY))
                    .append(Component.text(c.item() + " ×" + c.amount(), C_MATERIAL));
            case EntityLogEntry en -> {
                h = h.append(Component.newline())
                        .append(Component.text("實體 ", NamedTextColor.GRAY))
                        .append(Component.text(en.entityType(), C_MATERIAL));
                if (en.data() != null) {
                    h = h.append(Component.newline())
                            .append(Component.text("細節 ", NamedTextColor.GRAY))
                            .append(Component.text(en.data(), NamedTextColor.DARK_GRAY));
                }
            }
        }
        if (e.causeId() != null) {
            h = h.append(Component.newline())
                    .append(Component.text("起因 #" + e.causeId(), NamedTextColor.DARK_GRAY));
        }
        if (e.rolledBack()) {
            h = h.append(Component.newline()).append(Component.text("(已還原)", NamedTextColor.RED));
        }
        return h;
    }

    private Component aggregateHover(Row row) {
        LogEntry rep = row.representative().entry();
        return Component.text("×" + row.count() + " ", NamedTextColor.GOLD)
                .append(Component.text(verb(rep.action()) + " ", C_VERB))
                .append(Component.text(shortMaterial(materialOf(row.representative())), C_MATERIAL))
                .append(Component.newline())
                .append(Component.text(row.count() + " 個相同動作的兄弟節點", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("點 [展開] 展開子項", NamedTextColor.DARK_GRAY));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Clickable link helper
    // ─────────────────────────────────────────────────────────────────────────

    private Component link(String label, String command, String hover, NamedTextColor color) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover + "\n", NamedTextColor.WHITE)
                        .append(Component.text(command, NamedTextColor.DARK_GRAY))));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Formatting helpers  (ActionType -> verb, Instant/WorldPos formatting)
    // ─────────────────────────────────────────────────────────────────────────

    /** Short human verb for an action type. */
    public static String verb(ActionType action) {
        return switch (action) {
            case BLOCK_PLACE -> "放置";
            case BLOCK_BREAK -> "破壞";
            case BLOCK_CHANGE -> "改變";
            case SIGN_CHANGE -> "改牌";
            case BLOCK_EXPLODE -> "炸毀";
            case BLOCK_FLOW -> "流動";
            case CONTAINER_INSERT -> "存入";
            case CONTAINER_REMOVE -> "取出";
            case INTERACT -> "互動";
            case REDSTONE_CHANGE -> "紅石";
            case ENTITY_KILL -> "擊殺";
            case ENTITY_SPAWN -> "生成";
            case ENTITY_MODIFY -> "改變實體";
            case ITEM_DROP -> "丟出";
            case ITEM_PICKUP -> "拾取";
        };
    }

    /** The material/item/entity key that identifies an entry (for aggregation + display). */
    public static String materialOf(CausalNode node) {
        return materialOf(node.entry());
    }

    public static String materialOf(LogEntry e) {
        return switch (e) {
            case BlockLogEntry b -> b.material();
            case ContainerLogEntry c -> c.item();
            case EntityLogEntry en -> en.entityType();
        };
    }

    /** Fuller inline description (adds amount for containers). */
    private static String describe(LogEntry e) {
        return switch (e) {
            case BlockLogEntry b -> b.material();
            case ContainerLogEntry c -> c.item() + " ×" + c.amount();
            case EntityLogEntry en -> en.entityType();
        };
    }

    /** Strip the {@code minecraft:} namespace for compact display. */
    private static String shortMaterial(String key) {
        if (key == null) {
            return "?";
        }
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }

    /** Compact "x, y, z" for the inline line. */
    public static String coords(WorldPos pos) {
        if (pos == null) {
            return "?";
        }
        return pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    /** Full "world x, y, z" for hovers. */
    public static String fullCoords(WorldPos pos) {
        if (pos == null) {
            return "?";
        }
        return pos.world() + " " + pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    public static String formatTime(Instant instant) {
        return FULL.format(instant);
    }

    /** Format a {@link Duration} compactly (used by rollback summaries). */
    public static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
