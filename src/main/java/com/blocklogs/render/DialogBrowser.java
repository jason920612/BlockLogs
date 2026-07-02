package com.blocklogs.render;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.log.BlockLogEntry;
import com.blocklogs.api.log.ContainerLogEntry;
import com.blocklogs.api.log.EntityLogEntry;
import com.blocklogs.api.log.LogEntry;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.query.tree.CausalNode;
import com.blocklogs.core.storage.StorageException;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A native, server-side "Dialog" window browser for the causal tree, built on Paper 26.2's Dialog API.
 *
 * <p>Unlike {@link TreeRenderer} (which draws the tree as clickable chat lines), this opens real
 * cascading dialog windows on a vanilla client: one {@link ActionButton} per {@link CausalNode}, each
 * with a hover tooltip carrying the full node detail (reusing {@link TreeRenderer}'s formatting helpers).
 * Clicking a node with children opens a child dialog (querying children off-thread first); the child's
 * exit button re-opens the parent dialog, giving cascade + loop navigation. Leaf nodes open a detail
 * dialog. Levels with more buttons than fit on a page get 上一頁 / 下一頁 buttons.
 *
 * <p>Threading contract (identical to the rest of the plugin): every {@code queryEngine} read runs off
 * the main thread via the scheduler; the dialog is built and shown back on the main thread.
 */
public final class DialogBrowser {

    /** Buttons per dialog page before we split with 上一頁 / 下一頁. Wide (columns=1) labels. */
    private static final int PAGE_SIZE = 12;

    /** Column count for the multiAction layout. 1 keeps labels wide enough for verb + coords. */
    private static final int COLUMNS = 1;

    /** Button width in pixels (1..1024). */
    private static final int BUTTON_WIDTH = 320;

    /** How long a click callback stays live before Paper expires it. */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(30);

    private final BlockLogsServices services;
    private final Plugin plugin;

    public DialogBrowser(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public entry points
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Open the root view for a query: one button per matching root {@link CausalNode}. Runs the DB read
     * off-thread and shows the dialog on the main thread.
     */
    public void openRoots(Player player, QueryParams params) {
        runAsync(player, () -> {
            List<CausalNode> roots = services.queryEngine().tree(params);
            Component title = Component.text("BlockLogs ", NamedTextColor.AQUA)
                    .append(Component.text("因果樹", NamedTextColor.GOLD));
            Component summary = querySummary(params, roots.size());
            Dialog dialog = buildLevel(player, title, summary, roots, null, 0);
            show(player, dialog);
        });
    }

    /**
     * Open a node by id: query it (and its children) off-thread, then show a dialog for it. If the node
     * has children they become the buttons; the {@code parentToReturnTo} dialog (nullable) is re-opened
     * by this dialog's 返回 button. A leaf node shows a detail dialog.
     */
    public void openNode(Player player, long nodeId, Dialog parentToReturnTo) {
        runAsync(player, () -> {
            CausalNode node = services.queryEngine().expand(nodeId);
            if (node == null) {
                messagePlayer(player, Component.text("找不到節點 #" + nodeId, NamedTextColor.RED));
                return;
            }
            Dialog dialog = buildNodeDialog(player, node, parentToReturnTo);
            show(player, dialog);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dialog construction  (main thread only — no DB access here)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a dialog for a single {@code node}: body = the node's own detail; buttons = its children
     * (already loaded on {@code node.children()}); exit = 返回 to {@code parent} (nullable).
     */
    private Dialog buildNodeDialog(Player player, CausalNode node, Dialog parent) {
        LogEntry e = node.entry();
        Component title = Component.text(verb(e.action()) + " ", NamedTextColor.GOLD)
                .append(Component.text(shortLabel(e), NamedTextColor.WHITE));
        Component body = detail(e);
        return buildLevel(player, title, body, node.children(), parent, 0);
    }

    /**
     * Build one page of a level: a multiAction dialog whose buttons are one page of {@code nodes}, plus
     * paging buttons when the level overflows one page. The {@code exit} (返回) re-opens {@code parent}.
     *
     * @param nodes  all sibling nodes at this level (already fetched)
     * @param parent the dialog to return to (nullable — null at the roots)
     * @param page   zero-based page index into {@code nodes}
     */
    private Dialog buildLevel(Player player, Component title, Component body,
                              List<CausalNode> nodes, Dialog parent, int page) {
        int total = nodes.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, pages - 1));
        int from = safePage * PAGE_SIZE;
        int to = Math.min(total, from + PAGE_SIZE);

        // Self-reference: node buttons need to hand THIS dialog to their children as the 返回 target.
        // Populated after Dialog.create below; captured lazily by the button callbacks (which only fire
        // on a later click, by which point selfRef[0] is set).
        final Dialog[] selfRef = new Dialog[1];

        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            buttons.add(nodeButton(player, nodes.get(i), selfRef));
        }

        // Paging buttons re-open THIS same level at a different page (state captured in the closure).
        if (pages > 1) {
            final List<CausalNode> allNodes = nodes;
            final Dialog parentRef = parent;
            final Component titleRef = title;
            final Component bodyRef = body;
            if (safePage > 0) {
                buttons.add(simpleButton(
                        Component.text("⬆ 上一頁 (" + safePage + "/" + pages + ")", NamedTextColor.YELLOW),
                        Component.text("回到上一頁", NamedTextColor.GRAY),
                        (view, audience) -> show(player,
                                buildLevel(player, titleRef, bodyRef, allNodes, parentRef, safePage - 1))));
            }
            if (safePage < pages - 1) {
                buttons.add(simpleButton(
                        Component.text("⬇ 下一頁 (" + (safePage + 2) + "/" + pages + ")", NamedTextColor.YELLOW),
                        Component.text("查看下一頁", NamedTextColor.GRAY),
                        (view, audience) -> show(player,
                                buildLevel(player, titleRef, bodyRef, allNodes, parentRef, safePage + 1))));
            }
        }

        ActionButton exit = exitButton(player, parent);

        List<DialogBody> bodyList = List.of(DialogBody.plainMessage(pageFooter(body, safePage, pages, total)));
        // multiAction requires a NON-EMPTY action list (Paper throws "actions cannot be empty"; the
        // exitAction does not count). An empty level (no child buttons, no paging) falls back to a
        // single-button notice carrying just the 返回/關閉 button.
        DialogType type = buttons.isEmpty()
                ? DialogType.notice(exit)
                : DialogType.multiAction(buttons, exit, COLUMNS);
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(bodyList)
                        .inputs(List.of())
                        .build())
                .type(type));
        selfRef[0] = dialog;
        return dialog;
    }

    /**
     * Build the button for one node. Label = verb + material/desc (+ " ▸ ×N" for nodes with children);
     * tooltip = full detail. Clicking a node WITH children queries its children off-thread then opens a
     * child dialog whose 返回 loops back to the dialog we are building now. Clicking a LEAF opens its
     * detail dialog.
     */
    private ActionButton nodeButton(Player player, CausalNode node, Dialog[] parentRef) {
        LogEntry e = node.entry();
        long id = e.id();

        Component label = Component.text(verb(e.action()) + " ", NamedTextColor.YELLOW)
                .append(Component.text(shortLabel(e), NamedTextColor.WHITE))
                .append(Component.text("  @ " + TreeRenderer.coords(e.pos()), NamedTextColor.GRAY));
        if (node.hasChildren()) {
            label = label.append(Component.text("  ▸ ×" + node.childCount(), NamedTextColor.GOLD));
        }
        if (e.rolledBack()) {
            label = label.append(Component.text("  (已還原)", NamedTextColor.RED));
        }

        Component tooltip = detail(e);

        return ActionButton.create(label, tooltip, BUTTON_WIDTH, DialogAction.customClick(
                (view, audience) -> {
                    // parentRef[0] is the dialog that owns this button; it is set by the time the user
                    // clicks (buildLevel populated it right after Dialog.create). The child/leaf dialog's
                    // 返回 loops back to it.
                    Dialog parent = parentRef[0];
                    if (node.hasChildren()) {
                        // Re-query children off-thread, then open the child dialog on the main thread.
                        openNode(player, id, parent);
                    } else {
                        show(player, buildLeafDialog(player, node, parent));
                    }
                },
                callbackOptions()));
    }

    /** A leaf detail dialog: body = full detail, a single 返回 (or 關閉) button. */
    private Dialog buildLeafDialog(Player player, CausalNode node, Dialog parent) {
        LogEntry e = node.entry();
        Component title = Component.text(verb(e.action()) + " ", NamedTextColor.GOLD)
                .append(Component.text(shortLabel(e), NamedTextColor.WHITE));
        ActionButton exit = exitButton(player, parent);
        List<DialogBody> bodyList = List.of(DialogBody.plainMessage(detail(e)));
        // Leaf = single-button notice (multiAction requires a non-empty action list).
        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(bodyList)
                        .inputs(List.of())
                        .build())
                .type(DialogType.notice(exit)));
    }

    /** The exit button: 返回 to {@code parent} if present, else 關閉 (just closes). */
    private ActionButton exitButton(Player player, Dialog parent) {
        if (parent != null) {
            return simpleButton(
                    Component.text("⬅ 返回", NamedTextColor.LIGHT_PURPLE),
                    Component.text("回到上一層", NamedTextColor.GRAY),
                    (view, audience) -> audience.showDialog(parent));
        }
        return simpleButton(
                Component.text("關閉", NamedTextColor.GRAY),
                Component.text("關閉視窗", NamedTextColor.DARK_GRAY),
                (view, audience) -> player.closeDialog());
    }

    private ActionButton simpleButton(Component label, Component tooltip,
                                      io.papermc.paper.registry.data.dialog.action.DialogActionCallback cb) {
        return ActionButton.create(label, tooltip, BUTTON_WIDTH,
                DialogAction.customClick(cb, callbackOptions()));
    }

    private ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Detail / summary components  (reuse TreeRenderer static helpers)
    // ─────────────────────────────────────────────────────────────────────────

    /** Full multi-line detail for a node — same fields as {@link TreeRenderer}'s hover. */
    private Component detail(LogEntry e) {
        Component d = Component.text("#" + e.id(), NamedTextColor.GOLD)
                .append(Component.text("  " + verb(e.action()), NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(kv("時間", TreeRenderer.formatTime(e.time())))
                .append(Component.newline())
                .append(kv("執行者", e.actor().displayName()))
                .append(Component.newline())
                .append(kv("座標", TreeRenderer.fullCoords(e.pos())));

        switch (e) {
            case BlockLogEntry b -> {
                d = d.append(Component.newline()).append(kv("方塊", b.material()));
                if (b.dataBefore() != null) {
                    d = d.append(Component.newline()).append(kv("之前", b.dataBefore()));
                }
                if (b.dataAfter() != null) {
                    d = d.append(Component.newline()).append(kv("之後", b.dataAfter()));
                }
            }
            case ContainerLogEntry c -> d = d.append(Component.newline())
                    .append(kv("容器", c.containerType()))
                    .append(Component.newline())
                    .append(kv("物品", c.item() + " ×" + c.amount()));
            case EntityLogEntry en -> {
                d = d.append(Component.newline()).append(kv("實體", en.entityType()));
                if (en.data() != null) {
                    d = d.append(Component.newline()).append(kv("細節", en.data()));
                }
            }
        }
        if (e.causeId() != null) {
            d = d.append(Component.newline())
                    .append(Component.text("起因 #" + e.causeId(), NamedTextColor.DARK_GRAY));
        }
        if (e.rolledBack()) {
            d = d.append(Component.newline()).append(Component.text("(已還原)", NamedTextColor.RED));
        }
        return d;
    }

    private Component kv(String key, String value) {
        return Component.text(key + " ", NamedTextColor.GRAY)
                .append(Component.text(value == null ? "?" : value, NamedTextColor.WHITE));
    }

    /** A short "query summary" body for the root dialog: active filters + root count. */
    private Component querySummary(QueryParams p, int rootCount) {
        List<String> filters = new ArrayList<>();
        if (!p.actorNames().isEmpty()) {
            filters.add("玩家=" + String.join(",", p.actorNames()));
        }
        if (!p.actions().isEmpty()) {
            List<String> verbs = new ArrayList<>();
            p.actions().forEach(a -> verbs.add(verb(a)));
            filters.add("動作=" + String.join(",", verbs));
        }
        if (!p.categories().isEmpty()) {
            filters.add("類別=" + p.categories());
        }
        if (p.world() != null) {
            filters.add("世界=" + p.world());
        }
        if (p.center() != null && p.radius() != null) {
            filters.add("範圍=" + TreeRenderer.coords(p.center()) + " r" + p.radius());
        }
        if (!p.materials().isEmpty()) {
            filters.add("材質=" + String.join(",", p.materials()));
        }
        if (p.since() != null) {
            filters.add("自 " + TreeRenderer.formatTime(p.since()));
        }

        Component c = Component.text("根節點 " + rootCount + " 筆", NamedTextColor.AQUA);
        if (filters.isEmpty()) {
            c = c.append(Component.newline())
                    .append(Component.text("(無過濾條件)", NamedTextColor.DARK_GRAY));
        } else {
            for (String f : filters) {
                c = c.append(Component.newline()).append(Component.text(f, NamedTextColor.GRAY));
            }
        }
        return c;
    }

    /** Append a "第 x/y 頁" line to a body when a level is paginated. */
    private Component pageFooter(Component body, int page, int pages, int total) {
        if (pages <= 1) {
            return body;
        }
        return body.append(Component.newline())
                .append(Component.text("第 " + (page + 1) + "/" + pages + " 頁 (共 " + total + " 筆)",
                        NamedTextColor.DARK_GRAY));
    }

    /** Compact label for a node's button/title (verb goes elsewhere). */
    private String shortLabel(LogEntry e) {
        String key = TreeRenderer.materialOf(e);
        String shortKey = key == null ? "?"
                : (key.indexOf(':') >= 0 ? key.substring(key.indexOf(':') + 1) : key);
        if (e instanceof ContainerLogEntry c) {
            return shortKey + " ×" + c.amount();
        }
        return shortKey;
    }

    private static String verb(com.blocklogs.api.action.ActionType action) {
        return TreeRenderer.verb(action);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Threading helpers  (mirror BlCommand / InspectorClickListener patterns)
    // ─────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Query {
        void run() throws StorageException;
    }

    private void runAsync(Player player, Query body) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                body.run();
            } catch (StorageException ex) {
                messagePlayer(player, Component.text("儲存錯誤: " + ex.getMessage(), NamedTextColor.RED));
            } catch (RuntimeException ex) {
                messagePlayer(player, Component.text("開啟視窗失敗: " + ex.getMessage(), NamedTextColor.RED));
            }
        });
    }

    /** Show a dialog on the main thread. */
    private void show(Player player, Dialog dialog) {
        plugin.getServer().getScheduler().runTask(plugin, () -> player.showDialog(dialog));
    }

    /** Send a chat message on the main thread. */
    private void messagePlayer(Player player, Component message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(message));
    }
}
