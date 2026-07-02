package com.blocklogs.core.storage.sqlite;

import com.blocklogs.api.action.ActionCategory;
import com.blocklogs.api.action.ActionType;
import com.blocklogs.api.actor.Actor;
import com.blocklogs.api.actor.ActorType;
import com.blocklogs.api.log.BlockLogEntry;
import com.blocklogs.api.log.ContainerLogEntry;
import com.blocklogs.api.log.EntityLogEntry;
import com.blocklogs.api.log.LogEntry;
import com.blocklogs.api.model.WorldPos;
import com.blocklogs.core.query.LookupResult;
import com.blocklogs.core.query.QueryParams;
import com.blocklogs.core.query.session.QuerySession;
import com.blocklogs.core.storage.Database;
import com.blocklogs.core.storage.StorageException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed {@link Database} implemented directly on {@code java.sql} (JDBC).
 *
 * <p>Backed by the {@code org.xerial} sqlite-jdbc driver, which auto-registers {@code org.sqlite.JDBC}
 * with the {@link DriverManager}. A single {@link Connection} is opened against
 * {@code <dataFolder>/blocklogs.db} in {@link #initialize()}, WAL journalling is enabled, and the schema
 * is created if absent.
 *
 * <h2>Threading</h2>
 * As documented on {@link Database}, all methods are invoked off the main server thread by the async
 * logging/query pipeline. SQLite in WAL mode tolerates a single writer plus concurrent readers; JDBC
 * connections are not themselves thread-safe, so every method that touches SQL is {@code synchronized}
 * on this instance. That is sufficient for this plugin's single-writer + occasional-reader access
 * pattern and keeps the implementation simple and correct.
 *
 * <h2>Schema</h2>
 * A single wide table {@code bl_entry} stores every {@link LogEntry} subtype, discriminated by a
 * {@code kind} column (0=block, 1=container, 2=entity). The plugin-assigned {@link LogEntry#id()} is the
 * PRIMARY KEY and is stored verbatim (no AUTOINCREMENT). Query sessions live in {@code bl_session}.
 */
public final class SqliteDatabase implements Database {

    /** Discriminator values for the {@code kind} column. */
    private static final int KIND_BLOCK = 0;
    private static final int KIND_CONTAINER = 1;
    private static final int KIND_ENTITY = 2;

    private final Path dataFolder;
    private final Logger logger;

    private Connection connection;

    public SqliteDatabase(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public synchronized void initialize() throws StorageException {
        Path dbPath = dataFolder.resolve("blocklogs.db");
        String url = "jdbc:sqlite:" + dbPath.toString();
        try {
            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            createSchema();
            logger.info("[BlockLogs] SQLite storage ready at " + dbPath);
        } catch (SQLException e) {
            throw new StorageException("Failed to initialize SQLite database at " + dbPath, e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bl_entry (
                        id              INTEGER PRIMARY KEY,
                        time            INTEGER NOT NULL,
                        actor_type      INTEGER NOT NULL,
                        actor_uuid      TEXT,
                        actor_name      TEXT NOT NULL,
                        action          INTEGER NOT NULL,
                        world           TEXT NOT NULL,
                        x               INTEGER NOT NULL,
                        y               INTEGER NOT NULL,
                        z               INTEGER NOT NULL,
                        cause_id        INTEGER,
                        rolled_back     INTEGER NOT NULL DEFAULT 0,
                        kind            INTEGER NOT NULL,
                        material        TEXT,
                        data_before     TEXT,
                        data_after      TEXT,
                        item            TEXT,
                        amount          INTEGER,
                        serialized_item TEXT,
                        entity_type     TEXT,
                        entity_uuid     TEXT,
                        detail          TEXT
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_entry_loc ON bl_entry(world, x, z, y)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_entry_time ON bl_entry(time)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_entry_actor ON bl_entry(actor_name)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_entry_cause ON bl_entry(cause_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_entry_action ON bl_entry(action)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS bl_session (
                        id       INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner    TEXT,
                        created  INTEGER NOT NULL,
                        params   TEXT NOT NULL,
                        nav      TEXT NOT NULL,
                        expanded TEXT NOT NULL,
                        page     INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_bl_session_owner ON bl_session(owner)");
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warning("[BlockLogs] Error closing SQLite connection: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ------------------------------------------------------------------ ids / insert

    @Override
    public synchronized long currentMaxId() throws StorageException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM bl_entry")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new StorageException("Failed to read current max id", e);
        }
    }

    @Override
    public synchronized void insert(Collection<LogEntry> batch) throws StorageException {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        String sql = """
                INSERT OR REPLACE INTO bl_entry
                    (id, time, actor_type, actor_uuid, actor_name, action, world, x, y, z,
                     cause_id, rolled_back, kind, material, data_before, data_after,
                     item, amount, serialized_item, entity_type, entity_uuid, detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        boolean priorAutoCommit = true;
        try {
            priorAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (LogEntry e : batch) {
                    bindInsert(ps, e);
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(priorAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to insert batch of " + batch.size() + " entries", e);
        }
    }

    private void bindInsert(PreparedStatement ps, LogEntry e) throws SQLException {
        Actor actor = e.actor();
        WorldPos pos = e.pos();

        ps.setLong(1, e.id());
        ps.setLong(2, e.time().toEpochMilli());
        ps.setInt(3, actor.type().ordinal());
        setNullableString(ps, 4, actor.uuid() == null ? null : actor.uuid().toString());
        ps.setString(5, actor.name());
        ps.setInt(6, e.action().id());
        ps.setString(7, pos.world());
        ps.setInt(8, pos.x());
        ps.setInt(9, pos.y());
        ps.setInt(10, pos.z());
        if (e.causeId() == null) {
            ps.setNull(11, Types.INTEGER);
        } else {
            ps.setLong(11, e.causeId());
        }
        ps.setInt(12, e.rolledBack() ? 1 : 0);

        // subtype-specific columns; default all to NULL then fill the relevant ones.
        setNullableString(ps, 14, null); // material
        setNullableString(ps, 15, null); // data_before
        setNullableString(ps, 16, null); // data_after
        setNullableString(ps, 17, null); // item
        ps.setNull(18, Types.INTEGER);    // amount
        setNullableString(ps, 19, null); // serialized_item
        setNullableString(ps, 20, null); // entity_type
        setNullableString(ps, 21, null); // entity_uuid
        setNullableString(ps, 22, null); // detail

        switch (e) {
            case BlockLogEntry b -> {
                ps.setInt(13, KIND_BLOCK);
                setNullableString(ps, 14, b.material());
                setNullableString(ps, 15, b.dataBefore());
                setNullableString(ps, 16, b.dataAfter());
            }
            case ContainerLogEntry c -> {
                ps.setInt(13, KIND_CONTAINER);
                setNullableString(ps, 14, c.containerType());
                setNullableString(ps, 17, c.item());
                ps.setInt(18, c.amount());
                setNullableString(ps, 19, c.serializedItem());
            }
            case EntityLogEntry en -> {
                ps.setInt(13, KIND_ENTITY);
                setNullableString(ps, 20, en.entityType());
                setNullableString(ps, 21, en.entityUuid() == null ? null : en.entityUuid().toString());
                setNullableString(ps, 22, en.data());
            }
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    // ------------------------------------------------------------------ reads

    private static final String SELECT_COLS = """
            SELECT id, time, actor_type, actor_uuid, actor_name, action, world, x, y, z,
                   cause_id, rolled_back, kind, material, data_before, data_after,
                   item, amount, serialized_item, entity_type, entity_uuid, detail
            FROM bl_entry""";

    @Override
    public synchronized LookupResult query(QueryParams params) throws StorageException {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(params, args, false);

        long total;
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM bl_entry" + where)) {
            bindArgs(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to count query results", e);
        }

        int limit = params.limit();
        int offset = params.offset();
        String sql = SELECT_COLS + where + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?";
        List<LogEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = bindArgs(ps, args);
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(readRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to execute lookup query", e);
        }

        int page = limit > 0 ? offset / limit : 0;
        return new LookupResult(entries, page, limit, total);
    }

    @Override
    public synchronized List<LogEntry> roots(QueryParams params) throws StorageException {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(params, args, true);
        String sql = SELECT_COLS + where + " ORDER BY time DESC, id DESC LIMIT ? OFFSET ?";
        List<LogEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = bindArgs(ps, args);
            ps.setInt(i++, params.limit());
            ps.setInt(i, params.offset());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to fetch roots", e);
        }
        return out;
    }

    @Override
    public synchronized List<LogEntry> children(long parentId) throws StorageException {
        String sql = SELECT_COLS + " WHERE cause_id = ? ORDER BY time ASC, id ASC";
        List<LogEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to fetch children of " + parentId, e);
        }
        return out;
    }

    @Override
    public synchronized int childCount(long parentId) throws StorageException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM bl_entry WHERE cause_id = ?")) {
            ps.setLong(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to count children of " + parentId, e);
        }
    }

    @Override
    public synchronized LogEntry byId(long id) throws StorageException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_COLS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to fetch entry " + id, e);
        }
    }

    // ------------------------------------------------------------------ mutations

    @Override
    public synchronized void markRolledBack(Collection<Long> ids, boolean rolledBack) throws StorageException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        StringJoiner placeholders = new StringJoiner(",", "(", ")");
        for (int i = 0; i < ids.size(); i++) {
            placeholders.add("?");
        }
        String sql = "UPDATE bl_entry SET rolled_back = ? WHERE id IN " + placeholders;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, rolledBack ? 1 : 0);
            int i = 2;
            for (Long id : ids) {
                ps.setLong(i++, id);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to update rolled_back flag", e);
        }
    }

    @Override
    public synchronized int purge(Instant olderThan) throws StorageException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM bl_entry WHERE time < ?")) {
            ps.setLong(1, olderThan.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to purge entries older than " + olderThan, e);
        }
    }

    // ------------------------------------------------------------------ sessions

    @Override
    public synchronized int saveSession(QuerySession session) throws StorageException {
        String paramsBlob = SessionCodec.encodeParams(session.params());
        String nav = SessionCodec.encodeLongs(session.navStackSnapshot());
        String expanded = SessionCodec.encodeLongs(session.expandedSnapshot());
        String owner = session.owner() == null ? null : session.owner().toString();

        try {
            if (session.id() == 0) {
                String sql = "INSERT INTO bl_session (owner, created, params, nav, expanded, page) "
                        + "VALUES (?,?,?,?,?,?)";
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    setNullableString(ps, 1, owner);
                    ps.setLong(2, session.createdAt().toEpochMilli());
                    ps.setString(3, paramsBlob);
                    ps.setString(4, nav);
                    ps.setString(5, expanded);
                    ps.setInt(6, session.page());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        int newId = keys.next() ? keys.getInt(1) : 0;
                        session.id(newId);
                        return newId;
                    }
                }
            } else {
                String sql = "UPDATE bl_session SET owner=?, created=?, params=?, nav=?, expanded=?, page=? "
                        + "WHERE id=?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    setNullableString(ps, 1, owner);
                    ps.setLong(2, session.createdAt().toEpochMilli());
                    ps.setString(3, paramsBlob);
                    ps.setString(4, nav);
                    ps.setString(5, expanded);
                    ps.setInt(6, session.page());
                    ps.setInt(7, session.id());
                    ps.executeUpdate();
                    return session.id();
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to save query session", e);
        }
    }

    @Override
    public synchronized QuerySession loadSession(int sessionId) throws StorageException {
        String sql = "SELECT id, owner, created, params, nav, expanded, page FROM bl_session WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSession(rs) : null;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load session " + sessionId, e);
        }
    }

    @Override
    public synchronized List<QuerySession> listSessions(UUID owner) throws StorageException {
        String sql = "SELECT id, owner, created, params, nav, expanded, page FROM bl_session "
                + "WHERE owner = ? ORDER BY created DESC";
        List<QuerySession> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            setNullableString(ps, 1, owner == null ? null : owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to list sessions for owner " + owner, e);
        }
        return out;
    }

    private QuerySession readSession(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String ownerStr = rs.getString("owner");
        UUID owner = ownerStr == null ? null : UUID.fromString(ownerStr);
        Instant created = Instant.ofEpochMilli(rs.getLong("created"));
        QueryParams params = SessionCodec.decodeParams(rs.getString("params"));
        List<Long> nav = SessionCodec.decodeLongs(rs.getString("nav"));
        Set<Long> expanded = new java.util.HashSet<>(SessionCodec.decodeLongs(rs.getString("expanded")));
        int page = rs.getInt("page");
        return new QuerySession(id, owner, created, params, nav, expanded, page);
    }

    // ------------------------------------------------------------------ WHERE builder

    /**
     * Builds a {@code WHERE ...} clause (including the leading {@code " WHERE "} or empty string) from
     * the given params, appending bind values to {@code args} in order.
     *
     * @param rootsOnly when true, adds {@code cause_id IS NULL} for {@link #roots(QueryParams)}.
     */
    private String buildWhere(QueryParams params, List<Object> args, boolean rootsOnly) {
        List<String> clauses = new ArrayList<>();

        if (params.actorUuids() != null && !params.actorUuids().isEmpty()) {
            clauses.add("actor_uuid IN " + placeholders(params.actorUuids().size()));
            for (UUID u : params.actorUuids()) {
                args.add(u.toString());
            }
        }
        if (params.actorNames() != null && !params.actorNames().isEmpty()) {
            clauses.add("actor_name IN " + placeholders(params.actorNames().size()));
            args.addAll(params.actorNames());
        }

        // actions + categories -> a single set of action ids, OR-combined into one IN clause.
        Set<Integer> actionIds = new java.util.LinkedHashSet<>();
        if (params.actions() != null) {
            for (ActionType a : params.actions()) {
                actionIds.add(a.id());
            }
        }
        if (params.categories() != null && !params.categories().isEmpty()) {
            EnumSet<ActionCategory> cats = EnumSet.copyOf(params.categories());
            for (ActionType a : ActionType.values()) {
                if (cats.contains(a.category())) {
                    actionIds.add(a.id());
                }
            }
        }
        if (!actionIds.isEmpty()) {
            clauses.add("action IN " + placeholders(actionIds.size()));
            args.addAll(actionIds);
        }

        if (params.world() != null && !params.world().isEmpty()) {
            clauses.add("world = ?");
            args.add(params.world());
        }

        if (params.center() != null && params.radius() != null) {
            WorldPos c = params.center();
            int r = params.radius();
            // World from the center takes precedence for a radius search if none was set explicitly.
            if (params.world() == null || params.world().isEmpty()) {
                clauses.add("world = ?");
                args.add(c.world());
            }
            clauses.add("x BETWEEN ? AND ?");
            args.add(c.x() - r);
            args.add(c.x() + r);
            clauses.add("y BETWEEN ? AND ?");
            args.add(c.y() - r);
            args.add(c.y() + r);
            clauses.add("z BETWEEN ? AND ?");
            args.add(c.z() - r);
            args.add(c.z() + r);
        }

        if (params.since() != null) {
            clauses.add("time >= ?");
            args.add(params.since().toEpochMilli());
        }
        if (params.until() != null) {
            clauses.add("time <= ?");
            args.add(params.until().toEpochMilli());
        }

        if (params.materials() != null && !params.materials().isEmpty()) {
            // Materials apply to block material, container type, item and entity type columns.
            clauses.add("(material IN " + placeholders(params.materials().size())
                    + " OR item IN " + placeholders(params.materials().size())
                    + " OR entity_type IN " + placeholders(params.materials().size()) + ")");
            args.addAll(params.materials());
            args.addAll(params.materials());
            args.addAll(params.materials());
        }

        if (params.excludeRolledBack()) {
            clauses.add("rolled_back = 0");
        }

        if (rootsOnly) {
            clauses.add("cause_id IS NULL");
        }

        if (clauses.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    private static String placeholders(int n) {
        StringJoiner sj = new StringJoiner(",", "(", ")");
        for (int i = 0; i < n; i++) {
            sj.add("?");
        }
        return sj.toString();
    }

    /** Binds positional args starting at index 1; returns the next free index. */
    private static int bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        int i = 1;
        for (Object a : args) {
            switch (a) {
                case Integer v -> ps.setInt(i, v);
                case Long v -> ps.setLong(i, v);
                case String v -> ps.setString(i, v);
                case null -> ps.setNull(i, Types.NULL);
                default -> ps.setObject(i, a);
            }
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------ row -> LogEntry

    private LogEntry readRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Instant time = Instant.ofEpochMilli(rs.getLong("time"));

        int actorTypeOrd = rs.getInt("actor_type");
        ActorType actorType = ActorType.values()[actorTypeOrd];
        String actorUuidStr = rs.getString("actor_uuid");
        UUID actorUuid = actorUuidStr == null ? null : UUID.fromString(actorUuidStr);
        String actorName = rs.getString("actor_name");
        Actor actor = new Actor(actorType, actorUuid, actorName);

        ActionType action = ActionType.byId(rs.getInt("action"));
        WorldPos pos = new WorldPos(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));

        Object causeObj = rs.getObject("cause_id");
        Long causeId = causeObj == null ? null : ((Number) causeObj).longValue();
        boolean rolledBack = rs.getInt("rolled_back") != 0;

        int kind = rs.getInt("kind");
        return switch (kind) {
            case KIND_CONTAINER -> {
                String containerType = rs.getString("material");
                String item = rs.getString("item");
                int amount = rs.getInt("amount");
                String serializedItem = rs.getString("serialized_item");
                yield new ContainerLogEntry(id, time, actor, action, pos,
                        containerType, item, amount, serializedItem, causeId, rolledBack);
            }
            case KIND_ENTITY -> {
                String entityType = rs.getString("entity_type");
                String entityUuidStr = rs.getString("entity_uuid");
                UUID entityUuid = entityUuidStr == null ? null : UUID.fromString(entityUuidStr);
                String detail = rs.getString("detail");
                yield new EntityLogEntry(id, time, actor, action, pos,
                        entityType, entityUuid, detail, causeId, rolledBack);
            }
            default -> { // KIND_BLOCK
                String material = rs.getString("material");
                String dataBefore = rs.getString("data_before");
                String dataAfter = rs.getString("data_after");
                yield new BlockLogEntry(id, time, actor, action, pos,
                        material, dataBefore, dataAfter, causeId, rolledBack);
            }
        };
    }
}
