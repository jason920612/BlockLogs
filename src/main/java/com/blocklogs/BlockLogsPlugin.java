package com.blocklogs;

import com.blocklogs.api.BlockLogsApi;
import com.blocklogs.api.BlockLogsApiImpl;
import com.blocklogs.command.BlCommand;
import com.blocklogs.config.BlockLogsConfig;
import com.blocklogs.core.causal.CausalTracker;
import com.blocklogs.core.causal.SimpleCausalTracker;
import com.blocklogs.core.inspect.DefaultInspectorService;
import com.blocklogs.core.inspect.InspectorService;
import com.blocklogs.core.logging.QueuedLoggingService;
import com.blocklogs.core.query.DefaultQueryEngine;
import com.blocklogs.core.query.QueryEngine;
import com.blocklogs.core.query.session.DefaultQuerySessionManager;
import com.blocklogs.core.query.session.QuerySessionManager;
import com.blocklogs.core.rollback.DefaultRollbackEngine;
import com.blocklogs.core.rollback.RollbackEngine;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;
import com.blocklogs.core.storage.sqlite.SqliteDatabase;
import com.blocklogs.listener.BlockChangeListener;
import com.blocklogs.listener.ContainerListener;
import com.blocklogs.listener.EntityListener;
import com.blocklogs.listener.IndirectEntityBlockListener;
import com.blocklogs.listener.IndirectExplosionListener;
import com.blocklogs.listener.IndirectPhysicsListener;
import com.blocklogs.listener.InspectorClickListener;
import com.blocklogs.listener.InteractionListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Plugin entry point. Owns lifecycle and assembly only — it constructs the service graph, registers the
 * public API, the {@code /bl} command and scheduled maintenance, then hands the {@link BlockLogsServices}
 * bundle to listeners and commands. Feature subagents plug their implementations in via that bundle and
 * register their listeners in {@link #registerListeners()}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BlockLogsPlugin extends JavaPlugin {

    private QueuedLoggingService logging;
    private Database database;
    private BlockLogsServices services;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BlockLogsConfig config = new BlockLogsConfig(getConfig());

        database = new SqliteDatabase(getDataFolder().toPath(), getLogger());
        try {
            database.initialize();
        } catch (StorageException e) {
            getLogger().severe("Failed to initialize storage; disabling. " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long seedMaxId;
        try {
            seedMaxId = database.currentMaxId();
        } catch (StorageException e) {
            getLogger().warning("Could not read max id; seeding from 0. " + e.getMessage());
            seedMaxId = 0L;
        }

        logging = new QueuedLoggingService(database, getLogger(), seedMaxId,
                config.writerBatchSize(), config.writerFlushIntervalMs());
        logging.start();

        CausalTracker causalTracker = new SimpleCausalTracker(config.causalTtlMillis());
        QueryEngine queryEngine = new DefaultQueryEngine(database);
        QuerySessionManager sessionManager = new DefaultQuerySessionManager(database);
        RollbackEngine rollbackEngine = new DefaultRollbackEngine(this, database, logging);
        InspectorService inspectorService = new DefaultInspectorService();

        services = new BlockLogsServices(config, database, logging, causalTracker,
                queryEngine, sessionManager, rollbackEngine, inspectorService);

        getServer().getServicesManager().register(
                BlockLogsApi.class, new BlockLogsApiImpl(services), this, ServicePriority.Normal);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("bl", "BlockLogs main command",
                        List.of("blocklog", "blocklogs"), new BlCommand(services, this)));

        registerListeners();

        getServer().getScheduler().runTaskTimer(this, causalTracker::expireStale, 100L, 100L);

        getLogger().info("BlockLogs v" + getPluginMeta().getVersion() + " enabled. Commands: "
                + "/bl find (query form), /bl gui (tree window), /bl lookup, /bl inspect, /bl rollback.");
    }

    @Override
    public void onDisable() {
        if (logging != null) {
            logging.stop();
        }
        if (database != null) {
            database.close();
        }
    }

    /**
     * Registers event listeners. Skeleton registers none — each listener subagent adds its registration
     * here (or via a dedicated registrar) using {@link #services}.
     */
    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new BlockChangeListener(services), this);
        pm.registerEvents(new ContainerListener(services, this), this);
        pm.registerEvents(new InteractionListener(services, this), this);
        pm.registerEvents(new EntityListener(services), this);
        pm.registerEvents(new IndirectExplosionListener(services), this);
        pm.registerEvents(new IndirectPhysicsListener(services), this);
        pm.registerEvents(new IndirectEntityBlockListener(services), this);
        pm.registerEvents(new InspectorClickListener(services, this), this);
    }

    /** Accessor for listeners/commands that need the wired services. */
    public BlockLogsServices services() {
        return services;
    }
}
