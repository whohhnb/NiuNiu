package com.yulewqiong.niuniu;

import org.bukkit.Bukkit;
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

import java.util.UUID;

/**
 * 事件监听器：负责所有 Bukkit 事件的监听与处理。
 */
public class EventListener implements Listener {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final LangManager lang;
    private final BattleManager battleMgr;
    private final GuiManager guiMgr;

    public EventListener(NiuNiu plugin, PlayerDataManager dataMgr, LangManager lang,
                         BattleManager battleMgr, GuiManager guiMgr) {
        this.plugin = plugin;
        this.dataMgr = dataMgr;
        this.lang = lang;
        this.battleMgr = battleMgr;
        this.guiMgr = guiMgr;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        dataMgr.ensureDaily(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        BattleManager.Challenge ch = battleMgr.getChallenges().get(uid);
        if (ch != null && !ch.done) {
            ch.done = true;
            if (ch.timeoutTask != -1) {
                Bukkit.getScheduler().cancelTask(ch.timeoutTask);
            }
            battleMgr.getChallenges().remove(ch.from);
            battleMgr.getChallengeBusy().remove(ch.from);
            battleMgr.getChallengeBusy().remove(ch.target);
            Player other = Bukkit.getPlayer(ch.from.equals(uid) ? ch.target : ch.from);
            if (other != null) lang.sendMsg(other, "challenge-cancelled");
        }
        BattleManager.Battle bt = battleMgr.getBattles().get(uid);
        if (bt != null && !bt.over) {
            Player winner = Bukkit.getPlayer(uid.equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                lang.broadcast("battle-forfeit", "loser", e.getPlayer().getName(), "winner", winner.getName());
                battleMgr.endBattle(bt, winner, e.getPlayer());
            } else {
                battleMgr.endBattle(bt, null, null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        BattleManager.Battle bt = battleMgr.getBattles().get(p.getUniqueId());
        if (bt == null || bt.over) return;
        boolean myTurn = (p.getUniqueId().equals(bt.a) && bt.turn == 0)
                || (p.getUniqueId().equals(bt.b) && bt.turn == 1);
        if (!myTurn) return;
        String msg = e.getMessage().trim();
        int choice = battleMgr.parseSkill(msg);
        if (choice == -1 || msg.length() > 6) return;
        e.setCancelled(true);
        final String m = msg;
        Bukkit.getScheduler().runTask(plugin, () -> battleMgr.handlePick(p, m));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof Player) {
            BattleManager.Battle bt = battleMgr.getBattles().get(victim.getUniqueId());
            if (bt != null && !bt.over) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        BattleManager.Battle bt = battleMgr.getBattles().get(p.getUniqueId());
        if (bt != null && !bt.over) {
            Player winner = Bukkit.getPlayer(p.getUniqueId().equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                lang.broadcast("battle-forfeit", "loser", p.getName(), "winner", winner.getName());
                battleMgr.endBattle(bt, winner, p);
            } else {
                battleMgr.endBattle(bt, null, null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player) {
            if (e.getView().getTitle().contains("牛牛")) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.contains("牛牛")) return;
        e.setCancelled(true);
        String action = battleMgr.getAction(e.getCurrentItem());
        if (action == null) return;
        p.closeInventory();
        switch (action) {
            case "battle":
                guiMgr.openBattle(p);
                break;
            case "brush":
                dataMgr.doBrush(p);
                break;
            case "top":
                guiMgr.openTop(p);
                break;
            case "buy":
                dataMgr.doBuy(p);
                break;
            case "back":
                guiMgr.openMain(p);
                break;
            case "close":
                guiMgr.openMainMenu(p);
                break;
            default:
                if (action.startsWith("topinfo:")) {
                    String[] parts = action.substring("topinfo:".length()).split(":");
                    if (parts.length >= 1) {
                        int backPage = parts.length > 1 ? guiMgr.safePage(parts[1]) : 1;
                        guiMgr.openPlayerInfo(p, parts[0], backPage);
                    }
                } else if (action.startsWith("toppage:")) {
                    guiMgr.openTop(p, guiMgr.safePage(action.substring("toppage:".length())));
                } else if (action.startsWith("challenge:")) {
                    String tname = action.substring("challenge:".length());
                    Player t = Bukkit.getPlayerExact(tname);
                    if (t != null && !t.getUniqueId().equals(p.getUniqueId())) {
                        battleMgr.doBattle(p, t);
                    }
                }
                break;
        }
    }
}