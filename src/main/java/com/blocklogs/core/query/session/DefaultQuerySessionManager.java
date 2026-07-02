package com.blocklogs.core.query.session;

import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link QuerySessionManager}: keeps the active session per player in memory and delegates
 * persistence to the {@link Database}. Create/active/save are functional; cross-restart reopening works
 * once the storage subagent implements the session table.
 */
public final class DefaultQuerySessionManager implements QuerySessionManager {

    private final Database database;
    private final Map<UUID, QuerySession> active = new ConcurrentHashMap<>();

    public DefaultQuerySessionManager(Database database) {
        this.database = database;
    }

    @Override
    public QuerySession create(UUID owner, QueryParams params) throws StorageException {
        QuerySession session = new QuerySession(owner, Instant.now(), params);
        int id = database.saveSession(session);
        session.id(id);
        active.put(owner, session);
        return session;
    }

    @Override
    public QuerySession active(UUID player) {
        return active.get(player);
    }

    @Override
    public QuerySession open(int sessionId) throws StorageException {
        QuerySession session = database.loadSession(sessionId);
        if (session != null) {
            active.put(session.owner(), session);
        }
        return session;
    }

    @Override
    public void save(QuerySession session) throws StorageException {
        database.saveSession(session);
    }

    @Override
    public List<QuerySession> history(UUID owner) throws StorageException {
        return database.listSessions(owner);
    }
}
