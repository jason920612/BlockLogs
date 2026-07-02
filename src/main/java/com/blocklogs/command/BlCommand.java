package com.blocklogs.command;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionCategory;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.LookupResult;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.query.session.QuerySession;
import com.blocklogs.core.query.session.QuerySessionManager;
import com.blocklogs.core.query.tree.CausalNode;
import com.blocklogs.core.rollback.RollbackResult;
import com.blocklogs.core.storage.StorageException;
import com.blocklogs.render.DialogBrowser;
import com.blocklogs.render.QueryFormDialog;
import com.blocklogs.render.TreeRenderer;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Root {@code /bl} command. Parses filter tokens into {@link QueryParams}, drives the query / rollback /
 * session services (always off the main thread), and renders the clickable, hover-rich causal tree via
 * {@link TreeRenderer}.
 *
 * <p>Navigation is git-like: {@code lookup} opens a persistent, shareable {@link QuerySession}; {@code expand},
 * {@code goto}, {@code back} and {@code page} mutate that session's state and re-render.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BlCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "lookup", "flat", "gui", "find", "inspect", "expand", "goto", "back", "page",
            "session", "log", "rollback", "restore", "purge", "reload", "fold", "unfold");

    private static final List<String> FILTER_KEYS = List.of(
            "user:", "a:", "r:", "t:", "world:", "b:");

    private static final int PAGE_SIZE = 10;

    private final BlockLogsServices services;
    private final Plugin plugin;

    public BlCommand(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = tail(args);
        switch (sub) {
            case "help", "?" -> sendHelp(sender);
            case "inspect", "i" -> toggleInspector(sender);
            case "lookup", "l" -> lookup(sender, rest);
            case "flat" -> flat(sender, rest);
            case "gui", "g" -> gui(sender, rest);
            case "find", "f" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("查詢表單須由玩家執行 (需要位置資訊)。", NamedTextColor.RED));
                } else {
                    new QueryFormDialog(services, plugin).open(player);
                }
            }
            case "expand" -> expand(sender, rest);
            case "goto" -> gotoNode(sender, rest);
            case "back" -> back(sender);
            case "page" -> page(sender, rest);
            case "session", "s" -> session(sender, rest);
            case "log" -> log(sender);
            case "rollback", "rb" -> rollback(sender, rest, false);
            case "restore", "rs" -> rollback(sender, rest, true);
            case "purge" -> purge(sender, rest);
            case "reload" -> reload(sender);
            case "unfold" -> setAggregate(sender, false);
            case "fold" -> setAggregate(sender, true);
            default -> {
                sender.sendMessage(Component.text("未知的子指令: " + args[0], NamedTextColor.RED));
                sendHelp(sender);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  lookup / flat
    // ─────────────────────────────────────────────────────────────────────────

    private void lookup(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("查詢須由玩家執行 (需要位置資訊)。", NamedTextColor.RED));
            return;
        }
        QueryParams params;
        try {
            params = parseParams(player, tokens, PAGE_SIZE);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("過濾條件錯誤: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }

        runAsync(sender, () -> {
            QuerySession session = services.sessionManager().create(player.getUniqueId(), params);
            List<CausalNode> roots = services.queryEngine().tree(params);
            Component out = new TreeRenderer().renderTree(roots, session, services.queryEngine());
            send(sender, out);
        });
    }

    private void flat(CommandSender sender, String[] tokens) {
        Player player = sender instanceof Player p ? p : null;
        QueryParams params;
        try {
            params = parseParams(player, tokens, 100);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("過濾條件錯誤: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        runAsync(sender, () -> {
            LookupResult page = services.queryEngine().lookup(params);
            send(sender, new TreeRenderer().renderFlat(page));
        });
    }

    /**
     * Native Dialog-window browser over the causal tree. Same filter parsing as {@code lookup}, but
     * instead of chat lines it opens real cascading server-side Dialog windows (Paper 26.2 Dialog API):
     * one button per root, hover tooltips, and back/loop navigation on a vanilla client.
     */
    private void gui(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("GUI 檢視須由玩家執行 (需要位置資訊)。", NamedTextColor.RED));
            return;
        }
        QueryParams params;
        try {
            params = parseParams(player, tokens, PAGE_SIZE);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("過濾條件錯誤: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        new DialogBrowser(services, plugin).openRoots(player, params);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  session navigation: expand / goto / back / page / session / log
    // ─────────────────────────────────────────────────────────────────────────

    private void expand(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        Long id = parseLong(tokens);
        if (id == null) {
            sender.sendMessage(Component.text("用法: /bl expand <id>", NamedTextColor.RED));
            return;
        }
        QuerySession session = services.sessionManager().active(player.getUniqueId());
        runAsync(sender, () -> {
            if (session != null) {
                session.expand(id);
                services.sessionManager().save(session);
            }
            CausalNode node = services.queryEngine().expand(id);
            if (node == null) {
                send(sender, Component.text("找不到節點 #" + id, NamedTextColor.RED));
                return;
            }
            QuerySession view = session != null ? session
                    : new QuerySession(player.getUniqueId(), Instant.now(),
                            QueryParams.builder().build());
            Component out = Component.text("展開 #" + id, NamedTextColor.AQUA)
                    .append(Component.newline())
                    .append(new TreeRenderer().renderTree(node.children(), view, services.queryEngine()));
            send(sender, out);
        });
    }

    private void gotoNode(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        Long id = parseLong(tokens);
        if (id == null) {
            sender.sendMessage(Component.text("用法: /bl goto <id>", NamedTextColor.RED));
            return;
        }
        QuerySession session = services.sessionManager().active(player.getUniqueId());
        if (session == null) {
            sender.sendMessage(Component.text("沒有進行中的查詢，請先執行 /bl lookup。", NamedTextColor.RED));
            return;
        }
        session.drillInto(id);
        session.page(0);
        runAsync(sender, () -> {
            services.sessionManager().save(session);
            CausalNode node = services.queryEngine().expand(id);
            List<CausalNode> children = node == null ? List.of() : node.children();
            send(sender, new TreeRenderer().renderTree(children, session, services.queryEngine()));
        });
    }

    private void back(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        QuerySession session = services.sessionManager().active(player.getUniqueId());
        if (session == null || session.head() == null) {
            sender.sendMessage(Component.text("已經在最上層了。", NamedTextColor.YELLOW));
            return;
        }
        session.back();
        session.page(0);
        rerender(sender, session);
    }

    private void page(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        Long n = parseLong(tokens);
        if (n == null || n < 0) {
            sender.sendMessage(Component.text("用法: /bl page <n>", NamedTextColor.RED));
            return;
        }
        QuerySession session = services.sessionManager().active(player.getUniqueId());
        if (session == null) {
            sender.sendMessage(Component.text("沒有進行中的查詢。", NamedTextColor.RED));
            return;
        }
        session.page(n.intValue());
        rerender(sender, session);
    }

    private void session(CommandSender sender, String[] tokens) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        Long id = parseLong(tokens);
        if (id == null) {
            sender.sendMessage(Component.text("用法: /bl session <id>", NamedTextColor.RED));
            return;
        }
        runAsync(sender, () -> {
            QuerySession session = services.sessionManager().open(id.intValue());
            if (session == null) {
                send(sender, Component.text("找不到查詢 #" + id, NamedTextColor.RED));
                return;
            }
            renderSessionView(sender, session);
        });
    }

    private void log(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        runAsync(sender, () -> {
            List<QuerySession> history = services.sessionManager().history(player.getUniqueId());
            Component out = Component.text("已儲存的查詢:", NamedTextColor.AQUA);
            if (history.isEmpty()) {
                out = out.append(Component.newline())
                        .append(Component.text("  (沒有)", NamedTextColor.DARK_GRAY));
            }
            for (QuerySession s : history) {
                Component row = Component.text(" • ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("#" + s.id() + " ", NamedTextColor.GOLD))
                        .append(Component.text(TreeRenderer.formatTime(s.createdAt()), NamedTextColor.GRAY))
                        .append(Component.space())
                        .append(Component.text("[開啟]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/bl session " + s.id()))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("重新開啟查詢 #" + s.id(), NamedTextColor.WHITE))));
                out = out.append(Component.newline()).append(row);
            }
            send(sender, out);
        });
    }

    /** Re-render the current HEAD of an already-loaded session (used by back/page). */
    private void rerender(CommandSender sender, QuerySession session) {
        runAsync(sender, () -> {
            services.sessionManager().save(session);
            renderSessionView(sender, session);
        });
    }

    /** Fetch and render whatever the session's HEAD currently points at. Runs off-thread. */
    private void renderSessionView(CommandSender sender, QuerySession session) throws StorageException {
        List<CausalNode> roots;
        Long head = session.head();
        if (head == null) {
            roots = services.queryEngine().tree(session.params());
        } else {
            CausalNode node = services.queryEngine().expand(head);
            roots = node == null ? List.of() : node.children();
        }
        send(sender, new TreeRenderer().renderTree(roots, session, services.queryEngine()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  rollback / restore / purge / reload
    // ─────────────────────────────────────────────────────────────────────────

    private void rollback(CommandSender sender, String[] tokens, boolean restore) {
        if (!sender.hasPermission("blocklogs.rollback")) {
            noPerm(sender, "blocklogs.rollback");
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        QueryParams params;
        try {
            params = parseParams(player, tokens, Integer.MAX_VALUE);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("過濾條件錯誤: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        runAsync(sender, () -> {
            services.logging().flush();
            RollbackResult result = restore
                    ? services.rollbackEngine().restore(params)
                    : services.rollbackEngine().rollback(params);
            send(sender, renderRollback(result));
        });
    }

    private Component renderRollback(RollbackResult r) {
        Component out = Component.text(r.restore() ? "還原完成" : "回滾完成",
                        r.preview() ? NamedTextColor.YELLOW : NamedTextColor.GREEN)
                .append(Component.text(r.preview() ? " (預覽)" : "", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("  受影響 " + r.totalAffected() + " 筆, "
                        + "方塊 " + r.blocksChanged() + ", 區塊 " + r.chunksTouched()
                        + ", 耗時 " + TreeRenderer.formatDuration(r.duration()), NamedTextColor.GRAY));
        for (Map.Entry<ActionCategory, Integer> e : r.affectedByCategory().entrySet()) {
            out = out.append(Component.newline())
                    .append(Component.text("    " + e.getKey() + ": " + e.getValue(), NamedTextColor.DARK_GRAY));
        }
        return out;
    }

    private void purge(CommandSender sender, String[] tokens) {
        if (!sender.hasPermission("blocklogs.admin")) {
            noPerm(sender, "blocklogs.admin");
            return;
        }
        // Interpret an optional t:<duration> as the cutoff; otherwise fall back to config retention.
        Duration cutoff = null;
        for (String t : tokens) {
            if (t.startsWith("t:")) {
                cutoff = parseDuration(t.substring(2));
            }
        }
        Duration retention = cutoff != null ? cutoff
                : Duration.ofDays(Math.max(1, services.config().retentionDays()));
        Instant olderThan = Instant.now().minus(retention);
        runAsync(sender, () -> {
            int removed = services.database().purge(olderThan);
            send(sender, Component.text("已清除 " + removed + " 筆早於 "
                    + TreeRenderer.formatTime(olderThan) + " 的紀錄。", NamedTextColor.GREEN));
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("blocklogs.admin")) {
            noPerm(sender, "blocklogs.admin");
            return;
        }
        if (plugin instanceof com.blocklogs.BlockLogsPlugin blp) {
            blp.reload();
            sender.sendMessage(Component.text("已重新載入 config.yml (寫入批次與因果 TTL 需重啟才生效)。",
                    NamedTextColor.GREEN));
        } else {
            plugin.reloadConfig();
            sender.sendMessage(Component.text("已重新載入 config.yml。", NamedTextColor.YELLOW));
        }
    }

    /** Toggle chat-tree aggregation (folded ×N rows) for the active session, then re-render. */
    private void setAggregate(CommandSender sender, boolean aggregate) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("僅玩家可用。", NamedTextColor.RED));
            return;
        }
        QuerySession session = services.sessionManager().active(player.getUniqueId());
        if (session == null) {
            sender.sendMessage(Component.text("沒有進行中的查詢，請先執行 /bl lookup。", NamedTextColor.RED));
            return;
        }
        session.aggregate(aggregate);
        session.page(0);
        rerender(sender, session);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  filter parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse filter tokens into {@link QueryParams}. Supported tokens:
     * <ul>
     *   <li>{@code user:<name>[,<name>]} — actor names</li>
     *   <li>{@code a:<ACTION|CATEGORY>} — action types or categories</li>
     *   <li>{@code r:<radius>} or a bare number — radius around the player's block</li>
     *   <li>{@code t:<30m|3h|2d>} — since = now − duration</li>
     *   <li>{@code world:<name>} — world filter</li>
     *   <li>{@code b:<material>[,<material>]} — block/item material keys</li>
     * </ul>
     */
    private QueryParams parseParams(Player player, String[] tokens, int limit) {
        QueryParams.Builder b = QueryParams.builder().limit(limit).excludeRolledBack(false);
        Set<String> actorNames = new java.util.HashSet<>();
        Set<ActionType> actions = EnumSet.noneOf(ActionType.class);
        Set<ActionCategory> categories = EnumSet.noneOf(ActionCategory.class);
        Set<String> materials = new java.util.HashSet<>();
        Integer radius = null;

        for (String tok : tokens) {
            if (tok.isBlank()) {
                continue;
            }
            String lower = tok.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user:")) {
                for (String n : split(tok.substring(5))) {
                    actorNames.add(n);
                }
            } else if (lower.startsWith("a:")) {
                for (String a : split(tok.substring(2))) {
                    parseAction(a, actions, categories);
                }
            } else if (lower.startsWith("r:")) {
                radius = parseInt(tok.substring(2), "radius");
            } else if (lower.startsWith("t:")) {
                b.since(Instant.now().minus(parseDuration(tok.substring(2))));
            } else if (lower.startsWith("world:")) {
                b.world(tok.substring(6));
            } else if (lower.startsWith("b:")) {
                for (String m : split(tok.substring(2))) {
                    materials.add(namespaced(m));
                }
            } else if (lower.matches("\\d+")) {
                radius = parseInt(tok, "radius");
            } else {
                throw new IllegalArgumentException("無法識別的條件 '" + tok + "'");
            }
        }

        if (!actorNames.isEmpty()) {
            b.actorNames(actorNames);
        }
        if (!actions.isEmpty()) {
            b.actions(actions);
        }
        if (!categories.isEmpty()) {
            b.categories(categories);
        }
        if (!materials.isEmpty()) {
            b.materials(materials);
        }
        if (radius != null) {
            if (player == null) {
                throw new IllegalArgumentException("radius 需要玩家位置");
            }
            WorldPos center = new WorldPos(player.getWorld().getName(),
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ());
            b.center(center).radius(radius).world(player.getWorld().getName());
        }
        return b.build();
    }

    private void parseAction(String token, Set<ActionType> actions, Set<ActionCategory> categories) {
        String up = token.toUpperCase(Locale.ROOT);
        for (ActionType t : ActionType.values()) {
            if (t.name().equals(up)) {
                actions.add(t);
                return;
            }
        }
        for (ActionCategory c : ActionCategory.values()) {
            if (c.name().equals(up)) {
                categories.add(c);
                return;
            }
        }
        throw new IllegalArgumentException("未知的動作/類別 '" + token + "'");
    }

    /** Parse a duration like {@code 30m}, {@code 3h}, {@code 2d}, {@code 45s}, {@code 1w}. */
    private Duration parseDuration(String s) {
        if (s == null || s.length() < 2) {
            throw new IllegalArgumentException("時間格式錯誤 (例: 30m, 3h, 2d)");
        }
        char unit = Character.toLowerCase(s.charAt(s.length() - 1));
        long amount;
        try {
            amount = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("時間格式錯誤 '" + s + "'");
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            case 'w' -> Duration.ofDays(amount * 7);
            default -> throw new IllegalArgumentException("未知的時間單位 '" + unit + "'");
        };
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(what + " 必須是數字");
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String namespaced(String key) {
        return key.contains(":") ? key.toLowerCase(Locale.ROOT) : "minecraft:" + key.toLowerCase(Locale.ROOT);
    }

    private static Long parseLong(String[] tokens) {
        if (tokens.length == 0) {
            return null;
        }
        try {
            return Long.parseLong(tokens[0].trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  async plumbing + misc
    // ─────────────────────────────────────────────────────────────────────────

    /** Body that may throw {@link StorageException}, run off the main thread. */
    @FunctionalInterface
    private interface Query {
        void run() throws StorageException;
    }

    private void runAsync(CommandSender sender, Query body) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                body.run();
            } catch (StorageException ex) {
                send(sender, Component.text("儲存錯誤: " + ex.getMessage(), NamedTextColor.RED));
            } catch (RuntimeException ex) {
                send(sender, Component.text("查詢失敗: " + ex.getMessage(), NamedTextColor.RED));
            }
        });
    }

    /** Marshal a chat send back onto the main thread. */
    private void send(CommandSender sender, Component message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private void toggleInspector(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("檢查模式僅供玩家使用。", NamedTextColor.RED));
            return;
        }
        boolean on = services.inspectorService().toggle(player.getUniqueId());
        player.sendMessage(Component.text("檢查模式已" + (on ? "開啟" : "關閉") + "。",
                on ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void noPerm(CommandSender sender, String node) {
        sender.sendMessage(Component.text("你沒有權限 (" + node + ")。", NamedTextColor.RED));
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        // For query-ish subcommands, suggest filter keys on the current (last) token.
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("lookup") || sub.equals("l") || sub.equals("flat")
                || sub.equals("gui") || sub.equals("g")
                || sub.equals("rollback") || sub.equals("rb")
                || sub.equals("restore") || sub.equals("rs")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return FILTER_KEYS.stream().filter(k -> k.startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return "blocklogs.use";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("BlockLogs 指令:", NamedTextColor.AQUA));
        help(sender, "/bl lookup <過濾>", "因果樹檢視 (預設)");
        help(sender, "/bl flat <過濾>", "時間軸列表");
        help(sender, "/bl gui <過濾>", "原生視窗因果樹瀏覽");
        help(sender, "/bl find", "開啟查詢表單");
        help(sender, "/bl inspect", "切換點擊檢查模式");
        help(sender, "/bl expand <id>", "展開節點的子項");
        help(sender, "/bl goto <id> | back", "鑽入 / 返回");
        help(sender, "/bl session <id> | log", "分享 / 列出查詢");
        help(sender, "/bl rollback|restore <過濾>", "回滾 / 還原");
        help(sender, "/bl purge | reload", "維護 (管理員)");
        sender.sendMessage(Component.text("  過濾: user:名字 a:動作 r:半徑 t:30m world:名稱 b:材質",
                NamedTextColor.DARK_GRAY));
    }

    private void help(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(Component.text("  " + cmd + "  ", NamedTextColor.WHITE)
                .append(Component.text(desc, NamedTextColor.GRAY)));
    }
}
