package com.blocklogs.core.storage;

import com.blocklogs.api.log.LogEntry;
import com.blocklogs.core.query.LookupResult;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.query.session.QuerySession;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The persistence contract. The default (and initially only) implementation is SQLite.
 *
 * <p><b>Threading:</b> every method here is expected to be called OFF the main server thread. The
 * logging pipeline batches entries and calls {@link #insert(Collection)} from a background writer;
 * queries and rollbacks run in async tasks. Implementations must be safe for a single writer plus
 * concurrent readers (SQLite WAL mode is recommended).
 *
 * <p><b>IDs:</b> {@link LogEntry#id()} values are assigned by the logging service (see
 * {@code LoggingService}) using a monotonic counter seeded from {@link #currentMaxId()} at startup.
 * Implementations must persist the supplied id verbatim, NOT generate their own.
 */
public interface Database {

    /** Open connections, create/migrate schema, tune pragmas. Called once on enable. */
    void initialize() throws StorageException;

    /** Largest id currently stored, or {@code 0} if empty. Used to seed the id counter on startup. */
    long currentMaxId() throws StorageException;

    /** Persist a batch of pre-assigned entries in a single transaction. */
    void insert(Collection<LogEntry> batch) throws StorageException;

    /** Paged flat lookup matching the given params. */
    LookupResult query(QueryParams params) throws StorageException;

    /** Root entries (no {@code causeId}) matching the params — the tops of causal trees. */
    List<LogEntry> roots(QueryParams params) throws StorageException;

    /** Direct children of a given entry (entries whose {@code causeId == parentId}). */
    List<LogEntry> children(long parentId) throws StorageException;

    /** Number of direct children of an entry (for collapsed "×N" summaries). */
    int childCount(long parentId) throws StorageException;

    /** Fetch a single entry by id, or {@code null}. */
    LogEntry byId(long id) throws StorageException;

    /** Flip the rolled-back flag for the given entry ids. */
    void markRolledBack(Collection<Long> ids, boolean rolledBack) throws StorageException;

    /** Delete entries older than the cutoff; returns the number removed. */
    int purge(Instant olderThan) throws StorageException;

    // --- persisted query sessions (shareable, survive restarts) ---

    /** Insert or update a query session; returns its (possibly newly-assigned) id. */
    int saveSession(QuerySession session) throws StorageException;

    QuerySession loadSession(int sessionId) throws StorageException;

    List<QuerySession> listSessions(UUID owner) throws StorageException;

    /** Flush and close all resources. */
    void close();
}
