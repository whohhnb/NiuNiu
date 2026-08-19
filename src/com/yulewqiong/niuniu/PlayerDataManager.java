package com.yulewqiong.niuniu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 玩家数据管理器：内存缓存 + 数据库持久化。
 * 数据库是唯一持久化源（EvenMoreFish 模式）；内存 Map 仅作运行期缓存。
 * 约定：length > 0 表示玩家已激活牛牛；未激活玩家不落库。
 */
public class PlayerDataManager {

    private final NiuNiu plugin;
    private final ConfigManager config;
    private final LangManager lang;

    private Economy economy;
    private final ConcurrentHashMap<String, PlayerRow> cache = new ConcurrentHashMap<>();

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

    // ===== 缓存加载 =====

    /** 启动时从数据库全量加载进内存缓存 */
    public void loadAll() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            cache.clear();
            cache.putAll(db.loadAllPlayers());
        }
    }

    private PlayerRow row(Player p) {
        return cache.get(p.getUniqueId().toString());
    }

    /** 获取或创建玩家缓存行（用于激活/强制打胶等需要写数据的场景） */
    private PlayerRow getOrCreate(Player p) {
        String uuid = p.getUniqueId().toString();
        return cache.computeIfAbsent(uuid, k -> {
            PlayerRow r = new PlayerRow();
            r.uuid = uuid;
            r.name = p.getName();
            return r;
        });
    }

    /** 按 uuid 获取缓存行，缓存未命中则从数据库读取（离线玩家信息用） */
    public PlayerRow getByUuid(String uuid) {
        PlayerRow r = cache.get(uuid);
        if (r != null) return r;
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            r = db.loadPlayer(uuid);
            if (r != null) cache.put(uuid, r);
        }
        return r;
    }

    /** 返回所有缓存行的快照（赛季结算/排行榜遍历用） */
    public Collection<PlayerRow> getAllRows() {
        return new ArrayList<>(cache.values());
    }

    /** 清空内存缓存（赛季更替时调用） */
    public void clearCache() {
        cache.clear();
    }

    /** 立即持久化某玩家的缓存行 */
    public void save(Player p) {
        PlayerRow r = row(p);
        if (r != null) save(r);
    }

    public void save(PlayerRow r) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isEnabled()) {
            db.savePlayer(r);
        }
    }

    // ===== 基础查询 =====

    public boolean hasCow(Player p) {
        PlayerRow r = row(p);
        return r != null && r.length > 0;
    }

    public void ensureDaily(Player p) {
        PlayerRow r = row(p);
        if (r == null || r.length <= 0) return; // 未激活，跳过
        String today = LocalDate.now().toString();
        if (!today.equals(r.lastDate)) {
            r.lastDate = today;
            r.stamina = config.dailyStamina;
            save(r);
        }
    }

    public double getLength(Player p) {
        PlayerRow r = row(p);
        return r == null ? 0 : r.length;
    }

    public int getStamina(Player p) {
        PlayerRow r = row(p);
        return r == null ? 0 : r.stamina;
    }

    public long getCooldown(Player p) {
        PlayerRow r = row(p);
        return r == null ? 0 : r.cooldown;
    }

    public int getWins(Player p) {
        PlayerRow r = row(p);
        return r == null ? 0 : r.wins;
    }

    public int getBattles(Player p) {
        PlayerRow r = row(p);
        return r == null ? 0 : r.battles;
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
        PlayerRow r = row(p);
        if (r == null || r.stamina < cost) return false;
        r.stamina -= cost;
        save(r);
        return true;
    }

    public double getSeasonPeak(Player p) {
        PlayerRow r = row(p);
        if (r == null) return 0;
        return r.seasonPeak > 0 ? r.seasonPeak : r.length;
    }

    public void updateSeasonPeak(Player p, double newLen) {
        PlayerRow r = row(p);
        if (r == null) return;
        if (newLen > r.seasonPeak) {
            r.seasonPeak = newLen;
            save(r);
        }
    }

    public int countPlayers() {
        DatabaseManager db = plugin.getDatabaseManager();
        return (db != null && db.isEnabled()) ? db.countPlayers() : 0;
    }

    // ===== 打胶 =====

    private void initCow(Player p) {
        PlayerRow r = getOrCreate(p);
        String today = LocalDate.now().toString();
        double len = config.initLenMin + ThreadLocalRandom.current().nextDouble() * (config.initLenMax - config.initLenMin);
        double rounded = Math.round(len * 10.0) / 10.0;
        r.name = p.getName();
        r.length = rounded;
        r.seasonPeak = rounded;
        r.stamina = config.dailyStamina;
        r.lastDate = today;
        r.cooldown = 0L;
        r.wins = 0;
        r.battles = 0;
        r.buysToday = 0;
        r.buyDate = "";
        save(r);
        lang.sendMsg(p, "cow-born", "length", fmtLen(rounded));
        p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    public void doBrush(Player p) {
        if (!hasCow(p)) {
            initCow(p);
            return;
        }
        ensureDaily(p);
        boolean bypassCd = p.hasPermission("niu.bypass.cooldown");
        PlayerRow r = row(p);
        if (!bypassCd && inCooldown(p)) {
            lang.sendMsg(p, "brush-cooldown", "seconds", String.valueOf(statusSec(p)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        if (r.stamina < config.brushCost) {
            lang.sendMsg(p, "brush-no-stamina", "stamina", String.valueOf(r.stamina), "max", String.valueOf(config.maxStamina), "cost", String.valueOf(config.brushCost));
            lang.sendMsg(p, "brush-no-stamina-hint");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        r.stamina -= config.brushCost;
        double old = r.length;
        double grow = config.brushGrowthMin + ThreadLocalRandom.current().nextInt(config.brushGrowthMax - config.brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        r.length = newLen;
        if (!bypassCd) {
            r.cooldown = System.currentTimeMillis() + config.brushCooldown * 1000L;
        }
        if (newLen > r.seasonPeak) r.seasonPeak = newLen;
        save(r);
        if (bypassCd) {
            lang.sendMsg(p, "brush-success-bypass", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow));
        } else {
            lang.sendMsg(p, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(config.brushCooldown));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    public void forceBrush(Player target) {
        PlayerRow r = row(target);
        if (r == null || r.length <= 0) {
            initCow(target);
            return;
        }
        ensureDaily(target);
        r = row(target);
        double old = r.length;
        double grow = config.brushGrowthMin + ThreadLocalRandom.current().nextInt(config.brushGrowthMax - config.brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        r.length = newLen;
        if (!target.hasPermission("niu.bypass.cooldown")) {
            r.cooldown = System.currentTimeMillis() + config.brushCooldown * 1000L;
        }
        if (newLen > r.seasonPeak) r.seasonPeak = newLen;
        save(r);
        lang.sendMsg(target, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(config.brushCooldown));
        target.playSound(target.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    // ===== 购买体力 =====

    public void doBuy(Player p) {
        if (!hasCow(p)) {
            lang.sendMsg(p, "no-cow");
            return;
        }
        ensureDaily(p);
        PlayerRow r = row(p);
        if (r.stamina >= config.maxStamina) {
            lang.sendMsg(p, "buy-full");
            return;
        }
        String today = LocalDate.now().toString();
        if (!today.equals(r.buyDate)) {
            r.buyDate = today;
            r.buysToday = 0;
        }
        if (r.buysToday >= config.buyLimitPerDay) {
            lang.sendMsg(p, "buy-limit", "limit", String.valueOf(config.buyLimitPerDay));
            lang.sendMsg(p, "buy-limit-hint", "bought", String.valueOf(r.buysToday));
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
        r.stamina = Math.min(config.maxStamina, r.stamina + config.buyAmount);
        r.buysToday++;
        save(r);
        lang.sendMsg(p, "buy-success", "amount", String.valueOf(config.buyAmount), "stamina", String.valueOf(r.stamina), "max", String.valueOf(config.maxStamina), "bought", String.valueOf(r.buysToday), "limit", String.valueOf(config.buyLimitPerDay));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.5f);
    }

    // ===== 对战结算辅助 =====

    /** 开战时双方对战计数 +1 */
    public void addBattleCount(Player p) {
        PlayerRow r = row(p);
        if (r == null) return;
        r.battles++;
        save(r);
    }

    /** 对战结束：胜者长度提升+胜场+1，败者长度缩减 */
    public void applyBattleResult(Player winner, Player loser, double wNew, double lNew) {
        PlayerRow w = row(winner);
        PlayerRow l = row(loser);
        if (w != null) {
            w.length = wNew;
            if (wNew > w.seasonPeak) w.seasonPeak = wNew;
            w.wins++;
            save(w);
        }
        if (l != null) {
            l.length = lNew;
            save(l);
        }
    }

    // ===== 旧 data.yml 迁移 =====

    /**
     * 从旧 data.yml（YAML 存储时代）迁移玩家与赛季数据到数据库。
     * 迁移完成后将 data.yml 重命名为 data.yml.migrated 防止重复导入。
     *
     * @return 迁移的玩家数量；-1 表示源文件不存在
     */
    public int migrateFromYaml() {
        File file = new File(plugin.getDataFolder(), "data.yml");
        if (!file.exists()) return -1;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isEnabled()) return -2;

        int count = 0;
        // 玩家数据
        if (yaml.contains("players")) {
            for (String uuid : yaml.getConfigurationSection("players").getKeys(false)) {
                String b = "players." + uuid;
                double length = yaml.getDouble(b + ".length", 0);
                if (length <= 0) continue; // 跳过未激活的幽灵数据
                PlayerRow r = new PlayerRow();
                r.uuid = uuid;
                r.name = yaml.getString(b + ".name", "");
                r.length = length;
                r.seasonPeak = yaml.getDouble(b + ".seasonPeak", length);
                r.stamina = yaml.getInt(b + ".stamina", 0);
                r.lastDate = yaml.getString(b + ".lastDate", "");
                r.cooldown = yaml.getLong(b + ".cooldown", 0);
                r.wins = yaml.getInt(b + ".wins", 0);
                r.battles = yaml.getInt(b + ".battles", 0);
                r.buysToday = yaml.getInt(b + ".buysToday", 0);
                r.buyDate = yaml.getString(b + ".buyDate", "");
                db.savePlayer(r);
                cache.put(uuid, r);
                count++;
            }
        }
        // 赛季数据
        if (yaml.contains("season")) {
            for (String key : yaml.getConfigurationSection("season").getKeys(false)) {
                db.saveSeason(key, yaml.getString("season." + key, ""));
            }
        }
        // 防止下次启动重复导入（仅在成功时重命名）
        File bak = new File(plugin.getDataFolder(), "data.yml.migrated");
        if (file.renameTo(bak)) {
            plugin.getLogger().info("已将 data.yml 重命名为 data.yml.migrated。");
        }
        return count;
    }
}