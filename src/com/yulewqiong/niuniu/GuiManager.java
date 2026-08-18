package com.yulewqiong.niuniu;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI 管理器：负责所有界面的创建与渲染。
 */
public class GuiManager {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final ConfigManager config;
    private final LangManager lang;
    private final SeasonManager seasonMgr;
    private final BattleManager battleMgr;

    public GuiManager(NiuNiu plugin, PlayerDataManager dataMgr, ConfigManager config, LangManager lang,
                      SeasonManager seasonMgr, BattleManager battleMgr) {
        this.plugin = plugin;
        this.dataMgr = dataMgr;
        this.config = config;
        this.lang = lang;
        this.seasonMgr = seasonMgr;
        this.battleMgr = battleMgr;
    }

    // ===== 主菜单 =====

    public void openMain(Player p) {
        dataMgr.ensure(p);
        Inventory inv = Bukkit.createInventory(null, 27, LangManager.color("&6&l牛牛对战系统"));
        renderMain(p, inv);
        p.openInventory(inv);
    }

    public void renderMain(Player p, Inventory inv) {
        inv.clear();
        for (int i = 0; i < 27; i++) {
            if (i == 4 || i == 11 || i == 13 || i == 15 || i == 22 || i == 26) continue;
            inv.setItem(i, glass());
        }
        inv.setItem(4, infoIcon(p));
        int stam = dataMgr.getStamina(p);
        boolean cd = dataMgr.inCooldown(p);
        String cdLine = cd ? ("&c牛牛闹脾气中（" + dataMgr.statusSec(p) + "秒）") : "&a可以操作";

        List<String> bl = new ArrayList<>();
        bl.add("");
        bl.add(LangManager.color("&f挑战其他玩家的牛牛，一决高下"));
        bl.add("");
        bl.add(LangManager.color("&7对战规则："));
        bl.add(LangManager.color("&7▶ 双方各消耗 &e" + config.battleCost + " &7点体力"));
        bl.add(LangManager.color("&7▶ 长度越长，胜率越高"));
        bl.add(LangManager.color("&7▶ 赢家牛牛 &a+" + config.winGrowthMin + "~" + config.winGrowthMax + "cm"));
        bl.add(LangManager.color("&7▶ 输家牛牛 &c-" + config.loseLossMin + "~" + config.loseLossMax + "cm&7（最低 " + dataMgr.fmtLen(config.minLength) + "cm）"));
        bl.add("");
        bl.add(LangManager.color("&7当前体力：" + (stam >= config.battleCost ? "&a" + stam : "&c" + stam) + "&7/&e" + config.maxStamina));
        bl.add(LangManager.color("&7状态：" + cdLine));
        bl.add("");
        bl.add(LangManager.color("&7左键进入选人"));
        bl.add(LangManager.color("&7也可以输入 &e/niuniu battle <玩家>&7 指定对手"));
        inv.setItem(11, btn(Material.IRON_SWORD, "&c&l对战", "battle", bl));

        List<String> rl = new ArrayList<>();
        rl.add("");
        rl.add(LangManager.color("&f给牛牛打胶，让它长大"));
        rl.add("");
        rl.add(LangManager.color("&7打胶规则："));
        rl.add(LangManager.color("&7▶ 消耗 &e" + config.brushCost + " &7点体力"));
        rl.add(LangManager.color("&7▶ 每次打胶必成功 &a+" + config.brushGrowthMin + "~" + config.brushGrowthMax + "cm"));
        rl.add(LangManager.color("&7▶ 打一次进入 &c" + config.brushCooldown + " &7秒冷却"));
        rl.add(LangManager.color("&7  （冷却期间不能打胶）"));
        rl.add("");
        rl.add(LangManager.color("&7当前体力：" + (stam >= config.brushCost ? "&a" + stam : "&c" + stam) + "&7/&e" + config.maxStamina));
        rl.add(LangManager.color("&7状态：" + cdLine));
        rl.add("");
        rl.add(LangManager.color("&7左键打胶"));
        inv.setItem(13, btn(Material.END_ROD, "&b&l打胶", "brush", rl));

        List<String> tl = new ArrayList<>();
        tl.add("");
        tl.add(LangManager.color("&f看看本赛季谁的牛牛最高"));
        tl.add("");
        tl.add(LangManager.color("&7按赛季最高长度(cm)排名"));
        tl.add(LangManager.color("&7每赛季结束自动重置"));
        tl.add("");
        tl.add(LangManager.color("&7左键查看"));
        inv.setItem(15, btn(Material.EMERALD, "&e&l排行榜", "top", tl));

        List<String> gl = new ArrayList<>();
        gl.add("");
        gl.add(LangManager.color("&f花金币换体力，继续战斗"));
        gl.add("");
        gl.add(LangManager.color("&7价格：&6" + config.buyPrice + "$ &7= &e+" + config.buyAmount + " &7体力"));
        gl.add(LangManager.color("&7体力上限：&e" + config.maxStamina));
        gl.add(LangManager.color("&7每日限购：&e" + config.buyLimitPerDay + " &7次"));
        gl.add("");
        gl.add(LangManager.color("&7当前体力：" + (stam >= config.maxStamina ? "&a" + stam + "&7（已满）" : "&a" + stam + "&7/&e" + config.maxStamina)));
        gl.add("");
        gl.add(LangManager.color("&7左键购买"));
        inv.setItem(22, btn(Material.GOLD_INGOT, "&6&l购买体力", "buy", gl));

        List<String> closeL = new ArrayList<>();
        closeL.add("");
        closeL.add(LangManager.color("&7返回服务器主菜单"));
        inv.setItem(26, btn(Material.BARRIER, "&c&l返回主菜单", "close", closeL));
    }

    // ===== 选人界面 =====

    public void openBattle(Player p) {
        dataMgr.ensure(p);
        List<Player> targets = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (!t.getUniqueId().equals(p.getUniqueId())) targets.add(t);
        }
        Inventory inv = Bukkit.createInventory(null, 45, LangManager.color("&c&l牛牛对战 - 选择对手"));
        for (int i = 0; i < 45; i++) {
            if (i == 40) continue;
            inv.setItem(i, glass());
        }
        int slot = 10;
        for (Player t : targets) {
            if (slot >= 40) break;
            double tLen = dataMgr.getLength(t);
            if (tLen < config.minChallengeLength) continue;
            ItemStack head = headOf(t);
            ItemMeta m = head.getItemMeta();
            int tcp = dataMgr.getCP(t);
            String display = "&7[" + dataMgr.getRank(tcp) + "&7] &e" + t.getName();
            m.setDisplayName(LangManager.color(display));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(LangManager.color("&b长度：&e" + dataMgr.fmtLen(tLen) + "cm"));
            lore.add(LangManager.color("&b赛季最高：&6" + dataMgr.fmtLen(dataMgr.getSeasonPeak(t)) + "cm"));
            lore.add(LangManager.color("&b战斗力：&e" + tcp + " &7（" + dataMgr.getRank(tcp) + "&7）"));
            lore.add(LangManager.color("&bHP：&c" + battleMgr.maxHp(tcp)));
            lore.add(LangManager.color("&b体力：&a" + dataMgr.getStamina(t) + "&7/&e" + config.maxStamina));
            int twins = dataMgr.getWins(t);
            int tbattles = dataMgr.getBattles(t);
            String wr = tbattles > 0 ? (int) Math.round(100.0 * twins / tbattles) + "%" : "—";
            lore.add(LangManager.color("&b战绩：&e" + twins + " &7胜 · 胜率 &e" + wr));
            lore.add("");
            int winChance = (int) Math.round(100.0 * dataMgr.getCP(p) / (dataMgr.getCP(p) + tcp));
            lore.add(LangManager.color("&7你的胜率约 &e" + winChance + "%"));
            if (dataMgr.inCooldown(t)) {
                lore.add("");
                lore.add(LangManager.color("&c对方牛牛正在闹脾气"));
            } else if (dataMgr.getStamina(t) < config.battleCost) {
                lore.add("");
                lore.add(LangManager.color("&c对方体力不足，无法应战"));
            }
            lore.add("");
            lore.add(LangManager.color("&7左键挑战 · 双方各耗 &e" + config.battleCost + " &7体力"));
            m.setLore(lore);
            battleMgr.setAction(m, "challenge:" + t.getName());
            head.setItemMeta(m);
            inv.setItem(slot++, head);
        }
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(LangManager.color("&7返回主菜单"));
        inv.setItem(40, btn(Material.ARROW, "&c&l返回", "back", backL));
        p.openInventory(inv);
    }

    // ===== 排行榜 =====

    public void openTop(Player p) {
        openTop(p, 1);
    }

    public void openTop(Player p, int page) {
        Map<String, Double> peaks = new LinkedHashMap<>();
        if (dataMgr.getData().contains("players")) {
            for (String key : dataMgr.getData().getConfigurationSection("players").getKeys(false)) {
                double peak = dataMgr.getData().getDouble("players." + key + ".seasonPeak", 0);
                if (peak <= 0) continue;
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
                LangManager.color("&e&lS" + seasonMgr.getSeasonCurrent() + " 赛季 · 牛牛长度榜 &8(" + page + "/" + totalPages + ")"));
        for (int i = 0; i < 45; i++) {
            if (i == 40 || i == 38 || i == 42 || (i >= 10 && i <= 34 && (i - 10) % 3 == 0)) continue;
            inv.setItem(i, glass());
        }
        int slot = 10;
        for (int i = start; i < Math.min(start + perPage, top20.size()); i++) {
            if (slot > 34) break;
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
            m.setDisplayName(LangManager.color(medal + " &e" + name));
            double cur = dataMgr.getData().getDouble("players." + e.getKey() + ".length", 0);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(LangManager.color("&b赛季最高：&6" + dataMgr.fmtLen(e.getValue()) + "cm"));
            lore.add(LangManager.color("&7当前长度：&e" + dataMgr.fmtLen(cur) + "cm"));
            lore.add("");
            lore.add(LangManager.color("&7排名第 &e" + (i + 1) + " &7名"));
            lore.add(LangManager.color("&7点击头像查看玩家信息"));
            m.setLore(lore);
            battleMgr.setAction(m, "topinfo:" + e.getKey() + ":" + page);
            head.setItemMeta(m);
            inv.setItem(slot, head);
            slot += 3;
        }
        while (slot <= 34) {
            inv.setItem(slot, glass());
            slot += 3;
        }
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(LangManager.color("&7返回主菜单"));
        inv.setItem(40, btn(Material.ARROW, "&c&l返回", "back", backL));
        if (page > 1) {
            List<String> prevL = new ArrayList<>();
            prevL.add("");
            prevL.add(LangManager.color("&7上一页"));
            inv.setItem(38, btn(Material.ARROW, "&e&l上一页", "toppage:" + (page - 1), prevL));
        } else {
            inv.setItem(38, glass());
        }
        if (page < totalPages) {
            List<String> nextL = new ArrayList<>();
            nextL.add("");
            nextL.add(LangManager.color("&7下一页"));
            inv.setItem(42, btn(Material.ARROW, "&e&l下一页", "toppage:" + (page + 1), nextL));
        } else {
            inv.setItem(42, glass());
        }
        p.openInventory(inv);
    }

    // ===== 玩家信息 =====

    public void openPlayerInfo(Player viewer, String targetUuid, int backPage) {
        ItemStack head = headByUuid(targetUuid);
        ItemMeta m = head.getItemMeta();
        String name = "?";
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(targetUuid));
            name = op.getName() == null ? "?" : op.getName();
        } catch (Exception ignored) {
        }
        String b = "players." + targetUuid;
        double len = dataMgr.getData().getDouble(b + ".length", 0);
        double peak = dataMgr.getData().getDouble(b + ".seasonPeak", len);
        int stam = dataMgr.getData().getInt(b + ".stamina", 0);
        int wins = dataMgr.getData().getInt(b + ".wins", 0);
        int battles = dataMgr.getData().getInt(b + ".battles", 0);
        long cd = dataMgr.getData().getLong(b + ".cooldown", 0);
        boolean hasData = dataMgr.getData().contains(b);
        int cp = dataMgr.getCP(len);

        m.setDisplayName(LangManager.color("&6&l" + name + " &e的牛牛"));
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (!hasData) {
            lore.add(LangManager.color("&7这位玩家还没有开始养牛"));
        } else {
            lore.add(LangManager.color("&b当前长度：&e" + dataMgr.fmtLen(len) + " &7cm"));
            lore.add(LangManager.color("&b赛季最高：&6" + dataMgr.fmtLen(peak) + " &7cm"));
            lore.add(LangManager.color("&b战斗力：&e" + cp + " &7（" + dataMgr.getRank(cp) + "&7）"));
            lore.add(LangManager.color("&b最大HP：&c" + battleMgr.maxHp(cp)));
            lore.add(LangManager.color("&b体力：" + (stam > 0 ? "&a" + stam : "&c" + stam) + "&7/&e" + config.maxStamina));
            String wr = battles > 0 ? (int) Math.round(100.0 * wins / battles) + "%" : "—";
            lore.add(LangManager.color("&b战绩：&e" + wins + " &7胜 · 共 &e" + battles + " &7场 · 胜率 &e" + wr));
            lore.add(LangManager.color("&7当前赛季：&eS" + seasonMgr.getSeasonCurrent()));
            lore.add("");
            long cdLeft = Math.max(0, cd - System.currentTimeMillis());
            if (cdLeft > 0) {
                lore.add(LangManager.color("&c牛牛正在闹脾气（还剩 &c" + (cdLeft / 1000 + 1) + " &7秒）"));
            } else {
                lore.add(LangManager.color("&7牛牛心情很好 ❇"));
            }
        }
        m.setLore(lore);
        head.setItemMeta(m);

        Inventory inv = Bukkit.createInventory(null, 27, LangManager.color("&e&l牛牛信息 - " + name));
        for (int i = 0; i < 27; i++) {
            if (i == 13) continue;
            inv.setItem(i, glass());
        }
        inv.setItem(13, head);
        List<String> backL = new ArrayList<>();
        backL.add("");
        backL.add(LangManager.color("&7返回排行榜第 " + backPage + " 页"));
        inv.setItem(22, btn(Material.ARROW, "&c&l返回", "toppage:" + backPage, backL));
        viewer.openInventory(inv);
    }

    // ===== 返回主菜单 =====

    public void openMainMenu(Player p) {
        if (Bukkit.getPluginManager().getPlugin("PlayerMenu") != null) {
            try {
                cn.handyplus.menu.util.MenuUtil.asyncOpenGui(p, "menu", null);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    // ===== 信息卡 =====

    private ItemStack infoIcon(Player p) {
        ItemStack head = headOf(p);
        ItemMeta m = head.getItemMeta();
        m.setDisplayName(LangManager.color("&6&l你的牛牛 &e❤"));
        double len = dataMgr.getLength(p);
        int stam = dataMgr.getStamina(p);
        int wins = dataMgr.getWins(p);
        int battles = dataMgr.getBattles(p);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(LangManager.color("&b当前长度：&e" + dataMgr.fmtLen(len) + " &7cm"));
        lore.add(LangManager.color("&b赛季最高：&6" + dataMgr.fmtLen(dataMgr.getSeasonPeak(p)) + " &7cm"));
        int cp = dataMgr.getCP(p);
        lore.add(LangManager.color("&b战斗力：&e" + cp + " &7（" + dataMgr.getRank(cp) + "&7）"));
        lore.add(LangManager.color("&b最大HP：&c" + battleMgr.maxHp(cp)));
        lore.add(LangManager.color("&b体力：" + (stam > 0 ? "&a" + stam : "&c" + stam) + "&7/&e" + config.maxStamina));
        lore.add(LangManager.color("&b战绩：&e" + wins + " &7胜 · 共战斗 &e" + battles + " &7场"));
        lore.add(LangManager.color("&7当前赛季：&eS" + seasonMgr.getSeasonCurrent() + "&7（每赛季 &e" + config.seasonDurationDays + " &7天）"));
        lore.add("");
        if (dataMgr.inCooldown(p)) {
            lore.add(LangManager.color("&c牛牛正在闹脾气（还剩 &c" + dataMgr.statusSec(p) + " &7秒）"));
            lore.add(LangManager.color("&7期间不能打胶"));
        } else {
            lore.add(LangManager.color("&7牛牛心情很好 ❇"));
        }
        lore.add("");
        lore.add(LangManager.color("&7玩法速览："));
        lore.add(LangManager.color("&7▶ 每天 &e" + config.dailyStamina + " &7点体力，次日自动回满"));
        lore.add(LangManager.color("&7▶ 对战耗 &e" + config.battleCost + "&7：赢 &a+" + config.winGrowthMin + "~" + config.winGrowthMax + "cm，输 &c-" + config.loseLossMin + "~" + config.loseLossMax + "cm"));
        lore.add(LangManager.color("&7▶ 打胶耗 &e" + config.brushCost + "&7：必成功 &a+" + config.brushGrowthMin + "~" + config.brushGrowthMax + "cm"));
        lore.add(LangManager.color("&7▶ 打一次后进入 &c" + config.brushCooldown + "&7 秒冷却"));
        lore.add(LangManager.color("&7▶ 体力不够：&6/niu buy&7 花 &6" + config.buyPrice + "$&7 买 &e+" + config.buyAmount + "&7（每天限 &e" + config.buyLimitPerDay + " &7次）"));
        lore.add(LangManager.color("&7▶ 牛牛低于 &e" + dataMgr.fmtLen(config.minChallengeLength) + "cm&7 不能对战，先打胶养大"));
        lore.add("");
        lore.add(LangManager.color("&7点击下方按钮开始你的牛牛之旅！"));
        m.setLore(lore);
        head.setItemMeta(m);
        return head;
    }

    // ===== GUI 工具方法 =====

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
        m.setDisplayName(LangManager.color(name));
        m.setLore(lore);
        battleMgr.setAction(m, action);
        it.setItemMeta(m);
        return it;
    }

    public int safePage(String s) {
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
            SkullMeta sm = (SkullMeta) it.getItemMeta();
            sm.setPlayerProfile(profile);
            it.setItemMeta(sm);
        } catch (Exception ignored) {
        }
        return it;
    }
}