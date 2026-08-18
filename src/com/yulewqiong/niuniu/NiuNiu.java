package com.yulewqiong.niuniu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * NiuNiu 牛牛对战系统 - 主控制器
 * 负责插件生命周期管理、模块初始化与编排。
 */
public class NiuNiu extends JavaPlugin {

    // ===== 核心模块 =====
    private ConfigManager configManager;
    private LangManager langManager;
    private PlayerDataManager playerDataManager;
    private SeasonManager seasonManager;
    private BattleManager battleManager;
    private GuiManager guiManager;
    private CommandHandler commandHandler;
    private EventListener eventListener;

    // ===== 外部依赖 =====
    private Economy economy;
    private DatabaseManager databaseManager;
    private boolean useMysql;

    // ===== 定时任务 =====
    private BukkitTask saveTask;
    private BukkitTask refreshTask;

    // ===== getters =====
    public ConfigManager getConfigManager() { return configManager; }
    public LangManager getLangManager() { return langManager; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public SeasonManager getSeasonManager() { return seasonManager; }
    public BattleManager getBattleManager() { return battleManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public Economy getEconomy() { return economy; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public boolean isUseMysql() { return useMysql; }
    public void setUseMysql(boolean v) { useMysql = v; }
    public void setDatabaseManager(DatabaseManager db) { databaseManager = db; }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1. 初始化配置与语言
        configManager = new ConfigManager(this);
        configManager.load();
        langManager = new LangManager(this);
        langManager.load();

        // 2. 初始化数据层
        playerDataManager = new PlayerDataManager(this, configManager, langManager);
        playerDataManager.loadData();

        // 3. Vault 经济
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        if (economy == null) {
            getLogger().warning("未找到 Vault 经济，购买体力将无法使用。");
        }
        playerDataManager.setEconomy(economy);

        // 4. MySQL 初始化
        useMysql = getConfig().getBoolean("mysql.enabled", false);
        if (useMysql) {
            String mysqlHost = getConfig().getString("mysql.host", "localhost");
            int mysqlPort = getConfig().getInt("mysql.port", 3306);
            String mysqlDatabase = getConfig().getString("mysql.database", "niuniu");
            String mysqlUsername = getConfig().getString("mysql.username", "root");
            String mysqlPassword = getConfig().getString("mysql.password", "");
            databaseManager = new DatabaseManager(this);
            if (databaseManager.connect(mysqlHost, mysqlPort, mysqlDatabase, mysqlUsername, mysqlPassword)) {
                playerDataManager.loadFromMysql(databaseManager);
                getLogger().info("已从 MySQL 加载数据，共 " + playerDataManager.countPlayers() + " 名玩家。");
            } else {
                getLogger().warning("MySQL 连接失败，回退到 YAML 存储。");
                useMysql = false;
            }
        }

        // 5. 初始化业务模块
        seasonManager = new SeasonManager(this, playerDataManager, configManager, langManager);
        battleManager = new BattleManager(this, playerDataManager, configManager, langManager);
        guiManager = new GuiManager(this, playerDataManager, configManager, langManager, seasonManager, battleManager);
        commandHandler = new CommandHandler(this, configManager, langManager, playerDataManager, seasonManager, battleManager, guiManager);
        eventListener = new EventListener(this, playerDataManager, langManager, battleManager, guiManager);

        // 6. 注册事件和命令
        getServer().getPluginManager().registerEvents(eventListener, this);
        getCommand("niu").setExecutor(commandHandler);
        getCommand("niu").setTabCompleter(commandHandler);

        // 7. 赛季检查
        seasonManager.advanceSeasonIfNeeded();

        // 8. 定时保存任务
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            seasonManager.advanceSeasonIfNeeded();
            if (playerDataManager.isDirty()) {
                playerDataManager.saveData();
                playerDataManager.clearDirty();
            }
        }, configManager.saveInterval * 20L, configManager.saveInterval * 20L);

        // 9. 每秒刷新主菜单冷却倒计时
        String mainTitle = ChatColor.stripColor(LangManager.color("&6&l牛牛对战系统"));
        refreshTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getOpenInventory().getTopInventory() == null) continue;
                String t = ChatColor.stripColor(pl.getOpenInventory().getTitle());
                if (t != null && t.contains(mainTitle)) {
                    ItemStack info = pl.getOpenInventory().getTopInventory().getItem(4);
                    if (info != null && info.hasItemMeta()) {
                        String name = info.getItemMeta().getDisplayName();
                        if (name != null && name.contains("牛牛")) {
                            guiManager.renderMain(pl, pl.getOpenInventory().getTopInventory());
                        }
                    }
                }
            }
        }, 20L, 20L);

        getLogger().info("NiuNiu 牛牛对战系统已启用" + (useMysql ? "（MySQL 模式）" : ""));
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (saveTask != null) saveTask.cancel();
        if (playerDataManager != null && playerDataManager.isDirty()) {
            playerDataManager.saveData();
        }
        if (useMysql && databaseManager != null) {
            databaseManager.close();
        }
    }
}