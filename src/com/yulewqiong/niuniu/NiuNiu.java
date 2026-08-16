package com.yulewqiong.niuniu;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class NiuNiu extends JavaPlugin implements Listener {

    private Economy economy;
    private FileConfiguration data;
    private File dataFile;
    private FileConfiguration lang;
    private File langFile;
    private NamespacedKey actionKey;

    // ===== MySQL 支持 =====
    private DatabaseManager db;
    private boolean useMysql;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;

    private int maxStamina;
    private int dailyStamina;
    private int battleCost;
    private int brushCost;
    private int brushGrowthMin;
    private int brushGrowthMax;
    private int brushCooldown;
    private int buyPrice;
    private int buyAmount;
    private int buyLimitPerDay;
    private int winGrowthMin;
    private int winGrowthMax;
    private int loseLossMin;
    private int loseLossMax;
    private int saveInterval;
    private int seasonDurationDays;
    private double initLenMin;
    private double initLenMax;
    private double minLength;
    private double minChallengeLength;
    private double cpMult;
    private int cpBase;
    private int hpBase;
    private int hpPerRank;
    private int rageMax;
    private int rageStart;
    private int rageRegen;
    private int skillTailDamageBase;
    private double skillTailDamagePerCp;
    private int skillTailCost;
    private int skillHornDamageBase;
    private double skillHornDamagePerCp;
    private int skillHornCost;
    private int skillShieldRage;
    private int battlePickTimeout;
    private String titleNameConf;
    private String createCommand;
    private String grantCommand;
    private String titleDisplay;

    private boolean dirty;
    private BukkitTask saveTask;
    private BukkitTask refreshTask;
    private final Map<UUID, Battle> battles = new HashMap<>();
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Set<UUID> challengeBusy = new HashSet<>();

    private static final int SKILL_TAIL = 1;
    private static final int SKILL_HORN = 2;
    private static final int SKILL_SHIELD = 3;

    private static class Battle {
        UUID a;
        UUID b;
        String nameA;
        String nameB;
        int hpA;
        int hpB;
        int maxA;
        int maxB;
        int rageA;
        int rageB;
        int turn; // 0=A选, 1=B选
        int choiceA = -1;
        int choiceB = -1;
        int pickGen = 0;
        int timeoutTask = -1;
        boolean over;
    }

    private static class Challenge {
        UUID from;
        UUID target;
        String fromName;
        String targetName;
        int timeoutTask = -1;
        boolean done;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        loadData();
        loadLang();
        actionKey = new NamespacedKey(this, "niu_action");
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        }
        if (economy == null) {
            getLogger().warning("未找到 Vault 经济，购买体力将无法使用。");
        }
        // ===== MySQL 初始化 =====
        useMysql = getConfig().getBoolean("mysql.enabled", false);
        if (useMysql) {
            mysqlHost = getConfig().getString("mysql.host", "localhost");
            mysqlPort = getConfig().getInt("mysql.port", 3306);
            mysqlDatabase = getConfig().getString("mysql.database", "niuniu");
            mysqlUsername = getConfig().getString("mysql.username", "root");
            mysqlPassword = getConfig().getString("mysql.password", "");
            db = new DatabaseManager(this);
            if (db.connect(mysqlHost, mysqlPort, mysqlDatabase, mysqlUsername, mysqlPassword)) {
                loadFromMysql();
                getLogger().info("已从 MySQL 加载数据，共 " + countPlayers() + " 名玩家。");
            } else {
                getLogger().warning("MySQL 连接失败，回退到 YAML 存储。");
                useMysql = false;
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        advanceSeasonIfNeeded();
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            advanceSeasonIfNeeded();
            if (dirty) {
                saveData();
                dirty = false;
            }
        }, saveInterval * 20L, saveInterval * 20L);
        // 每秒重绘主菜单，实时刷新冷却倒计时
        String mainTitle = ChatColor.stripColor(color("&6&l牛牛对战系统"));
        refreshTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getOpenInventory().getTopInventory() == null) {
                    continue;
                }
                String t = ChatColor.stripColor(pl.getOpenInventory().getTitle());
                if (t != null && t.contains(mainTitle)) {
                    renderMain(pl, pl.getOpenInventory().getTopInventory());
                }
            }
        }, 20L, 20L);
        if (useMysql) { getLogger().info("NiuNiu 牛牛对战系统已启用（MySQL 模式）"); } else { getLogger().info("NiuNiu 牛牛对战系统已启用"); }
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (dirty) {
            saveData();
        }
        if (useMysql && db != null) {
            db.close();
        }
    }

    private void loadConfig() {
        FileConfiguration c = getConfig();
        maxStamina = c.getInt("settings.max-stamina", 100);
        dailyStamina = c.getInt("settings.daily-stamina", 100);
        battleCost = c.getInt("settings.battle-cost", 5);
        brushCost = c.getInt("settings.brush-cost", 5);
        brushGrowthMin = c.getInt("settings.brush-growth-min", 1);
        brushGrowthMax = c.getInt("settings.brush-growth-max", 5);
        brushCooldown = c.getInt("settings.brush-cooldown-seconds", 60);
        buyPrice = c.getInt("settings.buy-price", 1000);
        buyAmount = c.getInt("settings.buy-amount", 50);
        buyLimitPerDay = c.getInt("settings.buy-limit-per-day", 5);
        winGrowthMin = c.getInt("settings.win-growth-min", 1);
        winGrowthMax = c.getInt("settings.win-growth-max", 3);
        loseLossMin = c.getInt("settings.lose-loss-min", 1);
        loseLossMax = c.getInt("settings.lose-loss-max", 3);
        saveInterval = Math.max(5, c.getInt("settings.save-interval-seconds", 30));
        seasonDurationDays = Math.max(1, c.getInt("settings.season-duration-days", 14));
        initLenMin = c.getDouble("settings.initial-length-min", 5);
        initLenMax = c.getDouble("settings.initial-length-max", 10);
        minLength = c.getDouble("settings.min-length", 5);
        minChallengeLength = c.getDouble("settings.min-challenge-length", 10);
        cpMult = c.getDouble("settings.cp-length-multiplier", 2.0);
        cpBase = c.getInt("settings.cp-base", 10);
        hpBase = c.getInt("settings.hp-base", 91);
        hpPerRank = c.getInt("settings.hp-per-rank", 20);
        rageMax = c.getInt("settings.rage-max", 100);
        rageStart = c.getInt("settings.rage-start", 60);
        rageRegen = c.getInt("settings.rage-regen", 20);
        skillTailDamageBase = c.getInt("settings.skill-tail-damage-base", 30);
        skillTailDamagePerCp = c.getDouble("settings.skill-tail-damage-percp", 0.03);
        skillTailCost = c.getInt("settings.skill-tail-cost", 50);
        skillHornDamageBase = c.getInt("settings.skill-horn-damage-base", 15);
        skillHornDamagePerCp = c.getDouble("settings.skill-horn-damage-percp", 0.015);
        skillHornCost = c.getInt("settings.skill-horn-cost", 20);
        skillShieldRage = c.getInt("settings.skill-shield-rage", 45);
        battlePickTimeout = Math.max(5, c.getInt("settings.battle-pick-timeout", 30));
        titleNameConf = c.getString("settlement.title-name", "S{season}打胶大王");
        createCommand = c.getString("settlement.create-command", "plt title add activity {title} 0 0 true 牛牛赛季冠军称号");
        grantCommand = c.getString("settlement.grant-command", "plt player add {player} {title} 0");
        titleDisplay = c.getString("settlement.title-display", "&6&l✦ &e&lS{season} 打胶大王 &6&l✦");
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        if (data != null) {
            try {
                data.save(dataFile);
                            // 同步到 MySQL
            if (useMysql && db != null && db.isEnabled()) {
                syncToMysql();
            }            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadLang() {
        langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    private void markDirty() {
        dirty = true;
    }

    // ===== 赛季 =====
    private int getSeasonCurrent() {
        return data.getInt("season.current", 1);
    }

    private long getSeasonStarted() {
        return data.getLong("season.started", System.currentTimeMillis());
    }

    private void advanceSeasonIfNeeded() {
        if (System.currentTimeMillis() - getSeasonStarted() >= seasonDurationDays * 86400000L) {
            startNewSeason();
        }
    }

    private void startNewSeason() {
        settleSeason();
        int next = getSeasonCurrent() + 1;
        data.set("season.current", next);
        data.set("season.started", System.currentTimeMillis());
        if (data.contains("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                String b = "players." + key;
                double len = initLenMin + ThreadLocalRandom.current().nextDouble() * (initLenMax - initLenMin);
                data.set(b + ".length", Math.round(len * 10.0) / 10.0);
                data.set(b + ".seasonPeak", Math.round(len * 10.0) / 10.0);
                data.set(b + ".wins", 0);
                data.set(b + ".battles", 0);
                data.set(b + ".cooldown", 0L);
                // 赛季更替重置当天体力购买限制
                data.set(b + ".buysToday", 0);
                data.set(b + ".buyDate", LocalDate.now().toString());
            }
        }
        markDirty();
        broadcast("season-started", "season", String.valueOf(next));
    }

    // 结算当前赛季：找出赛季最高 #1，授予 PlayerTitle 永久称号权限
    private void settleSeason() {
        String topKey = null;
        double topPeak = -1;
        if (data.contains("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                double peak = data.getDouble("players." + key + ".seasonPeak", 0);
                if (peak > topPeak) {
                    topPeak = peak;
                    topKey = key;
                }
            }
        }
        if (topKey == null) {
            return; // 没有玩家，跳过结算
        }
        int season = getSeasonCurrent();
        String playerName = "?";
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(topKey));
            playerName = op.getName() == null ? "?" : op.getName();
        } catch (Exception ignored) {
        }
        // 防命令注入：控制台命令里的玩家名只保留字母数字下划线
        playerName = playerName.replaceAll("[^A-Za-z0-9_]", "");
        if (playerName.isEmpty()) {
            playerName = "?";
        }
        // PlayerTitle 直接给玩家称号（自动创建隐藏称号+发放，0=永久，不进商店）
        // 消毒：保留字母数字/中文/&颜色码/✦★☆·装饰，去除空格与危险字符（保证单参数命令可解析）
        String titleName = titleNameConf.replace("{season}", String.valueOf(season))
                .replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5&\\u2726\\u2605\\u2606\\u00b7]", "");
        if (titleName.isEmpty()) {
            titleName = "S" + season + "打胶大王";
        }
        String cmd = grantCommand.replace("{player}", playerName).replace("{title}", titleName);
        dispatchConsole(cmd);
        String title = titleDisplay.replace("{season}", String.valueOf(season));
        broadcast("season-settle-champion", "player", playerName, "season", String.valueOf(season), "title", color(title));
        getLogger().info("赛季结算：S" + season + " 冠军 " + playerName + " 获得称号 " + titleName);
    }

    private void dispatchConsole(String command) {
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private double getSeasonPeak(Player p) {
        return data.getDouble(base(p) + ".seasonPeak", getLength(p));
    }

    private void updateSeasonPeak(Player p, double newLen) {
        if (newLen > getSeasonPeak(p)) {
            data.set(base(p) + ".seasonPeak", newLen);
        }
    }

    // ===== 语言助手（仅聊天消息）=====
    private String prefix() {
        return lang.getString("prefix", "&7[&6牛牛&7] &r");
    }

    private String t(String key, String... kv) {
        String s = lang.getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return color(s);
    }

    private void sendMsg(Player p, String key, String... kv) {
        p.sendMessage(color(prefix() + t(key, kv)));
    }

    private void broadcast(String key, String... kv) {
        Bukkit.broadcastMessage(color(prefix() + t(key, kv)));
    }

    // ===== 数据助手 =====
    private String base(OfflinePlayer p) {
        return "players." + p.getUniqueId();
    }

    private void ensure(Player p) {
        advanceSeasonIfNeeded();
        String b = base(p);
        String today = LocalDate.now().toString();
        String last = data.getString(b + ".lastDate", "");
        if (!last.equals(today)) {
            data.set(b + ".lastDate", today);
            data.set(b + ".stamina", dailyStamina);
            markDirty();
        }
        if (!data.contains(b + ".length")) {
            double len = initLenMin + ThreadLocalRandom.current().nextDouble() * (initLenMax - initLenMin);
            data.set(b + ".length", Math.round(len * 10.0) / 10.0);
            data.set(b + ".seasonPeak", Math.round(len * 10.0) / 10.0);
            data.set(b + ".stamina", dailyStamina);
            data.set(b + ".lastDate", today);
            data.set(b + ".cooldown", 0L);
            data.set(b + ".wins", 0);
            data.set(b + ".battles", 0);
            markDirty();
            sendMsg(p, "cow-born", "length", fmtLen(getLength(p)));
        } else if (!data.contains(b + ".seasonPeak")) {
            data.set(b + ".seasonPeak", getLength(p));
            markDirty();
        }
    }

    private double getLength(Player p) {
        return data.getDouble(base(p) + ".length", 0);
    }

    private int getStamina(Player p) {
        return data.getInt(base(p) + ".stamina", 0);
    }

    private long getCooldown(Player p) {
        return data.getLong(base(p) + ".cooldown", 0);
    }

    private int getWins(Player p) {
        return data.getInt(base(p) + ".wins", 0);
    }

    private int getBattles(Player p) {
        return data.getInt(base(p) + ".battles", 0);
    }

    private long cooldownLeft(Player p) {
        return Math.max(0, getCooldown(p) - System.currentTimeMillis());
    }

    private boolean inCooldown(Player p) {
        return cooldownLeft(p) > 0;
    }

    private String fmtLen(double v) {
        return String.format("%.1f", v);
    }

    private int getCP(Player p) {
        return (int) Math.round(getLength(p) * cpMult) + cpBase;
    }

    private int getCP(double len) {
        return (int) Math.round(len * cpMult) + cpBase;
    }

    private String getRank(int cp) {
        if (cp < 50) {
            return "&7幼牛";
        }
        if (cp < 150) {
            return "&a小牛";
        }
        if (cp < 400) {
            return "&e壮牛";
        }
        if (cp < 1000) {
            return "&6牛将";
        }
        if (cp < 2500) {
            return "&c牛王";
        }
        return "&d牛神";
    }

    private long statusSec(Player p) {
        return inCooldown(p) ? cooldownLeft(p) / 1000 + 1 : 0;
    }

    private boolean spendStamina(Player p, int cost) {
        int s = getStamina(p);
        if (s < cost) {
            return false;
        }
        data.set(base(p) + ".stamina", s - cost);
        markDirty();
        return true;
    }

    // ===== 动作 =====
    private void doBrush(Player p) {
        ensure(p);
        // niu.bypass.cooldown：无视打胶冷却（不检查、打胶后也不进入冷却）
        boolean bypassCd = p.hasPermission("niu.bypass.cooldown");
        if (!bypassCd && inCooldown(p)) {
            sendMsg(p, "brush-cooldown", "seconds", String.valueOf(statusSec(p)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        int s = getStamina(p);
        if (s < brushCost) {
            sendMsg(p, "brush-no-stamina", "stamina", String.valueOf(s), "max", String.valueOf(maxStamina), "cost", String.valueOf(brushCost));
            sendMsg(p, "brush-no-stamina-hint");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
            return;
        }
        spendStamina(p, brushCost);
        double old = getLength(p);
        double grow = brushGrowthMin + ThreadLocalRandom.current().nextInt(brushGrowthMax - brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        data.set(base(p) + ".length", newLen);
        if (!bypassCd) {
            data.set(base(p) + ".cooldown", System.currentTimeMillis() + brushCooldown * 1000L);
        }
        updateSeasonPeak(p, newLen);
        markDirty();
        if (bypassCd) {
            sendMsg(p, "brush-success-bypass", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow));
        } else {
            sendMsg(p, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(brushCooldown));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    // 管理员强制打胶：无视冷却与体力，直接成长
    private void forceBrush(Player target) {
        ensure(target);
        double old = getLength(target);
        double grow = brushGrowthMin + ThreadLocalRandom.current().nextInt(brushGrowthMax - brushGrowthMin + 1);
        double newLen = Math.round((old + grow) * 10.0) / 10.0;
        data.set(base(target) + ".length", newLen);
        data.set(base(target) + ".cooldown", System.currentTimeMillis() + brushCooldown * 1000L);
        updateSeasonPeak(target, newLen);
        markDirty();
        sendMsg(target, "brush-success", "old", fmtLen(old), "new", fmtLen(newLen), "grow", String.valueOf((int) grow), "cooldown", String.valueOf(brushCooldown));
        target.playSound(target.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.4f);
    }

    private void doBattle(Player a, Player b) {
        ensure(a);
        ensure(b);
        if (a.getUniqueId().equals(b.getUniqueId())) {
            sendMsg(a, "battle-self");
            return;
        }
        if (battles.containsKey(a.getUniqueId()) || battles.containsKey(b.getUniqueId())
                || challengeBusy.contains(a.getUniqueId()) || challengeBusy.contains(b.getUniqueId())) {
            sendMsg(a, "battle-in-battle");
            return;
        }
        double la = getLength(a);
        double lb = getLength(b);
        if (la < minChallengeLength || lb < minChallengeLength) {
            sendMsg(a, "battle-too-small", "min", fmtLen(minChallengeLength));
            sendMsg(a, "battle-too-small-hint");
            return;
        }
        // 战斗无冷却，不管输赢都不进入冷却
        int sa = getStamina(a);
        int sb = getStamina(b);
        if (sa < battleCost || sb < battleCost) {
            if (sa < battleCost) {
                sendMsg(a, "battle-no-stamina-self", "cost", String.valueOf(battleCost), "stamina", String.valueOf(sa));
            }
            if (sb < battleCost) {
                sendMsg(a, "battle-target-no-stamina", "target", b.getName(), "stamina", String.valueOf(sb));
                sendMsg(b, "battle-target-notify", "attacker", a.getName(), "stamina", String.valueOf(sb));
            }
            return;
        }
        // 发起挑战，等待对方接受
        Challenge ch = new Challenge();
        ch.from = a.getUniqueId();
        ch.target = b.getUniqueId();
        ch.fromName = a.getName();
        ch.targetName = b.getName();
        challenges.put(a.getUniqueId(), ch);
        challengeBusy.add(a.getUniqueId());
        challengeBusy.add(b.getUniqueId());
        sendMsg(a, "challenge-sent", "target", b.getName());
        sendChallengePrompt(b, ch);
        ch.timeoutTask = getServer().getScheduler().runTaskLater(this, () -> {
            if (ch.done) {
                return;
            }
            ch.done = true;
            challenges.remove(ch.from);
            challengeBusy.remove(ch.from);
            challengeBusy.remove(ch.target);
            Player x = Bukkit.getPlayer(ch.from);
            Player y = Bukkit.getPlayer(ch.target);
            if (x != null) {
                sendMsg(x, "challenge-timeout", "target", ch.targetName);
            }
            if (y != null) {
                sendMsg(y, "challenge-timeout-self", "from", ch.fromName);
            }
        }, battlePickTimeout * 20L).getTaskId();
    }

    private Challenge findChallengeFor(Player p) {
        for (Challenge c : challenges.values()) {
            if (c.target.equals(p.getUniqueId())) {
                return c;
            }
        }
        return null;
    }

    private void sendChallengePrompt(Player b, Challenge ch) {
        Component line = Component.text(color(prefix() + t("challenge-request", "from", ch.fromName, "timeout", String.valueOf(battlePickTimeout))));
        Component accept = Component.text(color("&a [✔ 接受] "))
                .clickEvent(ClickEvent.runCommand("/niu accept"))
                .hoverEvent(HoverEvent.showText(Component.text(color("&a点击接受挑战"))));
        Component decline = Component.text(color("&c[✘ 拒绝]"))
                .clickEvent(ClickEvent.runCommand("/niu decline"))
                .hoverEvent(HoverEvent.showText(Component.text(color("&c点击拒绝挑战"))));
        b.sendMessage(line.append(accept).append(decline));
    }

    private void handleChallengeCommand(Player b, boolean accept) {
        Challenge ch = findChallengeFor(b);
        if (ch == null || ch.done) {
            sendMsg(b, "challenge-none");
            return;
        }
        if (accept) {
            acceptChallenge(b, ch);
        } else {
            rejectChallenge(b, ch);
        }
    }

    private void rejectChallenge(Player b, Challenge ch) {
        if (ch.done) {
            return;
        }
        ch.done = true;
        if (ch.timeoutTask != -1) {
            getServer().getScheduler().cancelTask(ch.timeoutTask);
        }
        challenges.remove(ch.from);
        challengeBusy.remove(ch.from);
        challengeBusy.remove(ch.target);
        Player a = Bukkit.getPlayer(ch.from);
        if (a != null) {
            sendMsg(a, "challenge-rejected", "target", ch.targetName);
        }
    }

    private void acceptChallenge(Player b, Challenge ch) {
        if (ch.done) {
            return;
        }
        ch.done = true;
        if (ch.timeoutTask != -1) {
            getServer().getScheduler().cancelTask(ch.timeoutTask);
        }
        challenges.remove(ch.from);
        challengeBusy.remove(ch.from);
        challengeBusy.remove(ch.target);
        Player a = Bukkit.getPlayer(ch.from);
        if (a != null) {
            startBattle(a, b);
        } else {
            sendMsg(b, "challenge-cancelled");
        }
    }

    private void startBattle(Player a, Player b) {
        // 开战时重新校验体力（挑战发出后体力可能被消耗），不足则取消
        if (getStamina(a) < battleCost || getStamina(b) < battleCost) {
            if (getStamina(a) < battleCost) {
                sendMsg(a, "battle-no-stamina-self", "cost", String.valueOf(battleCost), "stamina", String.valueOf(getStamina(a)));
            }
            if (getStamina(b) < battleCost) {
                sendMsg(a, "battle-target-no-stamina", "target", b.getName(), "stamina", String.valueOf(getStamina(b)));
                sendMsg(b, "battle-target-notify", "attacker", a.getName(), "stamina", String.valueOf(getStamina(b)));
            }
            return;
        }
        spendStamina(a, battleCost);
        spendStamina(b, battleCost);
        data.set(base(a) + ".battles", getBattles(a) + 1);
        data.set(base(b) + ".battles", getBattles(b) + 1);

        Battle bt = new Battle();
        bt.a = a.getUniqueId();
        bt.b = b.getUniqueId();
        bt.nameA = a.getName();
        bt.nameB = b.getName();
        int ca = getCP(a);
        int cb = getCP(b);
        bt.maxA = bt.hpA = maxHp(ca);
        bt.maxB = bt.hpB = maxHp(cb);
        bt.rageA = rageStart;
        bt.rageB = rageStart;
        bt.turn = 0;
        battles.put(a.getUniqueId(), bt);
        battles.put(b.getUniqueId(), bt);

        broadcast("battle-vs", "a", a.getName(), "la", fmtLen(getLength(a)), "ca", String.valueOf(ca), "b", b.getName(), "lb", fmtLen(getLength(b)), "cb", String.valueOf(cb));
        prompt(bt, a);
    }

    // ===== 回合制对战 =====
    private int rankIndex(int cp) {
        if (cp < 50) return 0;
        if (cp < 150) return 1;
        if (cp < 400) return 2;
        if (cp < 1000) return 3;
        if (cp < 2500) return 4;
        return 5;
    }

    private int maxHp(int cp) {
        return hpBase + rankIndex(cp) * hpPerRank;
    }

    private int skillDamage(int skill, int cp) {
        if (skill == SKILL_TAIL) {
            return skillTailDamageBase + (int) Math.round(cp * skillTailDamagePerCp);
        }
        if (skill == SKILL_HORN) {
            return skillHornDamageBase + (int) Math.round(cp * skillHornDamagePerCp);
        }
        return 0;
    }

    private int rageCost(int skill) {
        if (skill == SKILL_TAIL) return skillTailCost;
        if (skill == SKILL_HORN) return skillHornCost;
        return 0;
    }

    private String skillName(int skill) {
        if (skill == SKILL_TAIL) return "猛烈甩击";
        if (skill == SKILL_HORN) return "暴力筹茬";
        return "牛盾";
    }

    private int parseSkill(String s) {
        s = s.trim();
        switch (s) {
            case "1": return SKILL_TAIL;
            case "2": return SKILL_HORN;
            case "3": return SKILL_SHIELD;
            default:
                if (s.contains("甩")) return SKILL_TAIL;
                if (s.contains("筹") || s.contains("茬")) return SKILL_HORN;
                if (s.contains("盾")) return SKILL_SHIELD;
                return -1;
        }
    }

    private void prompt(Battle bt, Player p) {
        if (bt.timeoutTask != -1) {
            getServer().getScheduler().cancelTask(bt.timeoutTask);
            bt.timeoutTask = -1;
        }
        sendMsg(p, "battle-hp", "a", bt.nameA, "hpA", String.valueOf(bt.hpA), "maxA", String.valueOf(bt.maxA), "b", bt.nameB, "hpB", String.valueOf(bt.hpB), "maxB", String.valueOf(bt.maxB), "rageA", String.valueOf(bt.rageA), "rageB", String.valueOf(bt.rageB));
        sendSkillPrompt(bt, p);
        UUID who = bt.turn == 0 ? bt.a : bt.b;
        int gen = ++bt.pickGen;
        bt.timeoutTask = getServer().getScheduler().runTaskLater(this, () -> {
            if (bt.over || bt.pickGen != gen) {
                return;
            }
            bt.timeoutTask = -1;
            Player loser = Bukkit.getPlayer(who);
            Player winner = Bukkit.getPlayer(who.equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                broadcast("battle-timeout-forfeit", "loser", loser != null ? loser.getName() : (who.equals(bt.a) ? bt.nameA : bt.nameB), "winner", winner.getName());
                endBattle(bt, winner, loser);
            } else {
                endBattle(bt, null, null);
            }
        }, battlePickTimeout * 20L).getTaskId();
    }

    private void sendSkillPrompt(Battle bt, Player p) {
        int myRage = p.getUniqueId().equals(bt.a) ? bt.rageA : bt.rageB;
        Component line = Component.text(color(prefix() + t("battle-pick", "rage", String.valueOf(myRage), "max", String.valueOf(rageMax))));
        Component b1 = Component.text(color(" &e[1 猛烈甩击]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 1"))
                .hoverEvent(HoverEvent.showText(Component.text(color("&e强力攻击 · 耗怒 " + skillTailCost))));
        Component b2 = Component.text(color(" &6[2 暴力筹茬]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 2"))
                .hoverEvent(HoverEvent.showText(Component.text(color("&6稳定攻击 · 耗怒 " + skillHornCost))));
        Component b3 = Component.text(color(" &a[3 牛盾]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 3"))
                .hoverEvent(HoverEvent.showText(Component.text(color("&a格挡伤害 · 回怒 " + skillShieldRage))));
        p.sendMessage(line.append(b1).append(b2).append(b3));
    }

    private void handlePick(Player p, String input) {
        Battle bt = battles.get(p.getUniqueId());
        if (bt == null || bt.over) {
            return;
        }
        int choice = parseSkill(input);
        if (choice == -1) {
            sendMsg(p, "battle-pick-invalid");
            sendSkillPrompt(bt, p);
            return;
        }
        boolean isA = p.getUniqueId().equals(bt.a);
        int cost = rageCost(choice);
        int myRage = isA ? bt.rageA : bt.rageB;
        if (cost > myRage) {
            sendMsg(p, "battle-no-rage", "rage", String.valueOf(myRage), "max", String.valueOf(rageMax), "cost", String.valueOf(cost));
            sendSkillPrompt(bt, p);
            return;
        }
        if (isA && bt.turn == 0) {
            bt.choiceA = choice;
            bt.turn = 1;
            sendMsg(p, "battle-wait", "skill", skillName(choice));
            Player b = Bukkit.getPlayer(bt.b);
            if (b != null) {
                prompt(bt, b);
            } else {
                broadcast("battle-forfeit", "loser", bt.nameB, "winner", bt.nameA);
                endBattle(bt, p, null);
            }
        } else if (!isA && bt.turn == 1) {
            bt.choiceB = choice;
            sendMsg(p, "battle-wait", "skill", skillName(choice));
            resolveTurn(bt);
        } else {
            sendMsg(p, "battle-not-your-turn");
        }
    }

    private void resolveTurn(Battle bt) {
        Player a = Bukkit.getPlayer(bt.a);
        Player b = Bukkit.getPlayer(bt.b);
        int ca = a != null ? getCP(a) : 0;
        int cb = b != null ? getCP(b) : 0;
        boolean shieldA = bt.choiceA == SKILL_SHIELD;
        boolean shieldB = bt.choiceB == SKILL_SHIELD;

        // 判定谁出招命中
        int dmgToB = 0;
        int dmgToA = 0;
        boolean nullifyA = false; // A 的攻击被打断
        boolean nullifyB = false;
        if (shieldA && shieldB) {
            // 双双牛盾：都不掉血
        } else if (shieldA) {
            // A 牛盾，格挡 B 的攻击
            nullifyB = false; // B 出招但被格挡
            dmgToA = 0;
        } else if (shieldB) {
            nullifyA = false;
            dmgToB = 0;
        } else if (bt.choiceA == bt.choiceB) {
            // 相同技能：互相对撞，双方都命中
            dmgToB = skillDamage(bt.choiceA, ca);
            dmgToA = skillDamage(bt.choiceB, cb);
        } else if (bt.choiceA == SKILL_TAIL) {
            // A 猛烈甩击 打断 B 暴力筹茬
            dmgToB = skillDamage(bt.choiceA, ca);
            nullifyB = true;
        } else {
            // B 猛烈甩击 打断 A 暴力筹茬
            dmgToA = skillDamage(bt.choiceB, cb);
            nullifyA = true;
        }

        // 怒气结算：牛盾回怒，攻击耗怒（被打断的攻击怒气不返还）
        if (shieldA) {
            bt.rageA = Math.min(rageMax, bt.rageA + skillShieldRage);
        } else {
            bt.rageA -= rageCost(bt.choiceA);
        }
        if (shieldB) {
            bt.rageB = Math.min(rageMax, bt.rageB + skillShieldRage);
        } else {
            bt.rageB -= rageCost(bt.choiceB);
        }

        // 血量结算
        bt.hpB = Math.max(0, bt.hpB - dmgToB);
        bt.hpA = Math.max(0, bt.hpA - dmgToA);

        // 回合结束回怒
        bt.rageA = Math.min(rageMax, bt.rageA + rageRegen);
        bt.rageB = Math.min(rageMax, bt.rageB + rageRegen);

        // 双方都能看到双方的出招与结果
        String bothPicked = color(prefix() + t("battle-both-picked"));
        if (a != null) {
            a.sendMessage(bothPicked);
        }
        if (b != null) {
            b.sendMessage(bothPicked);
        }
        String actA = buildAction(bt.nameA, bt.choiceA, bt.nameB, dmgToB, nullifyA, shieldA, shieldB, bt.choiceB);
        String actB = buildAction(bt.nameB, bt.choiceB, bt.nameA, dmgToA, nullifyB, shieldB, shieldA, bt.choiceA);
        if (a != null) {
            a.sendMessage(color(prefix() + actA));
            a.sendMessage(color(prefix() + actB));
        }
        if (b != null) {
            b.sendMessage(color(prefix() + actA));
            b.sendMessage(color(prefix() + actB));
        }
        String hpLine = color(prefix() + t("battle-hp", "a", bt.nameA, "hpA", String.valueOf(bt.hpA), "maxA", String.valueOf(bt.maxA), "b", bt.nameB, "hpB", String.valueOf(bt.hpB), "maxB", String.valueOf(bt.maxB), "rageA", String.valueOf(bt.rageA), "rageB", String.valueOf(bt.rageB)));
        if (a != null) {
            a.sendMessage(hpLine);
        }
        if (b != null) {
            b.sendMessage(hpLine);
        }

        boolean aDead = bt.hpA <= 0;
        boolean bDead = bt.hpB <= 0;
        if (aDead && bDead) {
            Player w = bt.hpA >= bt.hpB ? a : b;
            endBattle(bt, w, w == a ? b : a);
        } else if (bDead) {
            endBattle(bt, a, b);
        } else if (aDead) {
            endBattle(bt, b, a);
        } else {
            bt.turn = 0;
            bt.choiceA = -1;
            bt.choiceB = -1;
            if (a != null) {
                prompt(bt, a);
            } else {
                endBattle(bt, null, null);
            }
        }
    }

    private String buildAction(String player, int skill, String target, int damage, boolean nullified, boolean shielded, boolean targetShielded, int opponentSkill) {
        if (shielded) {
            return t("battle-skill-shield", "player", player, "rage", String.valueOf(skillShieldRage));
        }
        if (targetShielded) {
            return t("battle-skill-blocked", "player", player, "skill", skillName(skill), "target", target);
        }
        if (nullified) {
            return t("battle-skill-clash", "player", player, "skill", skillName(skill), "target", target, "skill2", skillName(opponentSkill));
        }
        return t("battle-skill-hit", "player", player, "skill", skillName(skill), "target", target, "damage", String.valueOf(damage));
    }

    private void endBattle(Battle bt, Player winner, Player loser) {
        if (bt.over) {
            return;
        }
        bt.over = true;
        if (bt.timeoutTask != -1) {
            getServer().getScheduler().cancelTask(bt.timeoutTask);
            bt.timeoutTask = -1;
        }
        battles.remove(bt.a);
        battles.remove(bt.b);
        if (winner == null || loser == null) {
            broadcast("battle-cancelled");
            return;
        }
        double wOld = getLength(winner);
        double lOld = getLength(loser);
        int grow = winGrowthMin + ThreadLocalRandom.current().nextInt(winGrowthMax - winGrowthMin + 1);
        int shrink = loseLossMin + ThreadLocalRandom.current().nextInt(loseLossMax - loseLossMin + 1);
        double wNew = Math.round((wOld + grow) * 10.0) / 10.0;
        double lNew = Math.max(minLength, Math.round((lOld - shrink) * 10.0) / 10.0);
        data.set(base(winner) + ".length", wNew);
        data.set(base(loser) + ".length", lNew);
        data.set(base(winner) + ".wins", getWins(winner) + 1);
        updateSeasonPeak(winner, wNew);
        markDirty();
        broadcast("battle-win", "winner", winner.getName(), "loser", loser.getName());
        broadcast("battle-grow", "winner", winner.getName(), "old", fmtLen(wOld), "new", fmtLen(wNew), "grow", String.valueOf(grow));
        broadcast("battle-shrink", "loser", loser.getName(), "old", fmtLen(lOld), "new", fmtLen(lNew), "loss", String.valueOf((int) Math.round((lOld - lNew))));
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1.2f);
        if (loser.isOnline()) {
            loser.playSound(loser.getLocation(), Sound.ENTITY_COW_HURT, 1, 0.6f);
        }
    }

    private void doBuy(Player p) {
        ensure(p);
        int s = getStamina(p);
        if (s >= maxStamina) {
            sendMsg(p, "buy-full");
            return;
        }
        String b = base(p);
        String today = LocalDate.now().toString();
        if (!today.equals(data.getString(b + ".buyDate", ""))) {
            data.set(b + ".buyDate", today);
            data.set(b + ".buysToday", 0);
        }
        int bought = data.getInt(b + ".buysToday", 0);
        if (bought >= buyLimitPerDay) {
            sendMsg(p, "buy-limit", "limit", String.valueOf(buyLimitPerDay));
            sendMsg(p, "buy-limit-hint", "bought", String.valueOf(bought));
            return;
        }
        if (economy == null) {
            sendMsg(p, "buy-no-economy");
            return;
        }
        if (!economy.has(p, buyPrice)) {
            sendMsg(p, "buy-no-money", "price", String.valueOf(buyPrice));
            return;
        }
        economy.withdrawPlayer(p, buyPrice);
        int ns = Math.min(maxStamina, s + buyAmount);
        data.set(base(p) + ".stamina", ns);
        data.set(b + ".buysToday", bought + 1);
        markDirty();
        sendMsg(p, "buy-success", "amount", String.valueOf(buyAmount), "stamina", String.valueOf(ns), "max", String.valueOf(maxStamina), "bought", String.valueOf(bought + 1), "limit", String.valueOf(buyLimitPerDay));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1.5f);
    }

    // ===== GUI（文案写死在插件）=====
    private void openMain(Player p) {
        ensure(p);
        Inventory inv = Bukkit.createInventory(null, 27, color("&6&l牛牛对战系统"));
        renderMain(p, inv);
        p.openInventory(inv);
    }

    // 关闭按钮：返回服务器主菜单（PlayerMenu）
    private void openMainMenu(Player p) {
        if (Bukkit.getPluginManager().getPlugin("PlayerMenu") != null) {
            try {
                cn.handyplus.menu.util.MenuUtil.asyncOpenGui(p, "menu", null);
                return;
            } catch (Throwable ignored) {
            }
        }
        p.performCommand("cd");
    }

    private void renderMain(Player p, Inventory inv) {
        inv.clear();
        for (int i = 0; i < 27; i++) {
            if (i == 4 || i == 11 || i == 13 || i == 15 || i == 22 || i == 26) {
                continue;
            }
            inv.setItem(i, glass());
        }
        inv.setItem(4, infoIcon(p));
        int stam = getStamina(p);
        boolean cd = inCooldown(p);
        String cdLine = cd ? ("&c牛牛闹脾气中（" + statusSec(p) + "秒）") : "&a可以操作";

        List<String> bl = new ArrayList<>();
        bl.add("");
        bl.add(color("&f挑战其他玩家的牛牛，一决高下"));
        bl.add("");
        bl.add(color("&7对战规则："));
        bl.add(color("&7▶ 双方各消耗 &e" + battleCost + " &7点体力"));
        bl.add(color("&7▶ 长度越长，胜率越高"));
        bl.add(color("&7▶ 赢家牛牛 &a+" + winGrowthMin + "~" + winGrowthMax + "cm"));
        bl.add(color("&7▶ 输家牛牛 &c-" + loseLossMin + "~" + loseLossMax + "cm&7（最低 " + fmtLen(minLength) + "cm）"));
        bl.add("");
        bl.add(color("&7当前体力：" + (stam >= battleCost ? "&a" + stam : "&c" + stam) + "&7/&e" + maxStamina));
        bl.add(color("&7状态：" + cdLine));
        bl.add("");
        bl.add(color("&7左键进入选人"));
        bl.add(color("&7也可以输入 &e/niuniu battle <玩家>&7 指定对手"));
        inv.setItem(11, btn(Material.IRON_SWORD, "&c&l对战", "battle", bl));

        List<String> rl = new ArrayList<>();
        rl.add("");
        rl.add(color("&f给牛牛打胶，让它长大"));
        rl.add("");
        rl.add(color("&7打胶规则："));
        rl.add(color("&7▶ 消耗 &e" + brushCost + " &7点体力"));
        rl.add(color("&7▶ 每次打胶必成功 &a+" + brushGrowthMin + "~" + brushGrowthMax + "cm"));
        rl.add(color("&7▶ 打一次进入 &c" + brushCooldown + " &7秒冷却"));
        rl.add(color("&7  （冷却期间不能打胶）"));
        rl.add("");
        rl.add(color("&7当前体力：" + (stam >= brushCost ? "&a" + stam : "&c" + stam) + "&7/&e" + maxStamina));
        rl.add(color("&7状态：" + cdLine));
        rl.add("");
        rl.add(color("&7左键打胶"));
        inv.setItem(13, btn(Material.END_ROD, "&b&l打胶", "brush", rl));

        List<String> tl = new ArrayList<>();
        tl.add("");
        tl.add(color("&f看看本赛季谁的牛牛最高"));
        tl.add("");
        tl.add(color("&7按赛季最高长度(cm)排名"));
        tl.add(color("&7每赛季结束自动重置"));
        tl.add("");
        tl.add(color("&7左键查看"));
        inv.setItem(15, btn(Material.EMERALD, "&e&l排行榜", "top", tl));

        List<String> gl = new ArrayList<>();
        gl.add("");
        gl.add(color("&f花金币换体力，继续战斗"));
        gl.add("");
        gl.add(color("&7价格：&6" + buyPrice + "$ &7= &e+" + buyAmount + " &7体力"));
        gl.add(color("&7体力上限：&e" + maxStamina));
        gl.add(color("&7每日限购：&e" + buyLimitPerDay + " &7次"));
        gl.add("");
        gl.add(color("&7当前体力：" + (stam >= maxStamina ? "&a" + stam + "&7（已满）" : "&a" + stam + "&7/&e" + maxStamina)));
        gl.add("");
        gl.add(color("&7左键购买"));
        inv.setItem(22, btn(Material.GOLD_INGOT, "&6&l购买体力", "buy", gl));

        List<String> closeL = new ArrayList<>();
        closeL.add("");
        closeL.add(color("&7返回服务器主菜单"));
        inv.setItem(26, btn(Material.BARRIER, "&c&l返回主菜单", "close", closeL));
    }

    private void openBattle(Player p) {
        ensure(p);
        List<Player> targets = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (!t.getUniqueId().equals(p.getUniqueId())) {
                targets.add(t);
            }
        }
        Inventory inv = Bukkit.createInventory(null, 45, color("&c&l牛牛对战 - 选择对手"));
        for (int i = 0; i < 45; i++) {
            if (i == 40) {
                continue;
            }
            inv.setItem(i, glass());
        }
        int slot = 10;
        for (Player t : targets) {
            if (slot >= 40) {
                break;
            }
            double tLen = getLength(t);
            if (tLen < minChallengeLength) {
                continue;
            }
            ItemStack head = headOf(t);
            ItemMeta m = head.getItemMeta();
            int tcp = getCP(t);
            String display = "&7[" + getRank(tcp) + "&7] &e" + t.getName();
            m.setDisplayName(color(display));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(color("&b长度：&e" + fmtLen(tLen) + "cm"));
            lore.add(color("&b赛季最高：&6" + fmtLen(getSeasonPeak(t)) + "cm"));
            lore.add(color("&b战斗力：&e" + tcp + " &7（" + getRank(tcp) + "&7）"));
            lore.add(color("&bHP：&c" + maxHp(tcp)));
            lore.add(color("&b体力：&a" + getStamina(t) + "&7/&e" + maxStamina));
            int twins = getWins(t);
            int tbattles = getBattles(t);
            String wr = tbattles > 0 ? (int) Math.round(100.0 * twins / tbattles) + "%" : "—";
            lore.add(color("&b战绩：&e" + twins + " &7胜 · 胜率 &e" + wr));
            lore.add("");
            int winChance = (int) Math.round(100.0 * getCP(p) / (getCP(p) + tcp));
            lore.add(color("&7你的胜率约 &e" + winChance + "%"));
            if (inCooldown(t)) {
                lore.add("");
                lore.add(color("&c对方牛牛正在闹脾气"));
            } else if (getStamina(t) < battleCost) {
                lore.add("");
                lore.add(color("&c对方体力不足，无法应战"));
            }
            lore.add("");
            lore.add(color("&7左键挑战 · 双方各耗 &e" + battleCost + " &7体力"));
            m.setLore(lore);
            setAction(m, "challenge:" + t.getName());
            head.setItemMeta(m);
            inv.setItem(slot++, head);
        }
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(color("&7返回主菜单"));
        inv.setItem(40, btn(Material.ARROW, "&c&l返回", "back", backL));
        p.openInventory(inv);
    }

    private void openTop(Player p) {
        openTop(p, 1);
    }

    // 赛季排行榜：前 20 名，每页 10 名自动分页；点击头像查看该玩家信息
    private void openTop(Player p, int page) {
        Map<String, Double> peaks = new LinkedHashMap<>();
        if (data.contains("players")) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                double peak = data.getDouble("players." + key + ".seasonPeak", 0);
                if (peak <= 0) {
                    peak = data.getDouble("players." + key + ".length", 0);
                }
                peaks.put(key, peak);
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(peaks.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        List<Map.Entry<String, Double>> top20 = sorted.size() > 20 ? sorted.subList(0, 20) : sorted;
        int perPage = 10;
        int totalPages = Math.max(1, (int) Math.ceil(top20.size() / (double) perPage));
        page = Math.max(1, Math.min(page, totalPages));
        int start = (page - 1) * perPage;

        Inventory inv = Bukkit.createInventory(null, 45,
                color("&e&lS" + getSeasonCurrent() + " 赛季 · 牛牛长度榜 &8(" + page + "/" + totalPages + ")"));
        for (int i = 0; i < 45; i++) {
            // 条目槽位 = 10,13,16,...,34（(i-10)%3==0），其余全部填玻璃，不留空气
            if (i == 40 || i == 38 || i == 42 || (i >= 10 && i <= 34 && (i - 10) % 3 == 0)) {
                continue;
            }
            inv.setItem(i, glass());
        }
        int slot = 10;
        for (int i = start; i < Math.min(start + perPage, top20.size()); i++) {
            if (slot > 34) {
                break;
            }
            Map.Entry<String, Double> e = top20.get(i);
            ItemStack head = headByUuid(e.getKey());
            ItemMeta m = head.getItemMeta();
            String name = "?";
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(e.getKey()));
                name = op.getName() == null ? "?" : op.getName();
            } catch (Exception ignored) {
            }
            String medal = i == 0 ? "&6&l①" : (i == 1 ? "&7&l②" : (i == 2 ? "&c&l③" : "&f&l" + (i + 1)));
            m.setDisplayName(color(medal + " &e" + name));
            double cur = data.getDouble("players." + e.getKey() + ".length", 0);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(color("&b赛季最高：&6" + fmtLen(e.getValue()) + "cm"));
            lore.add(color("&7当前长度：&e" + fmtLen(cur) + "cm"));
            lore.add("");
            lore.add(color("&7排名第 &e" + (i + 1) + " &7名"));
            lore.add(color("&7点击头像查看玩家信息"));
            m.setLore(lore);
            setAction(m, "topinfo:" + e.getKey() + ":" + page);
            head.setItemMeta(m);
            inv.setItem(slot, head);
            slot += 3;
        }
        // 本页条目不足时，未用的条目槽位补玻璃，不留空气
        while (slot <= 34) {
            inv.setItem(slot, glass());
            slot += 3;
        }
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(color("&7返回主菜单"));
        inv.setItem(40, btn(Material.ARROW, "&c&l返回", "back", backL));
        if (page > 1) {
            List<String> prevL = new ArrayList<>();
            prevL.add("");
            prevL.add(color("&7上一页"));
            inv.setItem(38, btn(Material.ARROW, "&e&l上一页", "toppage:" + (page - 1), prevL));
        } else {
            inv.setItem(38, glass());
        }
        if (page < totalPages) {
            List<String> nextL = new ArrayList<>();
            nextL.add("");
            nextL.add(color("&7下一页"));
            inv.setItem(42, btn(Material.ARROW, "&e&l下一页", "toppage:" + (page + 1), nextL));
        } else {
            inv.setItem(42, glass());
        }
        p.openInventory(inv);
    }

    // 查看某玩家（可离线）的牛牛个人信息
    private void openPlayerInfo(Player viewer, String targetUuid, int backPage) {
        ItemStack head = headByUuid(targetUuid);
        ItemMeta m = head.getItemMeta();
        String name = "?";
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(targetUuid));
            name = op.getName() == null ? "?" : op.getName();
        } catch (Exception ignored) {
        }
        String b = "players." + targetUuid;
        double len = data.getDouble(b + ".length", 0);
        double peak = data.getDouble(b + ".seasonPeak", len);
        int stam = data.getInt(b + ".stamina", 0);
        int wins = data.getInt(b + ".wins", 0);
        int battles = data.getInt(b + ".battles", 0);
        long cd = data.getLong(b + ".cooldown", 0);
        boolean hasData = data.contains(b);
        int cp = getCP(len);

        m.setDisplayName(color("&6&l" + name + " &e的牛牛"));
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (!hasData) {
            lore.add(color("&7这位玩家还没有开始养牛"));
        } else {
            lore.add(color("&b当前长度：&e" + fmtLen(len) + " &7cm"));
            lore.add(color("&b赛季最高：&6" + fmtLen(peak) + " &7cm"));
            lore.add(color("&b战斗力：&e" + cp + " &7（" + getRank(cp) + "&7）"));
            lore.add(color("&b最大HP：&c" + maxHp(cp)));
            lore.add(color("&b体力：" + (stam > 0 ? "&a" + stam : "&c" + stam) + "&7/&e" + maxStamina));
            String wr = battles > 0 ? (int) Math.round(100.0 * wins / battles) + "%" : "—";
            lore.add(color("&b战绩：&e" + wins + " &7胜 · 共 &e" + battles + " &7场 · 胜率 &e" + wr));
            lore.add(color("&7当前赛季：&eS" + getSeasonCurrent()));
            lore.add("");
            long cdLeft = Math.max(0, cd - System.currentTimeMillis());
            if (cdLeft > 0) {
                lore.add(color("&c牛牛正在闹脾气（还剩 &c" + (cdLeft / 1000 + 1) + " &7秒）"));
            } else {
                lore.add(color("&7牛牛心情很好 ❇"));
            }
        }
        m.setLore(lore);
        head.setItemMeta(m);

        Inventory inv = Bukkit.createInventory(null, 27, color("&e&l牛牛信息 - " + name));
        for (int i = 0; i < 27; i++) {
            if (i == 13) {
                continue;
            }
            inv.setItem(i, glass());
        }
        inv.setItem(13, head);
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(color("&7返回排行榜第 " + backPage + " 页"));
        inv.setItem(22, btn(Material.ARROW, "&c&l返回", "toppage:" + backPage, backL));
        viewer.openInventory(inv);
    }

    private ItemStack infoIcon(Player p) {
        ItemStack head = headOf(p);
        ItemMeta m = head.getItemMeta();
        m.setDisplayName(color("&6&l你的牛牛 &e❤"));
        double len = getLength(p);
        int stam = getStamina(p);
        int wins = getWins(p);
        int battles = getBattles(p);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(color("&b当前长度：&e" + fmtLen(len) + " &7cm"));
        lore.add(color("&b赛季最高：&6" + fmtLen(getSeasonPeak(p)) + " &7cm"));
        int cp = getCP(p);
        lore.add(color("&b战斗力：&e" + cp + " &7（" + getRank(cp) + "&7）"));
        lore.add(color("&b最大HP：&c" + maxHp(cp)));
        lore.add(color("&b体力：" + (stam > 0 ? "&a" + stam : "&c" + stam) + "&7/&e" + maxStamina));
        lore.add(color("&b战绩：&e" + wins + " &7胜 · 共战斗 &e" + battles + " &7场"));
        lore.add(color("&7当前赛季：&eS" + getSeasonCurrent() + "&7（每赛季 &e" + seasonDurationDays + " &7天）"));
        lore.add("");
        if (inCooldown(p)) {
            lore.add(color("&c牛牛正在闹脾气（还剩 &c" + statusSec(p) + " &7秒）"));
            lore.add(color("&7期间不能打胶"));
        } else {
            lore.add(color("&7牛牛心情很好 ❇"));
        }
        lore.add("");
        lore.add(color("&7玩法速览："));
        lore.add(color("&7▶ 每天 &e" + dailyStamina + " &7点体力，次日自动回满"));
        lore.add(color("&7▶ 对战耗 &e" + battleCost + "&7：赢 &a+" + winGrowthMin + "~" + winGrowthMax + "cm，输 &c-" + loseLossMin + "~" + loseLossMax + "cm"));
        lore.add(color("&7▶ 打胶耗 &e" + brushCost + "&7：必成功 &a+" + brushGrowthMin + "~" + brushGrowthMax + "cm"));
        lore.add(color("&7▶ 打一次后进入 &c" + brushCooldown + "&7 秒冷却"));
        lore.add(color("&7▶ 体力不够：&6/niu buy&7 花 &6" + buyPrice + "$&7 买 &e+" + buyAmount + "&7（每天限 &e" + buyLimitPerDay + " &7次）"));
        lore.add(color("&7▶ 牛牛低于 &e" + fmtLen(minChallengeLength) + "cm&7 不能对战，先打胶养大"));
        lore.add("");
        lore.add(color("&7点击下方按钮开始你的牛牛之旅！"));
        m.setLore(lore);
        head.setItemMeta(m);
        return head;
    }

    private ItemStack glass() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(" ");
        it.setItemMeta(m);
        return it;
    }

    private ItemStack btn(Material mat, String name, String action, List<String> lore) {
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(color(name));
        m.setLore(lore);
        setAction(m, action);
        it.setItemMeta(m);
        return it;
    }

    private void setAction(ItemMeta m, String action) {
        m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
    }

    private String getAction(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return null;
        }
        return it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private int safePage(String s) {
        try {
            return Math.max(1, Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return 1;
        }
    }

    private ItemStack headOf(Player p) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setPlayerProfile(p.getPlayerProfile());
        it.setItemMeta(sm);
        return it;
    }

    private ItemStack headByUuid(String uuid) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD, 1);
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.fromString(uuid));
            it = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta sm = (SkullMeta) it.getItemMeta();
            sm.setPlayerProfile(profile);
            it.setItemMeta(sm);
        } catch (Exception ignored) {
        }
        return it;
    }
    // ===== MySQL 数据同步 =====

    private void loadFromMysql() {
        // 加载赛季数据
        Map<String, String> seasonData = db.loadSeason();
        for (Map.Entry<String, String> entry : seasonData.entrySet()) {
            data.set("season." + entry.getKey(), entry.getValue());
        }

        // 加载玩家数据
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

    private void syncToMysql() {
        // 同步赛季数据
        if (data.contains("season")) {
            for (String key : data.getConfigurationSection("season").getKeys(false)) {
                db.saveSeason(key, data.getString("season." + key, ""));
            }
        }

        // 同步玩家数据
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

    private int countPlayers() {
        if (data.contains("players")) {
            return data.getConfigurationSection("players").getKeys(false).size();
        }
        return 0;
    }

    private void doMigrate(CommandSender sender) {
        if (!useMysql || db == null || !db.isEnabled()) {
            sender.sendMessage(color(prefix() + t("migrate-no-db")));
            return;
        }

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

        int count = db.migrateFromYaml(yamlPlayers, yamlSeason);
        if (count >= 0) {
            sender.sendMessage(color(prefix() + "&a数据迁移完成！共迁移 " + count + " 名玩家数据。"));
        } else {
            sender.sendMessage(color(prefix() + "&c数据迁移失败，请检查数据库连接。"));
        }
    }
    // ===== 命令 =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfig();
                loadData();
                loadLang();
                sender.sendMessage(color(prefix() + t("reload")));
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("migrate")) {
                doMigrate(sender);
                return true;
            }
            sender.sendMessage(color(prefix() + t("console-only")));
            return true;
        }
        if (args.length == 0) {
            openMain(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "battle":
                if (args.length < 2) {
                    sendMsg(p, "battle-usage");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sendMsg(p, "player-offline", "name", args[1]);
                    return true;
                }
                doBattle(p, t);
                return true;
            case "brush":
                doBrush(p);
                return true;
            case "forcebrush":
                // /niu forcebrush：给自己强制打胶；/niu forcebrush <玩家>：给目标强制打胶
                if (!p.hasPermission("niu.admin")) {
                    sendMsg(p, "no-permission");
                    return true;
                }
                Player fTarget = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : p;
                if (fTarget == null) {
                    sendMsg(p, "player-offline", "name", args[1]);
                    return true;
                }
                forceBrush(fTarget);
                sendMsg(p, "brush-forced-admin", "target", fTarget.getName());
                return true;
            case "buy":
                doBuy(p);
                return true;
            case "top":
                openTop(p);
                return true;
            case "accept":
                handleChallengeCommand(p, true);
                return true;
            case "decline":
            case "refuse":
            case "reject":
                handleChallengeCommand(p, false);
                return true;
            case "skill":
                if (args.length < 2) {
                    sendMsg(p, "battle-pick-invalid");
                    return true;
                }
                handlePick(p, args[1]);
                return true;
            case "season":
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    if (p.hasPermission("niu.admin")) {
                        startNewSeason();
                        sendMsg(p, "season-start-admin", "season", String.valueOf(getSeasonCurrent()));
                    } else {
                        sendMsg(p, "no-permission");
                    }
                    return true;
                }
                long daysLeft = Math.max(0, (getSeasonStarted() + seasonDurationDays * 86400000L - System.currentTimeMillis()) / 86400000L);
                sendMsg(p, "season-info", "season", String.valueOf(getSeasonCurrent()), "days", String.valueOf(daysLeft));
                return true;
            case "reload":
                if (p.hasPermission("niu.admin")) {
                    reloadConfig();
                    loadConfig();
                    loadData();
                    loadLang();
                    sendMsg(p, "reload");
                } else {
                    sendMsg(p, "no-permission");
                }
                return true;
            case "migrate":
                if (p.hasPermission("niu.admin")) {
                    doMigrate(p);
                } else {
                    sendMsg(p, "no-permission");
                }
                return true;
            default:
                sendMsg(p, "usage");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"battle", "brush", "forcebrush", "buy", "top", "season", "accept", "decline", "skill", "migrate", "reload"}) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("forcebrush") || args[0].equalsIgnoreCase("battle"))) {
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    out.add(t.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            for (String s : new String[]{"start"}) {
                if (s.startsWith(args[1].toLowerCase())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ===== 事件 =====
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensure(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        Challenge ch = challenges.get(uid);
        if (ch != null && !ch.done) {
            ch.done = true;
            if (ch.timeoutTask != -1) {
                getServer().getScheduler().cancelTask(ch.timeoutTask);
            }
            challenges.remove(ch.from);
            challengeBusy.remove(ch.from);
            challengeBusy.remove(ch.target);
            Player other = Bukkit.getPlayer(ch.from.equals(uid) ? ch.target : ch.from);
            if (other != null) {
                sendMsg(other, "challenge-cancelled");
            }
        }
        Battle bt = battles.get(uid);
        if (bt != null && !bt.over) {
            Player winner = Bukkit.getPlayer(uid.equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                broadcast("battle-forfeit", "loser", e.getPlayer().getName(), "winner", winner.getName());
                endBattle(bt, winner, e.getPlayer());
            } else {
                endBattle(bt, null, null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Battle bt = battles.get(p.getUniqueId());
        if (bt == null || bt.over) {
            return;
        }
        boolean myTurn = (p.getUniqueId().equals(bt.a) && bt.turn == 0)
                || (p.getUniqueId().equals(bt.b) && bt.turn == 1);
        if (!myTurn) {
            return; // 不是你的回合：正常聊天
        }
        String msg = e.getMessage().trim();
        // 仅当整条消息就是技能选择（1/2/3 或技能名，短输入）才拦截解析，否则放行 → 修复聊天劫持
        int choice = parseSkill(msg);
        if (choice == -1 || msg.length() > 6) {
            return; // 正常聊天，不劫持
        }
        e.setCancelled(true);
        final String m = msg;
        getServer().getScheduler().runTask(this, () -> handlePick(p, m));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // 对战中：取消所有玩家对参战者造成的伤害（含对方与第三者，防止他人打断战斗），
        // 环境伤害（岩浆/摔落/怪物等）照常生效 → 既保护战斗又不刷无敌
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof Player) {
            Battle bt = battles.get(victim.getUniqueId());
            if (bt != null && !bt.over) {
                e.setCancelled(true);
            }
        }
    }

    // 对战中死亡：立即判负，避免战斗悬挂
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Battle bt = battles.get(p.getUniqueId());
        if (bt != null && !bt.over) {
            Player winner = Bukkit.getPlayer(p.getUniqueId().equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                broadcast("battle-forfeit", "loser", p.getName(), "winner", winner.getName());
                endBattle(bt, winner, p);
            } else {
                endBattle(bt, null, null);
            }
        }
    }

    // 拖拽防护：牛牛 GUI 内禁止拖动物品，防刷物品
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player) {
            if (e.getView().getTitle().contains("牛牛")) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        String title = e.getView().getTitle();
        if (title.contains("牛牛")) {
            e.setCancelled(true);
            String action = getAction(e.getCurrentItem());
            if (action == null) {
                return;
            }
            p.closeInventory();
            switch (action) {
                case "battle":
                    openBattle(p);
                    break;
                case "brush":
                    doBrush(p);
                    break;
                case "top":
                    openTop(p);
                    break;
                case "buy":
                    doBuy(p);
                    break;
                case "back":
                    // 排行榜/选人页返回主菜单
                    openMain(p);
                    break;
                case "close":
                    // 主菜单关闭按钮：返回服务器主菜单
                    openMainMenu(p);
                    break;
                default:
                    if (action.startsWith("topinfo:")) {
                        String[] parts = action.substring("topinfo:".length()).split(":");
                        if (parts.length >= 1) {
                            int backPage = parts.length > 1 ? safePage(parts[1]) : 1;
                            openPlayerInfo(p, parts[0], backPage);
                        }
                    } else if (action.startsWith("toppage:")) {
                        openTop(p, safePage(action.substring("toppage:".length())));
                    } else if (action.startsWith("challenge:")) {
                        String tname = action.substring("challenge:".length());
                        Player t = Bukkit.getPlayerExact(tname);
                        if (t != null && !t.getUniqueId().equals(p.getUniqueId())) {
                            doBattle(p, t);
                        }
                    }
                    break;
            }
        }
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
