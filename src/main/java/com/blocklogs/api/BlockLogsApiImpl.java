package com.blocklogs.api;

import com.blocklogs.BlockLogsServices;
import com.blocklogs.core.logging.LoggingService;
import com.blocklogs.core.query.QueryEngine;
import com.blocklogs.core.rollback.RollbackEngine;

/** Default {@link BlockLogsApi} backed by the plugin's service registry. */
public final class BlockLogsApiImpl implements BlockLogsApi {

    private final BlockLogsServices services;

    public BlockLogsApiImpl(BlockLogsServices services) {
        this.services = services;
    }

    @Override
    public LoggingService logging() {
        return services.logging();
    }

    @Override
    public QueryEngine query() {
        return services.queryEngine();
    }

    @Override
    public RollbackEngine rollback() {
        return services.rollbackEngine();
    }
}
