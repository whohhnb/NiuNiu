package com.yulewqiong.niuniu;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * 数据库管理器：负责连接（SQLite 默认 / MySQL 可选）与全部 CRUD。
 * 数据库是玩家数据的唯一持久化源（EvenMoreFish 模式），YAML 不再存储玩家数据。
 */
public class DatabaseManager {

    public enum Backend { SQLITE, MYSQL }

    private final NiuNiu plugin;
    private HikariDataSource dataSource;
    private boolean enabled = false;

    private Backend backend = Backend.SQLITE;
    private String tablePlayers;
    private String tableSeason;

    public DatabaseManager(NiuNiu plugin) {
        this.plugin = plugin;
    }

    /**
     * 统一的连接入口。
     *
     * @param type   后端类型：sqlite / mysql
     * @param sqliteFile SQLite 文件绝对路径（backend=sqlite 时生效）
     * @param host       MySQL 主机
     * @param port       MySQL 端口
     * @param database   MySQL 库名
     * @param username   MySQL 用户
     * @param password   MySQL 密码
     * @param tablePrefix 表名前缀
     * @param poolMaxSize 连接池大小
     * @param poolMinIdle 最小空闲连接
     */
    public boolean connect(String type, String sqliteFile, String host, int port, String database,
                           String username, String password, String tablePrefix, int poolMaxSize, int poolMinIdle) {
        boolean wantMysql = type != null && type.equalsIgnoreCase("mysql");
        this.tablePlayers = tablePrefix + "players";
        this.tableSeason = tablePrefix + "season";
        this.backend = wantMysql ? Backend.MYSQL : Backend.SQLITE;

        HikariConfig config = new HikariConfig();
        if (wantMysql) {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&serverTimezone=Asia/Shanghai"
                    + "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048");
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(Math.max(1, poolMaxSize));
            config.setMinimumIdle(Math.max(0, Math.min(poolMaxSize, poolMinIdle)));
            config.setConnectionTestQuery("SELECT 1");
        } else {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + sqliteFile.replace('\\', '/'));
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(5000);
        }
        config.setPoolName("NiuNiu-Pool");

        try {
            dataSource = new HikariDataSource(config);
            try (Connection conn = dataSource.getConnection()) {
                createTables(conn);
            }
            enabled = true;
            plugin.getLogger().info("数据库连接成功（" + (wantMysql ? "MySQL" : "SQLite") + "，前缀="
                    + (tablePrefix.isEmpty() ? "(无)" : tablePrefix) + "）");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "数据库连接失败: " + e.getMessage());
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

    public Backend getBackend() {
        return backend;
    }

    // ===== 建表 =====

    private void createTables(Connection conn) throws SQLException {
        String engineClause = backend == Backend.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tablePlayers + " (" +
                "  uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "  length DOUBLE DEFAULT 0," +
                "  season_peak DOUBLE DEFAULT 0," +
                "  stamina INT DEFAULT 0," +
                "  last_date VARCHAR(12) DEFAULT ''," +
                "  cooldown BIGINT DEFAULT 0," +
                "  wins INT DEFAULT 0," +
                "  battles INT DEFAULT 0," +
                "  buy_date VARCHAR(12) DEFAULT ''," +
                "  buys_today INT DEFAULT 0" +
                ")" + engineClause
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tableSeason + " (" +
                "  season_key VARCHAR(32) NOT NULL PRIMARY KEY," +
                "  season_value TEXT" +
                ")" + engineClause
            );
            plugin.getLogger().info("数据库表已就绪（" + tablePlayers + " / " + tableSeason + "）。");
        }
    }

    // ===== 玩家 CRUD =====

    public PlayerRow loadPlayer(String uuid) {
        if (!isEnabled()) return null;
        String sql = "SELECT length, season_peak, stamina, last_date, cooldown, wins, battles, buy_date, buys_today " +
                "FROM " + tablePlayers + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readRow(uuid, rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "加载玩家失败 (uuid=" + uuid + "): " + e.getMessage());
        }
        return null;
    }

    public Map<String, PlayerRow> loadAllPlayers() {
        Map<String, PlayerRow> all = new LinkedHashMap<>();
        if (!isEnabled()) return all;
        String sql = "SELECT uuid, length, season_peak, stamina, last_date, cooldown, wins, battles, buy_date, buys_today " +
                "FROM " + tablePlayers;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String uuid = rs.getString("uuid");
                all.put(uuid, readRow(uuid, rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "加载全部玩家失败: " + e.getMessage());
        }
        return all;
    }

    private PlayerRow readRow(String uuid, ResultSet rs) throws SQLException {
        PlayerRow r = new PlayerRow();
        r.uuid = uuid;
        r.length = rs.getDouble("length");
        r.seasonPeak = rs.getDouble("season_peak");
        r.stamina = rs.getInt("stamina");
        r.lastDate = rs.getString("last_date");
        r.cooldown = rs.getLong("cooldown");
        r.wins = rs.getInt("wins");
        r.battles = rs.getInt("battles");
        r.buyDate = rs.getString("buy_date");
        r.buysToday = rs.getInt("buys_today");
        return r;
    }

    private String upsertPlayersSql() {
        if (backend == Backend.MYSQL) {
            return "INSERT INTO " + tablePlayers +
                    " (uuid, length, season_peak, stamina, last_date, cooldown, wins, battles, buy_date, buys_today) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE length=VALUES(length), " +
                    "season_peak=VALUES(season_peak), stamina=VALUES(stamina), last_date=VALUES(last_date), " +
                    "cooldown=VALUES(cooldown), wins=VALUES(wins), battles=VALUES(battles), " +
                    "buy_date=VALUES(buy_date), buys_today=VALUES(buys_today)";
        }
        return "INSERT INTO " + tablePlayers +
                " (uuid, length, season_peak, stamina, last_date, cooldown, wins, battles, buy_date, buys_today) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET length=excluded.length, " +
                "season_peak=excluded.season_peak, stamina=excluded.stamina, last_date=excluded.last_date, " +
                "cooldown=excluded.cooldown, wins=excluded.wins, battles=excluded.battles, " +
                "buy_date=excluded.buy_date, buys_today=excluded.buys_today";
    }

    public void savePlayer(PlayerRow r) {
        if (!isEnabled()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertPlayersSql())) {
            ps.setString(1, r.uuid);
            ps.setDouble(2, r.length);
            ps.setDouble(3, r.seasonPeak);
            ps.setInt(4, r.stamina);
            ps.setString(5, r.lastDate != null ? r.lastDate : "");
            ps.setLong(6, r.cooldown);
            ps.setInt(7, r.wins);
            ps.setInt(8, r.battles);
            ps.setString(9, r.buyDate != null ? r.buyDate : "");
            ps.setInt(10, r.buysToday);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "保存玩家失败 (uuid=" + r.uuid + "): " + e.getMessage());
        }
    }

    public void deletePlayer(String uuid) {
        if (!isEnabled()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablePlayers + " WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "删除玩家失败 (uuid=" + uuid + "): " + e.getMessage());
        }
    }

    public int countPlayers() {
        if (!isEnabled()) return 0;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tablePlayers)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "统计玩家数失败: " + e.getMessage());
        }
        return 0;
    }

    // ===== 赛季数据 =====

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
        String sql = backend == Backend.MYSQL
                ? "INSERT INTO " + tableSeason + " (season_key, season_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE season_value=VALUES(season_value)"
                : "INSERT INTO " + tableSeason + " (season_key, season_value) VALUES (?, ?) ON CONFLICT(season_key) DO UPDATE SET season_value=excluded.season_value";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "保存赛季数据失败: " + e.getMessage());
        }
    }

    // ===== 清空 =====

    public void clearAll() {
        if (!isEnabled()) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM " + tablePlayers);
            stmt.executeUpdate("DELETE FROM " + tableSeason);
            plugin.getLogger().info("数据库表已清空（" + tablePlayers + " / " + tableSeason + "）。");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "清空数据库失败: " + e.getMessage());
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("数据库连接已关闭。");
        }
    }
}