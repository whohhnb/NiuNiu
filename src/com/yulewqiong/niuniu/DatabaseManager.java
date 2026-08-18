package com.yulewqiong.niuniu;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final NiuNiu plugin;
    private HikariDataSource dataSource;
    private boolean enabled = false;

    private String tablePlayers;
    private String tableSeason;

    public DatabaseManager(NiuNiu plugin) {
        this.plugin = plugin;
    }

    public boolean connect(String host, int port, String database, String username, String password,
                           String tablePrefix, int poolMaxSize, int poolMinIdle) {
        this.tablePlayers = tablePrefix + "players";
        this.tableSeason = tablePrefix + "season";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&serverTimezone=Asia/Shanghai"
                + "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolMaxSize);
        config.setMinimumIdle(poolMinIdle);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setPoolName("NiuNiu-Pool");
        // 连接验证，防止连接超时断开
        config.setConnectionTestQuery("SELECT 1");

        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = dataSource.getConnection()) {
                createTables(conn);
            }
            enabled = true;
            plugin.getLogger().info("MySQL 连接成功！(" + host + ":" + port + "/" + database
                    + ", 前缀=" + (tablePrefix.isEmpty() ? "(无)" : tablePrefix)
                    + ", 池=" + poolMaxSize + "/" + poolMinIdle + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "MySQL 连接失败: " + e.getMessage());
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
            enabled = false;
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled && dataSource != null && !dataSource.isClosed();
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePlayers + " (" +
                "  uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  player_name VARCHAR(32) DEFAULT ''," +
                "  length DOUBLE DEFAULT 0," +
                "  season_peak DOUBLE DEFAULT 0," +
                "  stamina INT DEFAULT 0," +
                "  last_date VARCHAR(12) DEFAULT ''," +
                "  cooldown BIGINT DEFAULT 0," +
                "  wins INT DEFAULT 0," +
                "  battles INT DEFAULT 0," +
                "  buy_date VARCHAR(12) DEFAULT ''," +
                "  buys_today INT DEFAULT 0," +
                "  INDEX idx_player_name (player_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tableSeason + " (" +
                "  season_key VARCHAR(32) NOT NULL PRIMARY KEY," +
                "  season_value TEXT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            plugin.getLogger().info("数据库表已就绪（" + tablePlayers + " / " + tableSeason + "）。");
        }
    }

    public Map<String, Object> loadPlayer(String uuid) {
        Map<String, Object> data = new HashMap<>();
        if (!isEnabled()) return data;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT player_name, length, season_peak, stamina, last_date, cooldown, " +
                 "wins, battles, buy_date, buys_today FROM " + tablePlayers + " WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    data.put("playerName", rs.getString("player_name"));
                    data.put("length", rs.getDouble("length"));
                    data.put("seasonPeak", rs.getDouble("season_peak"));
                    data.put("stamina", rs.getInt("stamina"));
                    data.put("lastDate", rs.getString("last_date"));
                    data.put("cooldown", rs.getLong("cooldown"));
                    data.put("wins", rs.getInt("wins"));
                    data.put("battles", rs.getInt("battles"));
                    data.put("buyDate", rs.getString("buy_date"));
                    data.put("buysToday", rs.getInt("buys_today"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "加载玩家数据失败 (uuid=" + uuid + "): " + e.getMessage());
        }
        return data;
    }

    public Map<String, Map<String, Object>> loadAllPlayers() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        if (!isEnabled()) return all;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT uuid, player_name, length, season_peak, stamina, last_date, cooldown, " +
                 "wins, battles, buy_date, buys_today FROM " + tablePlayers);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> data = new HashMap<>();
                String uuid = rs.getString("uuid");
                data.put("playerName", rs.getString("player_name"));
                data.put("length", rs.getDouble("length"));
                data.put("seasonPeak", rs.getDouble("season_peak"));
                data.put("stamina", rs.getInt("stamina"));
                data.put("lastDate", rs.getString("last_date"));
                data.put("cooldown", rs.getLong("cooldown"));
                data.put("wins", rs.getInt("wins"));
                data.put("battles", rs.getInt("battles"));
                data.put("buyDate", rs.getString("buy_date"));
                data.put("buysToday", rs.getInt("buys_today"));
                all.put(uuid, data);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "加载所有玩家数据失败: " + e.getMessage());
        }
        return all;
    }

    /**
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 代替 REPLACE INTO，
     * 避免 DELETE + INSERT 带来的主键重建和自增 ID 浪费。
     */
    public void savePlayer(String uuid, String playerName, double length, double seasonPeak,
                           int stamina, String lastDate, long cooldown, int wins, int battles,
                           String buyDate, int buysToday) {
        if (!isEnabled()) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO " + tablePlayers + " (uuid, player_name, length, season_peak, stamina, " +
                 "last_date, cooldown, wins, battles, buy_date, buys_today) " +
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE " +
                 "player_name = VALUES(player_name), length = VALUES(length), " +
                 "season_peak = VALUES(season_peak), stamina = VALUES(stamina), " +
                 "last_date = VALUES(last_date), cooldown = VALUES(cooldown), " +
                 "wins = VALUES(wins), battles = VALUES(battles), " +
                 "buy_date = VALUES(buy_date), buys_today = VALUES(buys_today)")) {
            ps.setString(1, uuid);
            ps.setString(2, playerName != null ? playerName : "");
            ps.setDouble(3, length);
            ps.setDouble(4, seasonPeak);
            ps.setInt(5, stamina);
            ps.setString(6, lastDate != null ? lastDate : "");
            ps.setLong(7, cooldown);
            ps.setInt(8, wins);
            ps.setInt(9, battles);
            ps.setString(10, buyDate != null ? buyDate : "");
            ps.setInt(11, buysToday);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "保存玩家数据失败 (uuid=" + uuid + "): " + e.getMessage());
        }
    }

    public Map<String, String> loadSeason() {
        Map<String, String> data = new HashMap<>();
        if (!isEnabled()) return data;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT season_key, season_value FROM " + tableSeason)) {
            while (rs.next()) {
                data.put(rs.getString("season_key"), rs.getString("season_value"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "加载赛季数据失败: " + e.getMessage());
        }
        return data;
    }

    public void saveSeason(String key, String value) {
        if (!isEnabled()) return;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO " + tableSeason + " (season_key, season_value) VALUES (?, ?) " +
                 "ON DUPLICATE KEY UPDATE season_value = VALUES(season_value)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "保存赛季数据失败: " + e.getMessage());
        }
    }

    public int migrateFromYaml(Map<String, Map<String, Object>> yamlPlayers, Map<String, String> yamlSeason) {
        if (!isEnabled()) return -1;

        int count = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + tablePlayers + " (uuid, player_name, length, season_peak, stamina, " +
                     "last_date, cooldown, wins, battles, buy_date, buys_today) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "player_name = VALUES(player_name), length = VALUES(length), " +
                     "season_peak = VALUES(season_peak), stamina = VALUES(stamina), " +
                     "last_date = VALUES(last_date), cooldown = VALUES(cooldown), " +
                     "wins = VALUES(wins), battles = VALUES(battles), " +
                     "buy_date = VALUES(buy_date), buys_today = VALUES(buys_today)")) {
                for (Map.Entry<String, Map<String, Object>> entry : yamlPlayers.entrySet()) {
                    String uuid = entry.getKey();
                    Map<String, Object> d = entry.getValue();
                    ps.setString(1, uuid);
                    ps.setString(2, safeString(d.get("playerName"), ""));
                    ps.setDouble(3, safeDouble(d.get("length"), 0));
                    ps.setDouble(4, safeDouble(d.get("seasonPeak"), 0));
                    ps.setInt(5, safeInt(d.get("stamina"), 0));
                    ps.setString(6, safeString(d.get("lastDate"), ""));
                    ps.setLong(7, safeLong(d.get("cooldown"), 0));
                    ps.setInt(8, safeInt(d.get("wins"), 0));
                    ps.setInt(9, safeInt(d.get("battles"), 0));
                    ps.setString(10, safeString(d.get("buyDate"), ""));
                    ps.setInt(11, safeInt(d.get("buysToday"), 0));
                    ps.addBatch();
                    count++;
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + tableSeason + " (season_key, season_value) VALUES (?, ?) " +
                     "ON DUPLICATE KEY UPDATE season_value = VALUES(season_value)")) {
                for (Map.Entry<String, String> entry : yamlSeason.entrySet()) {
                    ps.setString(1, entry.getKey());
                    ps.setString(2, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            plugin.getLogger().info("数据迁移完成！共迁移 " + count + " 名玩家数据 + 赛季数据。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "数据迁移失败: " + e.getMessage());
            return -1;
        }
        return count;
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("MySQL 数据库连接已关闭。");
        }
    }

    private String safeString(Object obj, String def) {
        return obj != null ? obj.toString() : def;
    }

    private double safeDouble(Object obj, double def) {
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        if (obj instanceof String) {
            try { return Double.parseDouble((String) obj); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private int safeInt(Object obj, int def) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) {
            try { return Integer.parseInt((String) obj); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private long safeLong(Object obj, long def) {
        if (obj instanceof Number) return ((Number) obj).longValue();
        if (obj instanceof String) {
            try { return Long.parseLong((String) obj); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}