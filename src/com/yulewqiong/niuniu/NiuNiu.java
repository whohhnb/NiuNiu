package com.yulewqiong.niuniu;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

/**
 * NiuNiu 牛牛对战系统 - 主控制器
 * 负责插件生命周期管理、数据库连接、模块初始化与编排。
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

    // ===== 定时任务 =====
    private BukkitTask seasonTask;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1. 配置与语言
        configManager = new ConfigManager(this);
        configManager.load();
        langManager = new LangManager(this);
        langManager.load();

        // 2. 连接数据库（SQLite 默认 / MySQL 可选）；数据库是唯一数据源
        databaseManager = new DatabaseManager(this);
        String sqlitePath = new File(getDataFolder(), configManager.sqliteFile).getAbsolutePath();
        boolean connected = databaseManager.connect(
                configManager.storageType, sqlitePath,
                configManager.mysqlHost, configManager.mysqlPort, configManager.mysqlDatabase,
                configManager.mysqlUsername, configManager.mysqlPassword,
                configManager.tablePrefix, configManager.poolMaxSize, configManager.poolMinIdle);
        if (!connected) {
            getLogger().severe("数据库连接失败，插件无法加载玩家数据，已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Vault 经济
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        if (economy == null) {
            getLogger().warning("未找到 Vault 经济，购买体力将无法使用。");
        }

        // 4. 数据层：全量加载玩家进内存缓存
        playerDataManager = new PlayerDataManager(this, configManager, langManager);
        playerDataManager.setEconomy(economy);
        playerDataManager.loadAll();
        getLogger().info("已从数据库加载 " + playerDataManager.countPlayers() + " 名玩家。");

        // 5. 业务模块
        seasonManager = new SeasonManager(this, playerDataManager, configManager, langManager);
        seasonManager.load();
        battleManager = new BattleManager(this, playerDataManager, configManager, langManager);
        guiManager = new GuiManager(this, playerDataManager, configManager, langManager, seasonManager, battleManager);
        commandHandler = new CommandHandler(this, configManager, langManager, playerDataManager, seasonManager, battleManager, guiManager);
        eventListener = new EventListener(this, playerDataManager, langManager, battleManager, guiManager);

        // 6. 注册事件和命令
        getServer().getPluginManager().registerEvents(eventListener, this);
        getCommand("niu").setExecutor(commandHandler);
        getCommand("niu").setTabCompleter(commandHandler);

        // 6.5 注册 PlaceholderAPI（若存在）
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            NiuNiuExpansion expansion = new NiuNiuExpansion(this);
            if (expansion.register()) {
                getLogger().info("已注册 PlaceholderAPI 变量扩展。");
            } else {
                getLogger().warning("PlaceholderAPI 变量扩展注册失败（identifier 冲突？）。");
            }
        }

        // 7. 赛季检查
        seasonManager.advanceSeasonIfNeeded();

        // 8. 定时赛季检查
        seasonTask = getServer().getScheduler().runTaskTimer(this, seasonManager::advanceSeasonIfNeeded,
                configManager.saveInterval * 20L, configManager.saveInterval * 20L);

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

        getLogger().info("NiuNiu 牛牛对战系统已启用（" + databaseManager.getBackend() + " 模式）");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (seasonTask != null) seasonTask.cancel();
        if (databaseManager != null) {
            databaseManager.close();
        }
    }
}