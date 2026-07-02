package com.blocklogs.core.query.session;

import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.storage.StorageException;

import java.util.List;
import java.util.UUID;

/**
 * Creates, persists and reopens {@link QuerySession}s. Backed by the {@link com.blocklogs.core.storage.Database}
 * for persistence and a small in-memory cache of active sessions per player.
 */
public interface QuerySessionManager {

    /** Start a new session for {@code owner} from the given params and persist it. */
    QuerySession create(UUID owner, QueryParams params) throws StorageException;

    /** The player's most recently active session, or {@code null}. */
    QuerySession active(UUID player);

    /** Reopen a session by id (may belong to another player — sessions are shareable). */
    QuerySession open(int sessionId) throws StorageException;

    /** Persist the current navigation state of a session. */
    void save(QuerySession session) throws StorageException;

    /** List sessions owned by a player, newest first. */
    List<QuerySession> history(UUID owner) throws StorageException;
}
