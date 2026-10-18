package com.mrgregles.bsp_rankup.storage;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MySQL through plain JDBC, as BSP Region Guard does it (owner decision D21). No driver is bundled: BSP Core's full
 * jar provides one on Forge servers and Mohist ships its own. One connection, reopened when it fails. Tables:
 * {@code <prefix>counts} (uuid, signal, count), {@code <prefix>players} (uuid, bonus, rank, ranked_at, pending),
 * {@code <prefix>settings} (one row), {@code <prefix>notices}.
 */
public final class JdbcBackend implements StorageBackend {
    private final String url, user, password, prefix;
    private Connection connection;

    public JdbcBackend(String host, int port, String database, String user, String password, String prefix) {
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";
        this.user = user;
        this.password = password;
        this.prefix = prefix;
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
            }
            connection = DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    @Override
    public void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "counts (uuid CHAR(36) NOT NULL, signal_key VARCHAR(32) NOT NULL, count BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, signal_key))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "players (uuid CHAR(36) NOT NULL PRIMARY KEY, bonus BIGINT NOT NULL DEFAULT 0, rank_group VARCHAR(64) NULL, ranked_at BIGINT NOT NULL DEFAULT 0, pending MEDIUMTEXT NULL, season INT NOT NULL DEFAULT 0, season_base BIGINT NOT NULL DEFAULT 0, rewarded TEXT NULL, updated_at BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "settings (id INT NOT NULL PRIMARY KEY, data MEDIUMTEXT NOT NULL, version BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "notices (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uid VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL, source VARCHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL, payload TEXT)");
        }
    }

    @Override
    public void addCount(UUID player, String signal, long delta) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "counts (uuid, signal_key, count) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE count = GREATEST(0, count + VALUES(count))")) {
            ps.setString(1, player.toString());
            ps.setString(2, signal);
            ps.setLong(3, delta);
            ps.executeUpdate();
        }
    }

    @Override
    public void addBonus(UUID player, long delta) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "players (uuid, bonus, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE bonus = bonus + VALUES(bonus), updated_at = VALUES(updated_at)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, delta);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setRank(UUID player, @Nullable String rank, long rankedAt) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "players (uuid, rank_group, ranked_at, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE rank_group = VALUES(rank_group), ranked_at = VALUES(ranked_at), updated_at = VALUES(updated_at)")) {
            ps.setString(1, player.toString());
            ps.setString(2, rank);
            ps.setLong(3, rankedAt);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setPending(UUID player, @Nullable String pendingJson) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "players (uuid, pending, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE pending = VALUES(pending), updated_at = VALUES(updated_at)")) {
            ps.setString(1, player.toString());
            ps.setString(2, pendingJson);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setSeason(UUID player, int season, long seasonBase) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "players (uuid, season, season_base, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE season = VALUES(season), season_base = VALUES(season_base), updated_at = VALUES(updated_at)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setLong(3, seasonBase);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void setRewarded(UUID player, @Nullable String rewarded) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "players (uuid, rewarded, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE rewarded = VALUES(rewarded), updated_at = VALUES(updated_at)")) {
            ps.setString(1, player.toString());
            ps.setString(2, rewarded);
            ps.setLong(3, System.currentTimeMillis());
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
        try (PreparedStatement ps = conn().prepareStatement("SELECT bonus, rank_group, ranked_at, pending, season, season_base, rewarded FROM " + prefix + "players WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerRow(player, counts, rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getString(7));
                }
            }
        }
        return counts.isEmpty() ? null : new PlayerRow(player, counts, 0, null, 0, null, 0, 0, null);
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
