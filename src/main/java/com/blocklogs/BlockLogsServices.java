package com.blocklogs;

import com.blocklogs.config.BlockLogsConfig;
import com.blocklogs.core.causal.CausalTracker;
import com.blocklogs.core.inspect.InspectorService;
import com.blocklogs.core.logging.LoggingService;
import com.blocklogs.core.query.QueryEngine;
import com.blocklogs.core.query.session.QuerySessionManager;
import com.blocklogs.core.rollback.RollbackEngine;
import com.blocklogs.core.storage.Database;

/**
 * Tiny service locator wiring the plugin's collaborators together. Constructed once in
 * {@code BlockLogsPlugin#onEnable} and passed to listeners and commands. This is the single seam where
 * skeleton-provided shells are swapped for the real implementations built by the feature subagents.
 */
public final class BlockLogsServices {

    private volatile BlockLogsConfig config;
    private final Database database;
    private final LoggingService logging;
    private final CausalTracker causalTracker;
    private final QueryEngine queryEngine;
    private final QuerySessionManager sessionManager;
    private final RollbackEngine rollbackEngine;
    private final InspectorService inspectorService;

    public BlockLogsServices(BlockLogsConfig config,
                             Database database,
                             LoggingService logging,
                             CausalTracker causalTracker,
                             QueryEngine queryEngine,
                             QuerySessionManager sessionManager,
                             RollbackEngine rollbackEngine,
                             InspectorService inspectorService) {
        this.config = config;
        this.database = database;
        this.logging = logging;
        this.causalTracker = causalTracker;
        this.queryEngine = queryEngine;
        this.sessionManager = sessionManager;
        this.rollbackEngine = rollbackEngine;
        this.inspectorService = inspectorService;
    }

    public BlockLogsConfig config() { return config; }

    /** Swap in a freshly-loaded config (used by {@code /bl reload}). Listeners read {@link #config()} live. */
    public void setConfig(BlockLogsConfig config) { this.config = config; }
    public Database database() { return database; }
    public LoggingService logging() { return logging; }
    public CausalTracker causalTracker() { return causalTracker; }
    public QueryEngine queryEngine() { return queryEngine; }
    public QuerySessionManager sessionManager() { return sessionManager; }
    public RollbackEngine rollbackEngine() { return rollbackEngine; }
    public InspectorService inspectorService() { return inspectorService; }
}
