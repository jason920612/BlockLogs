package com.blocklogs.api;

import com.blocklogs.core.logging.LoggingService;
import com.blocklogs.core.query.QueryEngine;
import com.blocklogs.core.rollback.RollbackEngine;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Stable entry point for other plugins. Registered in the Bukkit {@code ServicesManager} on enable.
 *
 * <pre>{@code
 * BlockLogsApi api = BlockLogsApi.get();
 * if (api != null) {
 *     api.logging().record(actor, ActionType.BLOCK_PLACE, pos).block(...).commit();
 * }
 * }</pre>
 */
public interface BlockLogsApi {

    /** Record actions (also lets other plugins attribute their own block edits). */
    LoggingService logging();

    /** Run lookups / build causal trees. */
    QueryEngine query();

    /** Roll back or restore changes. */
    RollbackEngine rollback();

    /** Convenience accessor via the Bukkit ServicesManager. Returns {@code null} if BlockLogs is absent. */
    static @Nullable BlockLogsApi get() {
        RegisteredServiceProvider<BlockLogsApi> rsp =
                Bukkit.getServicesManager().getRegistration(BlockLogsApi.class);
        return rsp == null ? null : rsp.getProvider();
    }
}
