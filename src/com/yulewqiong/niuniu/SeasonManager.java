package com.yulewqiong.niuniu;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 赛季管理器：负责赛季的自动推进、结算与重置。
 */
public class SeasonManager {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final ConfigManager config;
    private final LangManager lang;

    public SeasonManager(NiuNiu plugin, PlayerDataManager dataMgr, ConfigManager config, LangManager lang) {
        this.plugin = plugin;
        this.dataMgr = dataMgr;
        this.config = config;
        this.lang = lang;
    }

    public int getSeasonCurrent() {
        return dataMgr.getData().getInt("season.current", 1);
    }

    public long getSeasonStarted() {
        return dataMgr.getData().getLong("season.started", 0L);
    }

    public void advanceSeasonIfNeeded() {
        if (System.currentTimeMillis() - getSeasonStarted() >= config.seasonDurationDays * 86400000L) {
            startNewSeason();
        }
    }

    public void startNewSeason() {
        settleSeason();
        int next = getSeasonCurrent() + 1;
        dataMgr.getData().set("season.current", next);
        dataMgr.getData().set("season.started", System.currentTimeMillis());
        if (dataMgr.getData().contains("players")) {
            for (String key : dataMgr.getData().getConfigurationSection("players").getKeys(false)) {
                String b = "players." + key;
                double len = config.initLenMin + ThreadLocalRandom.current().nextDouble() * (config.initLenMax - config.initLenMin);
                dataMgr.getData().set(b + ".length", Math.round(len * 10.0) / 10.0);
                dataMgr.getData().set(b + ".seasonPeak", Math.round(len * 10.0) / 10.0);
                dataMgr.getData().set(b + ".wins", 0);
                dataMgr.getData().set(b + ".battles", 0);
                dataMgr.getData().set(b + ".cooldown", 0L);
                dataMgr.getData().set(b + ".buysToday", 0);
                dataMgr.getData().set(b + ".buyDate", LocalDate.now().toString());
                dataMgr.getData().set(b + ".lastDate", LocalDate.now().toString());
            }
        }
        dataMgr.markDirty();
        lang.broadcast("season-started", "season", String.valueOf(next));
    }

    private void settleSeason() {
        String topKey = null;
        double topPeak = -1;
        if (dataMgr.getData().contains("players")) {
            for (String key : dataMgr.getData().getConfigurationSection("players").getKeys(false)) {
                double peak = dataMgr.getData().getDouble("players." + key + ".seasonPeak", 0);
                if (peak > topPeak) {
                    topPeak = peak;
                    topKey = key;
                } else if (peak == topPeak && topPeak > 0) {
                    int wins1 = dataMgr.getData().getInt("players." + key + ".wins", 0);
                    int wins2 = dataMgr.getData().getInt("players." + topKey + ".wins", 0);
                    if (wins1 > wins2) {
                        topKey = key;
                    }
                }
            }
        }
        if (topKey == null) return;

        int season = getSeasonCurrent();
        String playerName = "?";
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(topKey));
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