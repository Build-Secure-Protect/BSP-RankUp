package com.mrgregles.bsp_rankup.storage;

import com.mrgregles.bsp_rankup.BSPRankUp;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * MySQL through plain JDBC, as BSP Region Guard does it (owner decision D21). No driver is bundled: BSP Core's full
 * jar provides one on Forge servers and Mohist ships its own. One connection, reopened when it fails. Tables:
 * {@code <prefix>counts} (uuid, signal, count), {@code <prefix>players} (uuid, bonus, rank, ranked_at, pending),
 * {@code <prefix>settings} (one row), {@code <prefix>notices}. The shared storage's changes arrive as one
 * {@link #write} every few seconds: one transaction, one JDBC batch per kind of statement.
 *
 * <p>The driver is created directly, as BSP Core's {@code MySqlStore} does: {@link DriverManager} refuses drivers
 * loaded by a mod class loader, so it never finds the one inside BSP Core's jar. BSP Core relocates that driver
 * under its own package, so its class name is tried first, then the plain one (Mohist's).
 */
public final class JdbcBackend implements StorageBackend {
    /** Driver classes, tried in order: BSP Core's full jar (Forge servers), then the plain driver (Mohist). */
    public static final List<String> DRIVERS = List.of("com.mrgregles.bsp_core.shaded.mysql.cj.jdbc.Driver", "com.mysql.cj.jdbc.Driver");
    @Nullable
    private static Driver driver;
    /** The URL scheme the driver takes: MySQL's, or MariaDB's when BSP-Core's slim jar found only that. */
    private static String scheme = "jdbc:mysql://";
    private static boolean driverLooked;

    /** After a failed connect, writes fail at once for this long instead of each waiting out the connect timeout. */
    static final long RETRY_MS = 10_000L;
    /** A connection used within this long is trusted without a ping first. */
    private static final long TRUST_MS = 30_000L;

    private final String url, user, password, prefix;
    private final String addCountSql, addBonusSql, setRankSql, setPendingSql, setSeasonSql, setRewardedSql, setLaddersSql, markResetSql;
    private Connection connection;
    private long lastUsedMs, failedAtMs;

    public JdbcBackend(String host, int port, String database, String user, String password, String prefix) {
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&rewriteBatchedStatements=true&connectTimeout=5000&socketTimeout=15000";
        this.user = user;
        this.password = password;
        this.prefix = prefix;
        this.addCountSql = "INSERT INTO " + prefix + "counts (uuid, signal_key, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = GREATEST(0, count + VALUES(count))";
        this.addBonusSql = "INSERT INTO " + prefix + "players (uuid, bonus, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE bonus = bonus + VALUES(bonus), updated_at = VALUES(updated_at)";
        this.setRankSql = "INSERT INTO " + prefix + "players (uuid, rank_group, ranked_at, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE rank_group = VALUES(rank_group), ranked_at = VALUES(ranked_at), updated_at = VALUES(updated_at)";
        this.setPendingSql = "INSERT INTO " + prefix + "players (uuid, pending, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE pending = VALUES(pending), updated_at = VALUES(updated_at)";
        this.markResetSql = "INSERT INTO " + prefix + "players (uuid, reset_season, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE reset_season = GREATEST(reset_season, VALUES(reset_season)), updated_at = VALUES(updated_at)";
        this.setSeasonSql = "INSERT INTO " + prefix + "players (uuid, season, season_base, score_peak, updated_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE season = VALUES(season), season_base = VALUES(season_base), score_peak = VALUES(score_peak), updated_at = VALUES(updated_at)";
        this.setRewardedSql = "INSERT INTO " + prefix + "players (uuid, rewarded, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE rewarded = VALUES(rewarded), updated_at = VALUES(updated_at)";
        this.setLaddersSql = "INSERT INTO " + prefix + "players (uuid, ladder_ranks, season_bases, awards, updated_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ladder_ranks = VALUES(ladder_ranks), season_bases = VALUES(season_bases), awards = VALUES(awards), updated_at = VALUES(updated_at)";
    }

    /**
     * The open connection, reopened when it was closed or, after a quiet spell, fails a ping. While the database is
     * unreachable every write fails at once for {@link #RETRY_MS} after a failed connect, so the worker's queue does
     * not back up behind one connect timeout per write.
     */
    private Connection conn() throws SQLException {
        long now = System.currentTimeMillis();
        if (connection != null && !connection.isClosed() && (now - lastUsedMs < TRUST_MS || connection.isValid(2))) {
            lastUsedMs = now;
            return connection;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // reopening anyway
            }
            connection = null;
        }
        if (now - failedAtMs < RETRY_MS) {
            throw new SQLException("MySQL unreachable; the next connect is tried within " + RETRY_MS / 1000 + " seconds");
        }
        try {
            connection = open();
        } catch (SQLException e) {
            failedAtMs = now;
            throw e;
        }
        failedAtMs = 0;
        lastUsedMs = now;
        return connection;
    }

    private Connection open() throws SQLException {
        Driver d = driver();
        if (d == null) {
            // neither class is visible: whatever driver the server registered itself
            return DriverManager.getConnection(url, user, password);
        }
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection c = d.connect(scheme.equals("jdbc:mysql://") ? url : scheme + url.substring("jdbc:mysql://".length()), props);
        if (c == null) {
            throw new SQLException("The MySQL driver " + d.getClass().getName() + " does not accept " + url);
        }
        return c;
    }

    /** The driver BSP-Core uses (1.4.5 and newer), else the first of {@link #DRIVERS} that loads; created once; null when none does. */
    @Nullable
    public static synchronized Driver driver() {
        if (!driverLooked) {
            driverLooked = true;
            java.util.Optional<com.mrgregles.bsp_rankup.compat.BspCore.JdbcDriver> core = com.mrgregles.bsp_rankup.compat.BspCore.jdbcDriver();
            if (core.isPresent()) {
                driver = core.get().driver();
                scheme = core.get().urlPrefix();
                BSPRankUp.LOGGER.info("MySQL driver: {} (from BSP-Core)", driver.getClass().getName());
                return driver;
            }
            for (String name : DRIVERS) {
                try {
                    driver = (Driver) Class.forName(name).getDeclaredConstructor().newInstance();
                    BSPRankUp.LOGGER.info("MySQL driver: {}", name);
                    break;
                } catch (ReflectiveOperationException | LinkageError | ClassCastException notInstalled) {
                    // not on this server: try the next
                }
            }
            if (driver == null) {
                BSPRankUp.LOGGER.warn("No MySQL driver found among {}; falling back to DriverManager", DRIVERS);
            }
        }
        return driver;
    }

    @Override
    public void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "counts (uuid CHAR(36) NOT NULL, signal_key VARCHAR(32) NOT NULL, count BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, signal_key))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "players (uuid CHAR(36) NOT NULL PRIMARY KEY, bonus BIGINT NOT NULL DEFAULT 0, rank_group VARCHAR(64) NULL, ranked_at BIGINT NOT NULL DEFAULT 0, pending MEDIUMTEXT NULL, season INT NOT NULL DEFAULT 0, season_base BIGINT NOT NULL DEFAULT 0, score_peak BIGINT NOT NULL DEFAULT 0, rewarded TEXT NULL, ladder_ranks TEXT NULL, season_bases TEXT NULL, awards TEXT NULL, updated_at BIGINT NOT NULL DEFAULT 0, reset_season INT NOT NULL DEFAULT 0)");
            // tables made before the score signal (0.2.2 and older) get its column
            boolean hasPeak;
            try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "players LIKE 'score_peak'")) {
                hasPeak = rs.next();
            }
            if (!hasPeak) {
                st.executeUpdate("ALTER TABLE " + prefix + "players ADD COLUMN score_peak BIGINT NOT NULL DEFAULT 0 AFTER season_base");
            }
            // and before several ladders (0.2.2 and older) the ladders' columns
            for (String column : new String[]{"ladder_ranks", "season_bases", "awards"}) {
                boolean has;
                try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "players LIKE '" + column + "'")) {
                    has = rs.next();
                }
                if (!has) {
                    st.executeUpdate("ALTER TABLE " + prefix + "players ADD COLUMN " + column + " TEXT NULL AFTER rewarded");
                }
            }
            // and before the season reset (0.6.1 and older) its marker
            boolean hasReset;
            try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "players LIKE 'reset_season'")) {
                hasReset = rs.next();
            }
            if (!hasReset) {
                st.executeUpdate("ALTER TABLE " + prefix + "players ADD COLUMN reset_season INT NOT NULL DEFAULT 0");
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "settings (id INT NOT NULL PRIMARY KEY, data MEDIUMTEXT NOT NULL, version BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "images (id CHAR(40) NOT NULL PRIMARY KEY, png MEDIUMBLOB NOT NULL, created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "notices (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uid VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL, source VARCHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL, payload TEXT)");
        }
    }

    @Override
    public void addCount(UUID player, String signal, long delta) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(addCountSql)) {
            ps.setString(1, player.toString());
            ps.setString(2, signal);
            ps.setLong(3, delta);
            ps.executeUpdate();
        }
    }

    @Override
    public void addBonus(UUID player, long delta) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(addBonusSql)) {
            ps.setString(1, player.toString());
            ps.setLong(2, delta);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setRank(UUID player, @Nullable String rank, long rankedAt) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(setRankSql)) {
            ps.setString(1, player.toString());
            ps.setString(2, rank);
            ps.setLong(3, rankedAt);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setPending(UUID player, @Nullable String pendingJson) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(setPendingSql)) {
            ps.setString(1, player.toString());
            ps.setString(2, pendingJson);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setSeason(UUID player, int season, long seasonBase, long scorePeak) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(setSeasonSql)) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setLong(3, seasonBase);
            ps.setLong(4, scorePeak);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setRewarded(UUID player, @Nullable String rewarded) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(setRewardedSql)) {
            ps.setString(1, player.toString());
            ps.setString(2, rewarded);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Every player's changes in one transaction, each kind of statement sent as one JDBC batch: deletes first, then
     * count and bonus deltas, then the values set outright. A failure rolls everything back, so the caller can keep
     * the whole lot for its next try.
     */
    @Override
    public List<UUID> write(List<PlayerWrite> writes) throws SQLException {
        Connection c = conn();
        c.setAutoCommit(false);
        try {
            List<UUID> written = writeIn(c, writes);
            c.commit();
            return written;
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the connection is gone: nothing was committed
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // reopened on the next use
            }
        }
    }

    /** Players per lookup of the rows' reset markers. */
    private static final int MARKER_CHUNK = 1000;

    /**
     * Every statement of a batch write, on a connection whose transaction the caller holds: the rows of the batch's
     * players are locked and their reset markers read first, the writes {@link StorageBackend#admits} refuses are
     * left out, and the rows written afresh get the writer's marker. Returns whose were written.
     */
    private List<UUID> writeIn(Connection c, List<PlayerWrite> all) throws SQLException {
        if (all.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> markers = new HashMap<>();
        for (int from = 0; from < all.size(); from += MARKER_CHUNK) {
            List<PlayerWrite> part = all.subList(from, Math.min(all.size(), from + MARKER_CHUNK));
            String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + prefix + "counts WHERE uuid IN (" + marks + ") GROUP BY uuid")) {
                for (int i = 0; i < part.size(); i++) {
                    ps.setString(i + 1, part.get(i).player().toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        markers.put(rs.getString(1), 0); // a row of counts only: from before any marker
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, reset_season FROM " + prefix + "players WHERE uuid IN (" + marks + ") FOR UPDATE")) {
                for (int i = 0; i < part.size(); i++) {
                    ps.setString(i + 1, part.get(i).player().toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        markers.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        }
        List<PlayerWrite> writes = new ArrayList<>();
        List<PlayerWrite> marking = new ArrayList<>();
        for (PlayerWrite w : all) {
            int rowReset = markers.getOrDefault(w.player().toString(), -1);
            if (StorageBackend.admits(rowReset, w)) {
                writes.add(w);
                if (StorageBackend.marks(rowReset, w)) {
                    marking.add(w);
                }
            }
        }
        {
            long now = System.currentTimeMillis();
            batch(c, "DELETE FROM " + prefix + "counts WHERE uuid = ?", writes, (ps, w) -> {
                if (w.delete()) {
                    ps.setString(1, w.player().toString());
                    ps.addBatch();
                }
            });
            batch(c, "DELETE FROM " + prefix + "players WHERE uuid = ?", writes, (ps, w) -> {
                if (w.delete()) {
                    ps.setString(1, w.player().toString());
                    ps.addBatch();
                }
            });
            batch(c, addCountSql, writes, (ps, w) -> {
                for (Map.Entry<String, Long> e : w.counts().entrySet()) {
                    ps.setString(1, w.player().toString());
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
            });
            batch(c, addBonusSql, writes, (ps, w) -> {
                if (w.bonus() != 0) {
                    ps.setString(1, w.player().toString());
                    ps.setLong(2, w.bonus());
                    ps.setLong(3, now);
                    ps.addBatch();
                }
            });
            batch(c, setRankSql, writes, (ps, w) -> {
                if (w.rank() != null) {
                    ps.setString(1, w.player().toString());
                    ps.setString(2, w.rank().rank());
                    ps.setLong(3, w.rank().rankedAt());
                    ps.setLong(4, now);
                    ps.addBatch();
                }
            });
            batch(c, setPendingSql, writes, (ps, w) -> {
                if (w.pending() != null) {
                    ps.setString(1, w.player().toString());
                    ps.setString(2, w.pending().text());
                    ps.setLong(3, now);
                    ps.addBatch();
                }
            });
            batch(c, setSeasonSql, writes, (ps, w) -> {
                if (w.season() != null) {
                    ps.setString(1, w.player().toString());
                    ps.setInt(2, w.season().season());
                    ps.setLong(3, w.season().seasonBase());
                    ps.setLong(4, w.season().scorePeak());
                    ps.setLong(5, now);
                    ps.addBatch();
                }
            });
            batch(c, setRewardedSql, writes, (ps, w) -> {
                if (w.rewarded() != null) {
                    ps.setString(1, w.player().toString());
                    ps.setString(2, w.rewarded().text());
                    ps.setLong(3, now);
                    ps.addBatch();
                }
            });
            batch(c, setLaddersSql, writes, (ps, w) -> {
                if (w.ladders() != null) {
                    ps.setString(1, w.player().toString());
                    ps.setString(2, w.ladders().ranks());
                    ps.setString(3, w.ladders().bases());
                    ps.setString(4, w.ladders().awards());
                    ps.setLong(5, now);
                    ps.addBatch();
                }
            });
            batch(c, markResetSql, marking, (ps, w) -> {
                ps.setString(1, w.player().toString());
                ps.setInt(2, w.resetSeason());
                ps.setLong(3, now);
                ps.addBatch();
            });
        }
        return writes.stream().map(PlayerWrite::player).toList();
    }

    @Override
    public void markReset(UUID player, int season) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(markResetSql)) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Adds rows to a batch of one statement. */
    private interface Rows {
        void add(PreparedStatement ps, PlayerWrite w) throws SQLException;
    }

    /**
     * One statement with its rows from every write, sent as one batch. The driver prepares on the client (the
     * default), so a statement no write has rows for costs no round trip.
     */
    private static void batch(Connection c, String sql, List<PlayerWrite> writes, Rows rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (PlayerWrite w : writes) {
                rows.add(ps, w);
            }
            ps.executeBatch();
        }
    }

    @Override
    public void setLadders(UUID player, LadderValue value) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(setLaddersSql)) {
            ps.setString(1, player.toString());
            ps.setString(2, value.ranks());
            ps.setString(3, value.bases());
            ps.setString(4, value.awards());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void deletePlayer(UUID player) throws SQLException {
        for (String table : new String[]{"counts", "players"}) {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + table + " WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
        }
    }

    @Override
    @Nullable
    public PlayerRow loadPlayer(UUID player) throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT signal_key, count FROM " + prefix + "counts WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        try (PreparedStatement ps = conn().prepareStatement("SELECT bonus, rank_group, ranked_at, pending, season, season_base, score_peak, rewarded, ladder_ranks, season_bases, awards, reset_season FROM " + prefix + "players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerRow(player, counts, rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                            rs.getString(9) == null && rs.getString(10) == null && rs.getString(11) == null ? null
                                    : new LadderValue(nz(rs.getString(9)), nz(rs.getString(10)), nz(rs.getString(11))), rs.getInt(12));
                }
            }
        }
        return counts.isEmpty() ? null : new PlayerRow(player, counts, 0, null, 0, null, 0, 0, 0, null, null);
    }

    @Override
    public Map<UUID, PlayerRow> loadAllPlayers() throws SQLException {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, signal_key, count FROM " + prefix + "counts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = uuidOrNull(rs.getString(1));
                if (id != null) {
                    counts.computeIfAbsent(id, k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }
            }
        }
        Map<UUID, PlayerRow> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, bonus, rank_group, ranked_at, pending, season, season_base, score_peak, rewarded, ladder_ranks, season_bases, awards, reset_season FROM " + prefix + "players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = uuidOrNull(rs.getString(1));
                if (id == null) {
                    continue;
                }
                out.put(id, new PlayerRow(id, counts.getOrDefault(id, Map.of()), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getLong(8),
                        rs.getString(9), rs.getString(10) == null && rs.getString(11) == null && rs.getString(12) == null ? null
                        : new LadderValue(nz(rs.getString(10)), nz(rs.getString(11)), nz(rs.getString(12))), rs.getInt(13)));
            }
        }
        counts.forEach((id, c) -> out.putIfAbsent(id, new PlayerRow(id, c, 0, null, 0, null, 0, 0, 0, null, null)));
        return out;
    }

    @Nullable
    private static UUID uuidOrNull(@Nullable String s) {
        try {
            return s == null ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null; // a hand-made row
        }
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    @Override
    public void saveSettings(String json, long version) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "settings (id, data, version) VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data), version = VALUES(version)")) {
            ps.setString(1, json);
            ps.setLong(2, version);
            ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public SettingsRow loadSettings() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT data, version FROM " + prefix + "settings WHERE id = 1")) {
            return rs.next() ? new SettingsRow(rs.getString(1), rs.getLong(2)) : null;
        }
    }

    @Override
    public void saveImage(String id, byte[] png) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT IGNORE INTO " + prefix + "images (id, png, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setBytes(2, png);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public byte[] loadImage(String id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT png FROM " + prefix + "images WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    @Override
    public long publish(Notice n) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "notices (uid, created_at, source, kind, payload) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, n.uid());
            ps.setLong(2, n.createdAt());
            ps.setString(3, n.source());
            ps.setString(4, n.kind());
            ps.setString(5, n.payload());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    @Override
    public List<Notice> fetchNoticesAfter(long id, int limit) throws SQLException {
        List<Notice> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, uid, created_at, source, kind, payload FROM " + prefix + "notices WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, id);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Notice(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    @Override
    public long latestNoticeId() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + prefix + "notices")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public int deleteNoticesBefore(long createdAtMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "notices WHERE created_at < ?")) {
            ps.setLong(1, createdAtMs);
            return ps.executeUpdate();
        }
    }

    @Override
    public String describe() {
        return "mysql " + url.substring(0, url.indexOf('?')) + " as " + user + ", prefix " + prefix;
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
