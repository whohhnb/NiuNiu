package com.yulewqiong.niuniu;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 配置管理器：负责从 config.yml 加载所有配置项，并提供统一访问接口。
 */
public class ConfigManager {

    private final NiuNiu plugin;

    // ===== 体力 =====
    int maxStamina;
    int dailyStamina;

    // ===== 打胶 =====
    int brushCost;
    int brushGrowthMin;
    int brushGrowthMax;
    int brushCooldown;

    // ===== 对战 =====
    int battleCost;
    int winGrowthMin;
    int winGrowthMax;
    int loseLossMin;
    int loseLossMax;
    int battlePickTimeout;

    // ===== 购买体力 =====
    int buyPrice;
    int buyAmount;
    int buyLimitPerDay;

    // ===== 保存 =====
    int saveInterval;

    // ===== 赛季 =====
    int seasonDurationDays;

    // ===== 牛牛属性 =====
    double initLenMin;
    double initLenMax;
    double minLength;
    double minChallengeLength;

    // ===== 战斗力 =====
    double cpMult;
    int cpBase;

    // ===== 回合制 =====
    int hpBase;
    int hpPerRank;
    int rageMax;
    int rageStart;
    int rageRegen;

    // ===== 技能 =====
    int skillTailDamageBase;
    double skillTailDamagePerCp;
    int skillTailCost;
    int skillHornDamageBase;
    double skillHornDamagePerCp;
    int skillHornCost;
    int skillShieldRage;

    // ===== 结算 =====
    String titleNameConf;
    String createCommand;
    String grantCommand;
    String titleDisplay;

    public ConfigManager(NiuNiu plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();
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
        createCommand = c.getString("settlement.create-command", "");
        grantCommand = c.getString("settlement.grant-command", "plt player add {player} {title} 0");
        titleDisplay = c.getString("settlement.title-display", "&6&l✦ &e&lS{season} 打胶大王 &6&l✦");
    }
}