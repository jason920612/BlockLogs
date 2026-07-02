package com.blocklogs.listener;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.render.DialogBrowser;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Click-to-inspect handler. While a player has inspector mode enabled (see
 * {@link com.blocklogs.core.inspect.InspectorService}), any left/right click on a block is intercepted,
 * cancelled, and the block's history is opened as a native Dialog-window causal tree browser (Paper
 * 26.2 Dialog API) via {@link DialogBrowser} — cascading windows with hover tooltips and back/loop
 * navigation on a vanilla client.
 *
 * <p>Register in {@code BlockLogsPlugin#registerListeners()} with:
 * <pre>{@code pm.registerEvents(new InspectorClickListener(services, this), this);}</pre>
 */
public final class InspectorClickListener implements Listener {

    private final BlockLogsServices services;
    private final Plugin plugin;

    public InspectorClickListener(BlockLogsServices services, Plugin plugin) {
        this.services = services;
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!services.inspectorService().isInspecting(player.getUniqueId())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Consume the click: no breaking / no GUI opening while inspecting.
        event.setCancelled(true);

        WorldPos center = LogSupport.pos(block);
        QueryParams params = QueryParams.builder()
                .world(center.world())
                .center(center)
                .radius(1)
                .excludeRolledBack(false)
                .limit(200)
                .build();

        // Open the native Dialog-window browser rooted at the clicked block. DialogBrowser runs the DB
        // read off-thread and shows the dialog back on the main thread itself.
        new DialogBrowser(services, plugin).openRoots(player, params);
    }
}
