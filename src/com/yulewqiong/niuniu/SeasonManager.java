package com.yulewqiong.niuniu;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.UUID;

/**
 * 赛季管理器：负责赛季的自动推进、结算与重置。
 * 赛季数据（current / started）持久化在数据库 season 表中。
 */
public class SeasonManager {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final ConfigManager config;
    private final LangManager lang;

    private int current = 1;
    private long started = 0L;

    public SeasonManager(NiuNiu plugin, PlayerDataManager dataMgr, ConfigManager config, LangManager lang) {
        this.plugin = plugin;
        this.dataMgr = dataMgr;
        this.config = config;
        this.lang = lang;
    }

    /** 从数据库加载赛季状态 */
    public void load() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isEnabled()) {
            started = System.currentTimeMillis();
            return;
        }
        Map<String, String> s = db.loadSeason();
        try {
            current = Integer.parseInt(s.getOrDefault("current", "1"));
        } catch (NumberFormatException ignored) {
            current = 1;
        }
        try {
            started = Long.parseLong(s.getOrDefault("started", "0"));
        } catch (NumberFormatException ignored) {
            started = 0;
        }
        if (started == 0) {
            started = System.currentTimeMillis();
            db.saveSeason("started", String.valueOf(started));
        }
    }

    public int getSeasonCurrent() {
        return current;
    }

    public long getSeasonStarted() {
        return started;
    }

    public void advanceSeasonIfNeeded() {
        if (System.currentTimeMillis() - started >= config.seasonDurationDays * 86400000L) {
            startNewSeason();
        }
    }

    /** 结算当前赛季 → 清空数据库与缓存 → 赛季号 +1 */
    public void startNewSeason() {
        settleSeason();
        int next = current + 1;

        // 清空内存缓存与数据库，玩家需重新打胶激活牛牛
        dataMgr.clearCache();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            db.clearAll();
            db.saveSeason("current", String.valueOf(next));
            db.saveSeason("started", String.valueOf(System.currentTimeMillis()));
        }
        current = next;
        started = System.currentTimeMillis();
        lang.broadcast("season-started", "season", String.valueOf(next));
    }

    /** 找出本赛季最高，授予 PlayerTitle 永久称号 */
    private void settleSeason() {
        PlayerRow top = null;
        double topPeak = -1;
        for (PlayerRow r : dataMgr.getAllRows()) {
            if (r == null || r.length <= 0) continue;
            if (r.seasonPeak > topPeak) {
                topPeak = r.seasonPeak;
                top = r;
            } else if (r.seasonPeak == topPeak && topPeak > 0) {
                if (r.wins > top.wins) {
                    top = r;
                }
            }
        }
        if (top == null) return;

        int season = current;
        String playerName = "?";
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(top.uuid));
            playerName = op.getName() == null ? "?" : op.getName();
        } catch (Exception ignored) {
        }
        playerName = playerName.replaceAll("[^A-Za-z0-9_]", "");
        if (playerName.isEmpty()) playerName = "?";

        String titleName = config.titleNameConf.replace("{season}", String.valueOf(season))
                .replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5&\\u2726\\u2605\\u2606\\u00b7]", "");
        if (titleName.isEmpty()) titleName = "S" + season + "打胶大王";

        String cmd = config.grantCommand.replace("{player}", playerName).replace("{title}", titleName);
        dispatchConsole(cmd);

        String title = config.titleDisplay.replace("{season}", String.valueOf(season));
        lang.broadcast("season-settle-champion", "player", playerName, "season", String.valueOf(season), "title", LangManager.color(title));
        plugin.getLogger().info("赛季结算：S" + season + " 冠军 " + playerName + " 获得称号 " + titleName);
    }

    private void dispatchConsole(String command) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }
}