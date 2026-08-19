package com.yulewqiong.niuniu;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * PlaceholderAPI 变量扩展。
 * 个人变量（带玩家）：%niu_length% %niu_peak% %niu_stamina% 等
 * 排行榜变量（全局）：%niu_top_name_1% %niu_top_length_1% 等
 */
public class NiuNiuExpansion extends PlaceholderExpansion {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final ConfigManager config;
    private final SeasonManager seasonMgr;
    private final BattleManager battleMgr;

    public NiuNiuExpansion(NiuNiu plugin) {
        this.plugin = plugin;
        this.dataMgr = plugin.getPlayerDataManager();
        this.config = plugin.getConfigManager();
        this.seasonMgr = plugin.getSeasonManager();
        this.battleMgr = plugin.getBattleManager();
    }

    @Override
    public String getIdentifier() {
        return "niu";
    }

    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty()
                ? "NiuNiu" : String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String getRequiredPlugin() {
        return "NiuNiu";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        // 仅当本插件已启用时才允许注册
        return plugin.isEnabled();
    }

    @Override
    public List<String> getPlaceholders() {
        return Arrays.asList(
                "has_cow", "length", "length_raw", "peak", "stamina", "max_stamina",
                "cp", "rank", "rank_color", "max_hp", "wins", "battles", "winrate",
                "cooldown", "buys_today", "buy_limit", "season", "season_days_left",
                "top_name_1", "top_length_1", "top_peak_1", "top_wins_1"
        );
    }

    @Override
    public String onRequest(OfflinePlayer offline, String identifier) {
        // 排行榜变量不依赖在线玩家
        if (identifier.startsWith("top_")) {
            return handleTop(identifier);
        }

        if (offline == null) return null;

        Player p = offline.getPlayer();
        if (p == null) {
            // 离线玩家：按 uuid 从数据库读
            return handleOffline(offline.getUniqueId(), identifier);
        }
        return handleOnline(p, identifier);
    }

    // ===== 在线玩家变量 =====

    private String handleOnline(Player p, String id) {
        PlayerRow r = rowOf(p);
        boolean hasCow = r != null && r.length > 0;
        switch (id) {
            case "has_cow":
                return hasCow ? "true" : "false";
            case "length":
                return hasCow ? dataMgr.fmtLen(r.length) : "0.0";
            case "length_raw":
                return hasCow ? String.valueOf(r.length) : "0";
            case "peak":
                return hasCow ? dataMgr.fmtLen(r.seasonPeak) : "0.0";
            case "stamina":
                return String.valueOf(hasCow ? r.stamina : 0);
            case "max_stamina":
                return String.valueOf(config.maxStamina);
            case "cp":
                return String.valueOf(hasCow ? dataMgr.getCP(p) : 0);
            case "rank":
                return hasCow ? plain(dataMgr.getRank(dataMgr.getCP(p))) : "无";
            case "rank_color":
                return hasCow ? colored(dataMgr.getRank(dataMgr.getCP(p))) : "&7无";
            case "max_hp":
                return String.valueOf(hasCow ? battleMgr.maxHp(dataMgr.getCP(p)) : 0);
            case "wins":
                return String.valueOf(hasCow ? r.wins : 0);
            case "battles":
                return String.valueOf(hasCow ? r.battles : 0);
            case "winrate":
                return hasCow && r.battles > 0 ? (int) Math.round(100.0 * r.wins / r.battles) + "%" : "0%";
            case "cooldown":
                return hasCow ? String.valueOf(dataMgr.statusSec(p)) : "0";
            case "buys_today":
                return String.valueOf(hasCow ? r.buysToday : 0);
            case "buy_limit":
                return String.valueOf(config.buyLimitPerDay);
            case "season":
                return String.valueOf(seasonMgr.getSeasonCurrent());
            case "season_days_left":
                return String.valueOf(dataMgr.seasonDaysLeft());
            default:
                return null;
        }
    }

    // ===== 离线玩家变量（仅数据库可查的字段） =====

    private String handleOffline(UUID uuid, String id) {
        PlayerRow r = dataMgr.getByUuid(uuid.toString());
        boolean hasCow = r != null && r.length > 0;
        switch (id) {
            case "has_cow":
                return hasCow ? "true" : "false";
            case "length":
                return hasCow ? dataMgr.fmtLen(r.length) : "0.0";
            case "peak":
                return hasCow ? dataMgr.fmtLen(r.seasonPeak) : "0.0";
            case "wins":
                return String.valueOf(hasCow ? r.wins : 0);
            case "battles":
                return String.valueOf(hasCow ? r.battles : 0);
            case "winrate":
                return hasCow && r.battles > 0 ? (int) Math.round(100.0 * r.wins / r.battles) + "%" : "0%";
            default:
                return null;
        }
    }

    // ===== 排行榜变量 =====

    private String handleTop(String id) {
        // 形如 top_name_1 / top_length_3 / top_peak_5
        String[] parts = id.split("_");
        if (parts.length != 3) return null;
        String type = parts[1];
        int rank;
        try {
            rank = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (rank < 1) return null;

        List<PlayerRow> top = dataMgr.getTopRows(rank);
        if (top.size() < rank) {
            return type.equals("length") || type.equals("peak") ? "0.0" : "无";
        }
        PlayerRow r = top.get(rank - 1);
        switch (type) {
            case "name":
                return playerName(r.uuid);
            case "length":
                return dataMgr.fmtLen(r.length);
            case "peak":
                return dataMgr.fmtLen(r.seasonPeak);
            case "wins":
                return String.valueOf(r.wins);
            default:
                return null;
        }
    }

    private PlayerRow rowOf(Player p) {
        return dataMgr.getByUuid(p.getUniqueId().toString());
    }

    private String playerName(String uuid) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            return op.getName() == null ? "?" : op.getName();
        } catch (Exception e) {
            return "?";
        }
    }

    private String plain(String colored) {
        return ChatColor.stripColor(colored(colored));
    }

    private String colored(String colored) {
        return LangManager.color(colored);
    }
}