package com.blocklogs.render;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.action.ActionCategory;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.QueryParams;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A native Paper 26.2 Dialog "query form": instead of typing {@code /bl lookup user:x r:10 t:1h ...},
 * the player fills a small form (name text field, time-range / category dropdowns, radius slider,
 * two toggles) and clicks 查詢. The submitted values are assembled into {@link QueryParams} and the
 * existing {@link DialogBrowser} is opened over the resulting causal tree.
 *
 * <p>Built + shown on the main thread (mirrors {@link DialogBrowser}); the actual DB read happens
 * async inside {@link DialogBrowser#openRoots} — this class does no I/O.
 */
public final class QueryFormDialog {

    /** Match {@code DialogBrowser}'s wide, single-column layout. */
    private static final int INPUT_WIDTH = 320;

    /** Result page size — matches {@link DialogBrowser}'s PAGE_SIZE (12). */
    private static final int PAGE_SIZE = 12;

    /** How long the submit callback stays live before Paper expires it (mirrors DialogBrowser). */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(30);

    private final BlockLogsServices services;
    private final Plugin plugin;

    public QueryFormDialog(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.plugin = plugin;
    }

    /** Build and show the query form to {@code player}. */
    public void open(Player player) {
        Component title = Component.text("BlockLogs ", NamedTextColor.AQUA)
                .append(Component.text("查詢表單", NamedTextColor.GOLD));
        Component intro = Component.text("填寫條件後按「查詢」開啟因果樹瀏覽器。", NamedTextColor.GRAY);

        List<DialogInput> inputs = List.of(
                DialogInput.text("player", Component.text("玩家名稱 (空白 = 所有玩家)"))
                        .width(INPUT_WIDTH)
                        .labelVisible(true)
                        .initial("")
                        .maxLength(32)
                        .build(),
                DialogInput.singleOption("time", Component.text("時間範圍"), List.of(
                                SingleOptionDialogInput.OptionEntry.create("0", Component.text("全部"), true),
                                SingleOptionDialogInput.OptionEntry.create("600", Component.text("近10分"), false),
                                SingleOptionDialogInput.OptionEntry.create("3600", Component.text("近1小時"), false),
                                SingleOptionDialogInput.OptionEntry.create("21600", Component.text("近6小時"), false),
                                SingleOptionDialogInput.OptionEntry.create("86400", Component.text("近1天"), false),
                                SingleOptionDialogInput.OptionEntry.create("604800", Component.text("近7天"), false)))
                        .width(INPUT_WIDTH)
                        .labelVisible(true)
                        .build(),
                // Radius slider 0..100 (0 = 全世界不限範圍). If labelFormat "%s" renders oddly on some
                // clients, fall back to a singleOption of preset radii (0/5/10/20/50/100) instead.
                DialogInput.numberRange("radius", Component.text("半徑 (0 = 全世界)"), 0f, 100f)
                        .width(INPUT_WIDTH)
                        .labelFormat("%s")
                        .initial(0f)
                        .step(1f)
                        .build(),
                DialogInput.singleOption("category", Component.text("類別"), List.of(
                                SingleOptionDialogInput.OptionEntry.create("all", Component.text("全部"), true),
                                SingleOptionDialogInput.OptionEntry.create("BLOCK", Component.text("方塊"), false),
                                SingleOptionDialogInput.OptionEntry.create("CONTAINER", Component.text("容器"), false),
                                SingleOptionDialogInput.OptionEntry.create("INTERACTION", Component.text("互動"), false),
                                SingleOptionDialogInput.OptionEntry.create("REDSTONE", Component.text("紅石"), false),
                                SingleOptionDialogInput.OptionEntry.create("ENTITY", Component.text("生物"), false),
                                SingleOptionDialogInput.OptionEntry.create("ITEM", Component.text("物品"), false)))
                        .width(INPUT_WIDTH)
                        .labelVisible(true)
                        .build(),
                DialogInput.bool("thisWorld", Component.text("只看目前世界"))
                        .initial(true)
                        .onTrue("true")
                        .onFalse("false")
                        .build(),
                DialogInput.bool("hideRolledBack", Component.text("隱藏已還原"))
                        .initial(false)
                        .onTrue("true")
                        .onFalse("false")
                        .build());

        ActionButton submit = ActionButton.create(
                Component.text("查詢"),
                Component.text("執行查詢"),
                200,
                DialogAction.customClick(
                        (view, audience) -> onSubmit(player, view),
                        ClickCallback.Options.builder()
                                .uses(ClickCallback.UNLIMITED_USES)
                                .lifetime(CALLBACK_LIFETIME)
                                .build()));

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        // If submitted input values come back null at runtime, switch this to
                        // DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE.
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(intro)))
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(List.of(submit), null, 1)));

        player.showDialog(dialog);
    }

    /**
     * Read the submitted values (null-safe), assemble {@link QueryParams}, and hand off to the existing
     * {@link DialogBrowser}. This runs on the main thread (the customClick callback fires there); the
     * browser queries the DB async internally, matching how {@code BlCommand#gui} calls it.
     */
    private void onSubmit(Player player, DialogResponseView view) {
        try {
            QueryParams.Builder b = QueryParams.builder().limit(PAGE_SIZE);

            String playerText = view.getText("player");
            if (playerText != null && !playerText.isBlank()) {
                b.actorNames(Set.of(playerText.trim()));
            }

            String timeId = view.getText("time");
            long seconds = parseLongSafe(timeId);
            if (seconds > 0) {
                b.since(Instant.now().minusSeconds(seconds));
            }

            Float radius = view.getFloat("radius");
            if (radius != null && radius >= 1f) {
                WorldPos center = new WorldPos(player.getWorld().getName(),
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(),
                        player.getLocation().getBlockZ());
                b.center(center).radius((int) (float) radius);
            }

            String categoryId = view.getText("category");
            if (categoryId != null && !categoryId.isBlank() && !categoryId.equals("all")) {
                b.categories(EnumSet.of(ActionCategory.valueOf(categoryId)));
            }

            Boolean thisWorld = view.getBoolean("thisWorld");
            if (thisWorld != null && thisWorld) {
                b.world(player.getWorld().getName());
            }

            Boolean hideRolledBack = view.getBoolean("hideRolledBack");
            if (hideRolledBack != null && hideRolledBack) {
                b.excludeRolledBack(true);
            }

            QueryParams params = b.build();
            new DialogBrowser(services, plugin).openRoots(player, params);
        } catch (RuntimeException ex) {
            player.sendMessage(Component.text("查詢表單處理失敗: " + ex.getMessage(), NamedTextColor.RED));
        }
    }

    /** Parse a numeric id (seconds) from a dropdown, defaulting to 0 on null/garbage. */
    private static long parseLongSafe(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
