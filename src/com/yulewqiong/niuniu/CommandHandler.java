package com.yulewqiong.niuniu;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令处理器：负责 /niu 命令及其子命令的分发与 Tab 补全。
 */
public class CommandHandler implements TabExecutor {

    private final NiuNiu plugin;
    private final ConfigManager config;
    private final LangManager lang;
    private final PlayerDataManager dataMgr;
    private final SeasonManager seasonMgr;
    private final BattleManager battleMgr;
    private final GuiManager guiMgr;

    public CommandHandler(NiuNiu plugin, ConfigManager config, LangManager lang, PlayerDataManager dataMgr,
                          SeasonManager seasonMgr, BattleManager battleMgr, GuiManager guiMgr) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.dataMgr = dataMgr;
        this.seasonMgr = seasonMgr;
        this.battleMgr = battleMgr;
        this.guiMgr = guiMgr;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            return handleConsole(sender, args);
        }
        if (args.length == 0) {
            guiMgr.openMain(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "battle":
                if (args.length < 2) {
                    lang.sendMsg(p, "battle-usage");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    lang.sendMsg(p, "player-offline", "name", args[1]);
                    return true;
                }
                battleMgr.doBattle(p, t);
                return true;
            case "brush":
                dataMgr.doBrush(p);
                return true;
            case "forcebrush":
                if (!p.hasPermission("niu.admin")) {
                    lang.sendMsg(p, "no-permission");
                    return true;
                }
                Player fTarget = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : p;
                if (fTarget == null) {
                    lang.sendMsg(p, "player-offline", "name", args[1]);
                    return true;
                }
                dataMgr.forceBrush(fTarget);
                lang.sendMsg(p, "brush-forced-admin", "target", fTarget.getName());
                return true;
            case "setlength":
                if (!p.hasPermission("niu.setlength")) {
                    lang.sendMsg(p, "no-permission");
                    return true;
                }
                if (args.length < 3) {
                    lang.sendMsg(p, "setlength-usage");
                    return true;
                }
                Player sTarget = Bukkit.getPlayerExact(args[1]);
                if (sTarget == null) {
                    lang.sendMsg(p, "player-offline", "name", args[1]);
                    return true;
                }
                double len;
                try {
                    len = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    lang.sendMsg(p, "setlength-invalid");
                    return true;
                }
                // 拦截 NaN / Infinity / 负数 / 超上限
                if (!Double.isFinite(len) || len <= 0 || len > config.maxLength) {
                    lang.sendMsg(p, "setlength-invalid", "max", dataMgr.fmtLen(config.maxLength));
                    return true;
                }
                // 可选第 4 参数：-p 强制覆盖赛季最高
                boolean overridePeak = args.length >= 4 && args[3].equalsIgnoreCase("-p");
                double rounded = Math.round(len * 10.0) / 10.0;
                if (!dataMgr.setLength(sTarget, rounded, overridePeak)) {
                    lang.sendMsg(p, "no-cow-target", "target", sTarget.getName());
                    return true;
                }
                lang.sendMsg(p, "setlength-success", "target", sTarget.getName(), "length", dataMgr.fmtLen(rounded));
                return true;
            case "buy":
                dataMgr.doBuy(p);
                return true;
            case "top":
                guiMgr.openTop(p);
                return true;
            case "accept":
                battleMgr.handleChallengeCommand(p, true);
                return true;
            case "decline":
            case "refuse":
            case "reject":
                battleMgr.handleChallengeCommand(p, false);
                return true;
            case "skill":
                if (args.length < 2) {
                    lang.sendMsg(p, "battle-pick-invalid");
                    return true;
                }
                battleMgr.handlePick(p, args[1]);
                return true;
            case "season":
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    if (p.hasPermission("niu.admin")) {
                        seasonMgr.startNewSeason();
                        lang.sendMsg(p, "season-start-admin", "season", String.valueOf(seasonMgr.getSeasonCurrent()));
                    } else {
                        lang.sendMsg(p, "no-permission");
                    }
                    return true;
                }
                long daysLeft = Math.max(0, (seasonMgr.getSeasonStarted() + config.seasonDurationDays * 86400000L - System.currentTimeMillis()) / 86400000L);
                lang.sendMsg(p, "season-info", "season", String.valueOf(seasonMgr.getSeasonCurrent()), "days", String.valueOf(daysLeft));
                return true;
            case "reload":
                if (p.hasPermission("niu.admin")) {
                    reloadAll();
                    lang.sendMsg(p, "reload");
                } else {
                    lang.sendMsg(p, "no-permission");
                }
                return true;
            case "migrate":
                if (p.hasPermission("niu.admin")) {
                    handleMigrate(p);
                } else {
                    lang.sendMsg(p, "no-permission");
                }
                return true;
            default:
                lang.sendMsg(p, "usage");
                return true;
        }
    }

    private boolean handleConsole(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadAll();
            sender.sendMessage(LangManager.color(lang.prefix() + lang.t("reload")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("migrate")) {
            handleMigrate(sender);
            return true;
        }
        sender.sendMessage(LangManager.color(lang.prefix() + lang.t("console-only")));
        return true;
    }

    private void reloadAll() {
        plugin.reloadConfig();
        config.load();
        lang.load();
        plugin.getDatabaseManager().close();
        String sqlitePath = new java.io.File(plugin.getDataFolder(), config.sqliteFile).getAbsolutePath();
        boolean connected = plugin.getDatabaseManager().connect(
                config.storageType, sqlitePath,
                config.mysqlHost, config.mysqlPort, config.mysqlDatabase,
                config.mysqlUsername, config.mysqlPassword,
                config.tablePrefix, config.poolMaxSize, config.poolMinIdle);
        if (!connected) {
            plugin.getLogger().warning("数据库重连失败。");
            return;
        }
        dataMgr.loadAll();
        seasonMgr.load();
    }

    private void handleMigrate(CommandSender sender) {
        sender.sendMessage(LangManager.color(lang.prefix() + "&e正在从另一后端迁移数据到当前后端，请稍候..."));
        int count = dataMgr.migrate();
        if (count == -2) {
            sender.sendMessage(LangManager.color(lang.prefix() + "&c当前数据库未连接，无法迁移。"));
        } else if (count == -1) {
            sender.sendMessage(LangManager.color(lang.prefix() + "&c源后端连接失败，请检查另一后端的配置。"));
        } else {
            sender.sendMessage(LangManager.color(lang.prefix() + "&a迁移完成！共迁移 " + count + " 名玩家数据到当前后端。"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"battle", "brush", "forcebrush", "setlength", "buy", "top", "season", "accept", "decline", "skill", "migrate", "reload"}) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("forcebrush") || args[0].equalsIgnoreCase("battle") || args[0].equalsIgnoreCase("setlength"))) {
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(t.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            for (String s : new String[]{"start"}) {
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setlength")) {
            out.add("长度");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("setlength")) {
            for (String s : new String[]{"-p"}) {
                if (s.startsWith(args[3].toLowerCase())) out.add(s);
            }
        }
        return out;
    }
}