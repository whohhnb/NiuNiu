package com.yulewqiong.niuniu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 玩家数据管理器：负责玩家数据的 CRUD、打胶、购买体力、MySQL 同步等操作。
 */
public class PlayerDataManager {

    private final NiuNiu plugin;
    private final ConfigManager config;
    private final LangManager lang;

    private FileConfiguration data;
    private File dataFile;
    private boolean dirty;
    private Economy economy;

    public PlayerDataManager(NiuNiu plugin, ConfigManager config, LangManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    public Economy getEconomy() {
        return economy;
    }

    // ===== 数据文件 =====

    public void loadData() {
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveData() {
        if (data != null) {
            try {
                data.save(dataFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // 同步到 MySQL
            DatabaseManager db = plugin.getDatabaseManager();
            if (plugin.isUseMysql() && db != null && db.isEnabled()) {
                syncToMysql(db);
            }
        }
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public FileConfiguration getData() {
        return data;
    }

    // ===== 玩家数据基础方法 =====

    public String base(OfflinePlayer p) {
        return "players." + p.getUniqueId();
    }

    public void ensure(Player p) {
        String b = base(p);
        String today = LocalDate.now().toString();
        String last = data.getString(b + ".lastDate", "");
        if (!last.equals(today)) {
            data.set(b + ".lastDate", today);
            data.set(b + ".stamina", config.dailyStamina);
            markDirty();
        }
        if (!data.contains(b + ".length")) {
            double len = config.initLenMin + ThreadLocalRandom.current().nextDouble() * (config.initLenMax - config.initLenMin);
            data.set(b + ".length", Math.round(len * 10.0) / 10.0);
            data.set(b + ".seasonPeak", Math.round(len * 10.0) / 10.0);
            data.set(b + ".stamina", config.dailyStamina);
            data.set(b + ".lastDate", today);
            data.set(b + ".cooldown", 0L);
            data.set(b + ".wins", 0);
            data.set(b + ".battles", 0);
            data.set(b + ".buysToday", 0);
            data.set(b + ".buyDate", "");
            markDirty();
            lang.sendMsg(p, "cow-born", "length", fmtLen(getLength(p)));
        } else if (!data.contains(b + ".seasonPeak")) {
            data.set(b + ".seasonPeak", getLength(p));
            markDirty();
        }
    }

    public double getLength(Player p) {
        return data.getDouble(base(p) + ".length", 0);
    }

    public double getLengthByUuid(String uuid) {
        return data.getDouble("players." + uuid + ".length", 0);
    }

    public int getStamina(Player p) {
        return data.getInt(base(p) + ".stamina", 0);
    }

    public long getCooldown(Player p) {
        return data.getLong(base(p) + ".cooldown", 0);
    }

    public int getWins(Player p) {
        return data.getInt(base(p) + ".wins", 0);
    }

    public int getBattles(Player p) {
        return data.getInt(base(p) + ".battles", 0);
    }

    public long cooldownLeft(Player p) {
        return Math.max(0, getCooldown(p) - System.currentTimeMillis());
    }

    public boolean inCooldown(Player p) {
        return cooldownLeft(p) > 0;
    }

    public String fmtLen(double v) {
        return String.format("%.1f", v);
    }

    public int getCP(Player p) {
        return (int) Math.round(getLength(p) * config.cpMult) + config.cpBase;
    }

    public int getCP(double len) {
        return (int) Math.round(len * config.cpMult) + config.cpBase;
    }

    public String getRank(int cp) {
        if (cp < 50) return "&7幼牛";
        if (cp < 150) return "&a小牛";
        if (cp < 400) return "&e壮牛";
        if (cp < 1000) return "&6牛将";
        if (cp < 2500) return "&c牛王";
        return "&d牛神";
    }

    public long statusSec(Player p) {
        return inCooldown(p) ? cooldownLeft(p) / 1000 + 1 : 0;
    }

    public boolean spendStamina(Player p, int cost) {
        int s = getStamina(p);
        if (s < cost) return false;
        data.set(base(p) + ".stamina", s - cost);
        markDirty();
        return true;
    }

    public double getSeasonPeak(Player p) {
        return data.getDouble(base(p) + ".seasonPeak", getLength(p));
    }

    public void updateSeasonPeak(Player p, double newLen) {
        if (newLen > getSeasonPeak(p)) {
            data.set(base(p) + ".seasonPeak", newLen);
            markDirty();
        }
    }

    public int countPlayers() {
        if (data.contains("players")) {
            return data.getConfigurationSection("players").getKeys(false).size();
        }
        return 0;
    }

    // ===== 打胶 =====

    public void doBrush(Player p) {
        ensure(p);
        boolean bypassCd = p.hasPermission("niu.bypass.cooldown");
        if (!bypassCd && inCooldown(p)) {
            lang.sendMsg(p, "brush-cooldown", "seconds", String.valueOf(statusSec(p)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        int s = getStamina(p);
        if (s < config.brushCost) {
            lang.sendMsg(p, "brush-no-stamina", "stamina", String.valueOf(s), "max", String.valueOf(config.maxStamina), "cost", String.valueOf(config.brushCost));
            lang.sendMsg(p, "brush-no-stamina-hint");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        spendStamina(p, config.brushCost);
        double old = getLength(p);
        double grow = config.brushGrowthMin + ThreadLocalRandom.current().nextInt(config.brushGrowthMax - config.brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        data.set(base(p) + ".length", newLen);
        if (!bypassCd) {
            data.set(base(p) + ".cooldown", System.currentTimeMillis() + config.brushCooldown * 1000L);
        }
        updateSeasonPeak(p, newLen);
        markDirty();
        if (bypassCd) {
            lang.sendMsg(p, "brush-success-bypass", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow));
        } else {
            lang.sendMsg(p, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(config.brushCooldown));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    public void forceBrush(Player target) {
        ensure(target);
        double old = getLength(target);
        double grow = config.brushGrowthMin + ThreadLocalRandom.current().nextInt(config.brushGrowthMax - config.brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        data.set(base(target) + ".length", newLen);
        if (!target.hasPermission("niu.bypass.cooldown")) {
            data.set(base(target) + ".cooldown", System.currentTimeMillis() + config.brushCooldown * 1000L);
        }
        updateSeasonPeak(target, newLen);
        markDirty();
        lang.sendMsg(target, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(config.brushCooldown));
        target.playSound(target.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    // ===== 购买体力 =====

    public void doBuy(Player p) {
        ensure(p);
        int s = getStamina(p);
        if (s >= config.maxStamina) {
            lang.sendMsg(p, "buy-full");
            return;
        }
        String b = base(p);
        String today = LocalDate.now().toString();
        if (!today.equals(data.getString(b + ".buyDate", ""))) {
            data.set(b + ".buyDate", today);
            data.set(b + ".buysToday", 0);
        }
        int bought = data.getInt(b + ".buysToday", 0);
        if (bought >= config.buyLimitPerDay) {
            lang.sendMsg(p, "buy-limit", "limit", String.valueOf(config.buyLimitPerDay));
            lang.sendMsg(p, "buy-limit-hint", "bought", String.valueOf(bought));
            return;
        }
        if (economy == null) {
            lang.sendMsg(p, "buy-no-economy");
            return;
        }
        if (!economy.has(p, config.buyPrice)) {
            lang.sendMsg(p, "buy-no-money", "price", String.valueOf(config.buyPrice));
            return;
        }
        economy.withdrawPlayer(p, config.buyPrice);
        int ns = Math.min(config.maxStamina, s + config.buyAmount);
        data.set(base(p) + ".stamina", ns);
        data.set(b + ".buysToday", bought + 1);
        markDirty();
        lang.sendMsg(p, "buy-success", "amount", String.valueOf(config.buyAmount), "stamina", String.valueOf(ns), "max", String.valueOf(config.maxStamina), "bought", String.valueOf(bought + 1), "limit", String.valueOf(config.buyLimitPerDay));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.5f);
    }

    // ===== MySQL 同步 =====

    public void loadFromMysql(DatabaseManager db) {
        Map<String, String> seasonData = db.loadSeason();
        for (Map.Entry<String, String> entry : seasonData.entrySet()) {
            data.set("season." + entry.getKey(), entry.getValue());
        }
        Map<String, Map<String, Object>> allPlayers = db.loadAllPlayers();
        for (Map.Entry<String, Map<String, Object>> entry : allPlayers.entrySet()) {
            String uuid = entry.getKey();
            Map<String, Object> pd = entry.getValue();
            String b = "players." + uuid;
            data.set(b + ".name", pd.getOrDefault("playerName", ""));
            data.set(b + ".length", pd.getOrDefault("length", 0.0));
            data.set(b + ".seasonPeak", pd.getOrDefault("seasonPeak", 0.0));
            data.set(b + ".stamina", pd.getOrDefault("stamina", 0));
            data.set(b + ".lastDate", pd.getOrDefault("lastDate", ""));
            data.set(b + ".cooldown", pd.getOrDefault("cooldown", 0L));
            data.set(b + ".wins", pd.getOrDefault("wins", 0));
            data.set(b + ".battles", pd.getOrDefault("battles", 0));
            data.set(b + ".buyDate", pd.getOrDefault("buyDate", ""));
            data.set(b + ".buysToday", pd.getOrDefault("buysToday", 0));
        }
    }

    public void syncToMysql(DatabaseManager db) {
        if (data.contains("season")) {
            for (String key : data.getConfigurationSection("season").getKeys(false)) {
                db.saveSeason(key, data.getString("season." + key, ""));
            }
        }
        if (data.contains("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                String b = "players." + key;
                db.savePlayer(
                    key,
                    data.getString(b + ".name", ""),
                    data.getDouble(b + ".length", 0),
                    data.getDouble(b + ".seasonPeak", 0),
                    data.getInt(b + ".stamina", 0),
                    data.getString(b + ".lastDate", ""),
                    data.getLong(b + ".cooldown", 0),
                    data.getInt(b + ".wins", 0),
                    data.getInt(b + ".battles", 0),
                    data.getString(b + ".buyDate", ""),
                    data.getInt(b + ".buysToday", 0)
                );
            }
        }
    }

    public int doMigrate(DatabaseManager db) {
        Map<String, Map<String, Object>> yamlPlayers = new LinkedHashMap<>();
        if (data.contains("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                String b = "players." + key;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("playerName", data.getString(b + ".name", ""));
                d.put("length", data.getDouble(b + ".length", 0));
                d.put("seasonPeak", data.getDouble(b + ".seasonPeak", 0));
                d.put("stamina", data.getInt(b + ".stamina", 0));
                d.put("lastDate", data.getString(b + ".lastDate", ""));
                d.put("cooldown", data.getLong(b + ".cooldown", 0));
                d.put("wins", data.getInt(b + ".wins", 0));
                d.put("battles", data.getInt(b + ".battles", 0));
                d.put("buyDate", data.getString(b + ".buyDate", ""));
                d.put("buysToday", data.getInt(b + ".buysToday", 0));
                yamlPlayers.put(key, d);
            }
        }
        Map<String, String> yamlSeason = new LinkedHashMap<>();
        if (data.contains("season")) {
            for (String key : data.getConfigurationSection("season").getKeys(false)) {
                yamlSeason.put(key, data.getString("season." + key, ""));
            }
        }
        return db.migrateFromYaml(yamlPlayers, yamlSeason);
    }
}