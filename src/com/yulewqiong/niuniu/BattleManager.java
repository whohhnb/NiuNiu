package com.yulewqiong.niuniu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 对战管理器：负责挑战流程与回合制对战引擎。
 */
public class BattleManager {

    private final NiuNiu plugin;
    private final PlayerDataManager dataMgr;
    private final ConfigManager config;
    private final LangManager lang;

    private final NamespacedKey actionKey;
    private final Map<UUID, Battle> battles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Set<UUID> challengeBusy = new HashSet<>();

    public static final int SKILL_TAIL = 1;
    public static final int SKILL_HORN = 2;
    public static final int SKILL_SHIELD = 3;

    public BattleManager(NiuNiu plugin, PlayerDataManager dataMgr, ConfigManager config, LangManager lang) {
        this.plugin = plugin;
        this.dataMgr = dataMgr;
        this.config = config;
        this.lang = lang;
        this.actionKey = new NamespacedKey(plugin, "niu_action");
    }

    // ===== 内部类 =====

    public static class Battle {
        UUID a, b;
        String nameA, nameB;
        int hpA, hpB, maxA, maxB;
        int rageA, rageB;
        int turn; // 0=A选, 1=B选
        int choiceA = -1, choiceB = -1;
        int pickGen = 0;
        int timeoutTask = -1;
        boolean over;
    }

    public static class Challenge {
        UUID from, target;
        String fromName, targetName;
        int timeoutTask = -1;
        boolean done;
    }

    // ===== 公开访问 =====

    public Map<UUID, Battle> getBattles() {
        return battles;
    }

    public Map<UUID, Challenge> getChallenges() {
        return challenges;
    }

    public Set<UUID> getChallengeBusy() {
        return challengeBusy;
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }

    // ===== 技能工具方法 =====

    public int rankIndex(int cp) {
        if (cp < 50) return 0;
        if (cp < 150) return 1;
        if (cp < 400) return 2;
        if (cp < 1000) return 3;
        if (cp < 2500) return 4;
        return 5;
    }

    public int maxHp(int cp) {
        return config.hpBase + rankIndex(cp) * config.hpPerRank;
    }

    public int skillDamage(int skill, int cp) {
        if (skill == SKILL_TAIL) return config.skillTailDamageBase + (int) Math.round(cp * config.skillTailDamagePerCp);
        if (skill == SKILL_HORN) return config.skillHornDamageBase + (int) Math.round(cp * config.skillHornDamagePerCp);
        return 0;
    }

    public int rageCost(int skill) {
        if (skill == SKILL_TAIL) return config.skillTailCost;
        if (skill == SKILL_HORN) return config.skillHornCost;
        return 0;
    }

    public String skillName(int skill) {
        if (skill == SKILL_TAIL) return "猛烈甩击";
        if (skill == SKILL_HORN) return "暴力筹茬";
        return "牛盾";
    }

    public int parseSkill(String s) {
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

    // ===== Item Action 持久化 =====

    public void setAction(ItemMeta m, String action) {
        m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
    }

    public String getAction(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    // ===== 挑战流程 =====

    public void doBattle(Player a, Player b) {
        if (!dataMgr.hasCow(a)) {
            lang.sendMsg(a, "no-cow");
            return;
        }
        if (!dataMgr.hasCow(b)) {
            lang.sendMsg(a, "no-cow-target", "target", b.getName());
            return;
        }
        dataMgr.ensureDaily(a);
        dataMgr.ensureDaily(b);
        if (a.getUniqueId().equals(b.getUniqueId())) {
            lang.sendMsg(a, "battle-self");
            return;
        }
        if (battles.containsKey(a.getUniqueId()) || battles.containsKey(b.getUniqueId())
                || challengeBusy.contains(a.getUniqueId()) || challengeBusy.contains(b.getUniqueId())) {
            lang.sendMsg(a, "battle-in-battle");
            return;
        }
        double la = dataMgr.getLength(a);
        double lb = dataMgr.getLength(b);
        if (la < config.minChallengeLength || lb < config.minChallengeLength) {
            lang.sendMsg(a, "battle-too-small", "min", dataMgr.fmtLen(config.minChallengeLength));
            lang.sendMsg(a, "battle-too-small-hint");
            return;
        }
        int sa = dataMgr.getStamina(a);
        int sb = dataMgr.getStamina(b);
        if (sa < config.battleCost || sb < config.battleCost) {
            if (sa < config.battleCost) {
                lang.sendMsg(a, "battle-no-stamina-self", "cost", String.valueOf(config.battleCost), "stamina", String.valueOf(sa));
            }
            if (sb < config.battleCost) {
                lang.sendMsg(a, "battle-target-no-stamina", "target", b.getName(), "stamina", String.valueOf(sb));
                lang.sendMsg(b, "battle-target-notify", "attacker", a.getName(), "stamina", String.valueOf(sb));
            }
            return;
        }
        Challenge ch = new Challenge();
        ch.from = a.getUniqueId();
        ch.target = b.getUniqueId();
        ch.fromName = a.getName();
        ch.targetName = b.getName();
        challenges.put(a.getUniqueId(), ch);
        challengeBusy.add(a.getUniqueId());
        challengeBusy.add(b.getUniqueId());
        lang.sendMsg(a, "challenge-sent", "target", b.getName());
        sendChallengePrompt(b, ch);
        ch.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (ch.done) return;
            ch.done = true;
            challenges.remove(ch.from);
            challengeBusy.remove(ch.from);
            challengeBusy.remove(ch.target);
            Player x = Bukkit.getPlayer(ch.from);
            Player y = Bukkit.getPlayer(ch.target);
            if (x != null) lang.sendMsg(x, "challenge-timeout", "target", ch.targetName);
            if (y != null) lang.sendMsg(y, "challenge-timeout-self", "from", ch.fromName);
        }, config.battlePickTimeout * 20L).getTaskId();
    }

    public Challenge findChallengeFor(Player p) {
        for (Challenge c : challenges.values()) {
            if (c.target.equals(p.getUniqueId())) return c;
        }
        return null;
    }

    private void sendChallengePrompt(Player b, Challenge ch) {
        Component line = Component.text(LangManager.color(lang.prefix() + lang.t("challenge-request", "from", ch.fromName, "timeout", String.valueOf(config.battlePickTimeout))));
        Component accept = Component.text(LangManager.color("&a [✔ 接受] "))
                .clickEvent(ClickEvent.runCommand("/niu accept"))
                .hoverEvent(HoverEvent.showText(Component.text(LangManager.color("&a点击接受挑战"))));
        Component decline = Component.text(LangManager.color("&c[✘ 拒绝]"))
                .clickEvent(ClickEvent.runCommand("/niu decline"))
                .hoverEvent(HoverEvent.showText(Component.text(LangManager.color("&c点击拒绝挑战"))));
        b.sendMessage(line.append(accept).append(decline));
    }

    public void handleChallengeCommand(Player b, boolean accept) {
        Challenge ch = findChallengeFor(b);
        if (ch == null || ch.done) {
            lang.sendMsg(b, "challenge-none");
            return;
        }
        if (accept) {
            acceptChallenge(b, ch);
        } else {
            rejectChallenge(b, ch);
        }
    }

    private void rejectChallenge(Player b, Challenge ch) {
        if (ch.done) return;
        ch.done = true;
        if (ch.timeoutTask != -1) plugin.getServer().getScheduler().cancelTask(ch.timeoutTask);
        challenges.remove(ch.from);
        challengeBusy.remove(ch.from);
        challengeBusy.remove(ch.target);
        Player a = Bukkit.getPlayer(ch.from);
        if (a != null) lang.sendMsg(a, "challenge-rejected", "target", ch.targetName);
    }

    private void acceptChallenge(Player b, Challenge ch) {
        if (ch.done) return;
        ch.done = true;
        if (ch.timeoutTask != -1) plugin.getServer().getScheduler().cancelTask(ch.timeoutTask);
        challenges.remove(ch.from);
        challengeBusy.remove(ch.from);
        challengeBusy.remove(ch.target);
        Player a = Bukkit.getPlayer(ch.from);
        if (a != null) {
            startBattle(a, b);
        } else {
            lang.sendMsg(b, "challenge-cancelled");
        }
    }

    // ===== 对战引擎 =====

    private void startBattle(Player a, Player b) {
        if (dataMgr.getStamina(a) < config.battleCost || dataMgr.getStamina(b) < config.battleCost) {
            if (dataMgr.getStamina(a) < config.battleCost) {
                lang.sendMsg(a, "battle-no-stamina-self", "cost", String.valueOf(config.battleCost), "stamina", String.valueOf(dataMgr.getStamina(a)));
            }
            if (dataMgr.getStamina(b) < config.battleCost) {
                lang.sendMsg(a, "battle-target-no-stamina", "target", b.getName(), "stamina", String.valueOf(dataMgr.getStamina(b)));
                lang.sendMsg(b, "battle-target-notify", "attacker", a.getName(), "stamina", String.valueOf(dataMgr.getStamina(b)));
            }
            return;
        }
        dataMgr.spendStamina(a, config.battleCost);
        dataMgr.spendStamina(b, config.battleCost);
        dataMgr.addBattleCount(a);
        dataMgr.addBattleCount(b);

        Battle bt = new Battle();
        bt.a = a.getUniqueId();
        bt.b = b.getUniqueId();
        bt.nameA = a.getName();
        bt.nameB = b.getName();
        int ca = dataMgr.getCP(a);
        int cb = dataMgr.getCP(b);
        bt.maxA = bt.hpA = maxHp(ca);
        bt.maxB = bt.hpB = maxHp(cb);
        bt.rageA = config.rageStart;
        bt.rageB = config.rageStart;
        bt.turn = 0;
        battles.put(a.getUniqueId(), bt);
        battles.put(b.getUniqueId(), bt);

        lang.broadcast("battle-vs", "a", a.getName(), "la", dataMgr.fmtLen(dataMgr.getLength(a)), "ca", String.valueOf(ca),
                "b", b.getName(), "lb", dataMgr.fmtLen(dataMgr.getLength(b)), "cb", String.valueOf(cb));
        prompt(bt, a);
    }

    public void prompt(Battle bt, Player p) {
        if (bt.timeoutTask != -1) {
            plugin.getServer().getScheduler().cancelTask(bt.timeoutTask);
            bt.timeoutTask = -1;
        }
        lang.sendMsg(p, "battle-hp", "a", bt.nameA, "hpA", String.valueOf(bt.hpA), "maxA", String.valueOf(bt.maxA),
                "b", bt.nameB, "hpB", String.valueOf(bt.hpB), "maxB", String.valueOf(bt.maxB),
                "rageA", String.valueOf(bt.rageA), "rageB", String.valueOf(bt.rageB));
        sendSkillPrompt(bt, p);
        UUID who = bt.turn == 0 ? bt.a : bt.b;
        int gen = ++bt.pickGen;
        bt.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (bt.over || bt.pickGen != gen) return;
            bt.timeoutTask = -1;
            Player loser = Bukkit.getPlayer(who);
            Player winner = Bukkit.getPlayer(who.equals(bt.a) ? bt.b : bt.a);
            if (winner != null) {
                lang.broadcast("battle-timeout-forfeit", "loser", loser != null ? loser.getName() : (who.equals(bt.a) ? bt.nameA : bt.nameB), "winner", winner.getName());
                endBattle(bt, winner, loser);
            } else {
                endBattle(bt, null, null);
            }
        }, config.battlePickTimeout * 20L).getTaskId();
    }

    public void sendSkillPrompt(Battle bt, Player p) {
        int myRage = p.getUniqueId().equals(bt.a) ? bt.rageA : bt.rageB;
        Component line = Component.text(LangManager.color(lang.prefix() + lang.t("battle-pick", "rage", String.valueOf(myRage), "max", String.valueOf(config.rageMax))));
        Component b1 = Component.text(LangManager.color(" &e[1 猛烈甩击]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 1"))
                .hoverEvent(HoverEvent.showText(Component.text(LangManager.color("&e强力攻击 · 耗怒 " + config.skillTailCost))));
        Component b2 = Component.text(LangManager.color(" &6[2 暴力筹茬]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 2"))
                .hoverEvent(HoverEvent.showText(Component.text(LangManager.color("&6稳定攻击 · 耗怒 " + config.skillHornCost))));
        Component b3 = Component.text(LangManager.color(" &a[3 牛盾]"))
                .clickEvent(ClickEvent.runCommand("/niu skill 3"))
                .hoverEvent(HoverEvent.showText(Component.text(LangManager.color("&a格挡伤害 · 回怒 " + config.skillShieldRage))));
        p.sendMessage(line.append(b1).append(b2).append(b3));
    }

    public void handlePick(Player p, String input) {
        Battle bt = battles.get(p.getUniqueId());
        if (bt == null || bt.over) return;
        int choice = parseSkill(input);
        if (choice == -1) {
            lang.sendMsg(p, "battle-pick-invalid");
            sendSkillPrompt(bt, p);
            return;
        }
        boolean isA = p.getUniqueId().equals(bt.a);
        int cost = rageCost(choice);
        int myRage = isA ? bt.rageA : bt.rageB;
        if (cost > myRage) {
            lang.sendMsg(p, "battle-no-rage", "rage", String.valueOf(myRage), "max", String.valueOf(config.rageMax), "cost", String.valueOf(cost));
            sendSkillPrompt(bt, p);
            return;
        }
        if (isA && bt.turn == 0) {
            bt.choiceA = choice;
            bt.turn = 1;
            lang.sendMsg(p, "battle-wait", "skill", skillName(choice));
            Player b = Bukkit.getPlayer(bt.b);
            if (b != null) {
                prompt(bt, b);
            } else {
                lang.broadcast("battle-forfeit", "loser", bt.nameB, "winner", bt.nameA);
                endBattle(bt, p, null);
            }
        } else if (!isA && bt.turn == 1) {
            bt.choiceB = choice;
            lang.sendMsg(p, "battle-wait", "skill", skillName(choice));
            resolveTurn(bt);
        } else {
            lang.sendMsg(p, "battle-not-your-turn");
        }
    }

    private void resolveTurn(Battle bt) {
        Player a = Bukkit.getPlayer(bt.a);
        Player b = Bukkit.getPlayer(bt.b);
        int ca = a != null ? dataMgr.getCP(a) : 0;
        int cb = b != null ? dataMgr.getCP(b) : 0;
        boolean shieldA = bt.choiceA == SKILL_SHIELD;
        boolean shieldB = bt.choiceB == SKILL_SHIELD;

        int dmgToB = 0, dmgToA = 0;
        boolean nullifyA = false, nullifyB = false;

        if (shieldA && shieldB) {
            // 双双牛盾
        } else if (shieldA) {
            // A 格挡 B
        } else if (shieldB) {
            // B 格挡 A
        } else if (bt.choiceA == bt.choiceB) {
            dmgToB = skillDamage(bt.choiceA, ca);
            dmgToA = skillDamage(bt.choiceB, cb);
        } else if (bt.choiceA == SKILL_TAIL) {
            dmgToB = skillDamage(bt.choiceA, ca);
            nullifyB = true;
        } else {
            dmgToA = skillDamage(bt.choiceB, cb);
            nullifyA = true;
        }

        // 怒气结算
        if (shieldA) bt.rageA = Math.min(config.rageMax, bt.rageA + config.skillShieldRage);
        else bt.rageA -= rageCost(bt.choiceA);
        if (shieldB) bt.rageB = Math.min(config.rageMax, bt.rageB + config.skillShieldRage);
        else bt.rageB -= rageCost(bt.choiceB);

        bt.hpB = Math.max(0, bt.hpB - dmgToB);
        bt.hpA = Math.max(0, bt.hpA - dmgToA);

        bt.rageA = Math.min(config.rageMax, bt.rageA + config.rageRegen);
        bt.rageB = Math.min(config.rageMax, bt.rageB + config.rageRegen);

        String bothPicked = LangManager.color(lang.prefix() + lang.t("battle-both-picked"));
        if (a != null) a.sendMessage(bothPicked);
        if (b != null) b.sendMessage(bothPicked);

        String actA = buildAction(bt.nameA, bt.choiceA, bt.nameB, dmgToB, nullifyA, shieldA, shieldB, bt.choiceB);
        String actB = buildAction(bt.nameB, bt.choiceB, bt.nameA, dmgToA, nullifyB, shieldB, shieldA, bt.choiceA);
        if (a != null) { a.sendMessage(LangManager.color(lang.prefix() + actA)); a.sendMessage(LangManager.color(lang.prefix() + actB)); }
        if (b != null) { b.sendMessage(LangManager.color(lang.prefix() + actA)); b.sendMessage(LangManager.color(lang.prefix() + actB)); }

        String hpLine = LangManager.color(lang.prefix() + lang.t("battle-hp", "a", bt.nameA, "hpA", String.valueOf(bt.hpA), "maxA", String.valueOf(bt.maxA),
                "b", bt.nameB, "hpB", String.valueOf(bt.hpB), "maxB", String.valueOf(bt.maxB),
                "rageA", String.valueOf(bt.rageA), "rageB", String.valueOf(bt.rageB)));
        if (a != null) a.sendMessage(hpLine);
        if (b != null) b.sendMessage(hpLine);

        boolean aDead = bt.hpA <= 0, bDead = bt.hpB <= 0;
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
            if (a != null) prompt(bt, a);
            else endBattle(bt, null, null);
        }
    }

    private String buildAction(String player, int skill, String target, int damage, boolean nullified, boolean shielded, boolean targetShielded, int opponentSkill) {
        if (shielded) return lang.t("battle-skill-shield", "player", player, "rage", String.valueOf(config.skillShieldRage));
        if (targetShielded) return lang.t("battle-skill-blocked", "player", player, "skill", skillName(skill), "target", target);
        if (nullified) return lang.t("battle-skill-clash", "player", player, "skill", skillName(skill), "target", target, "skill2", skillName(opponentSkill));
        return lang.t("battle-skill-hit", "player", player, "skill", skillName(skill), "target", target, "damage", String.valueOf(damage));
    }

    public void endBattle(Battle bt, Player winner, Player loser) {
        if (bt.over) return;
        bt.over = true;
        if (bt.timeoutTask != -1) {
            plugin.getServer().getScheduler().cancelTask(bt.timeoutTask);
            bt.timeoutTask = -1;
        }
        battles.remove(bt.a);
        battles.remove(bt.b);
        if (winner == null || loser == null) {
            lang.broadcast("battle-cancelled");
            return;
        }
        double wOld = dataMgr.getLength(winner);
        double lOld = dataMgr.getLength(loser);
        int grow = config.winGrowthMin + ThreadLocalRandom.current().nextInt(config.winGrowthMax - config.winGrowthMin + 1);
        int shrink = config.loseLossMin + ThreadLocalRandom.current().nextInt(config.loseLossMax - config.loseLossMin + 1);
        double wNew = Math.round((wOld + grow) * 10.0) / 10.0;
        double lNew = Math.max(config.minLength, Math.round((lOld - shrink) * 10.0) / 10.0);
        dataMgr.applyBattleResult(winner, loser, wNew, lNew);
        lang.broadcast("battle-win", "winner", winner.getName(), "loser", loser.getName());
        lang.broadcast("battle-grow", "winner", winner.getName(), "old", dataMgr.fmtLen(wOld), "new", dataMgr.fmtLen(wNew), "grow", String.valueOf(grow));
        lang.broadcast("battle-shrink", "loser", loser.getName(), "old", dataMgr.fmtLen(lOld), "new", dataMgr.fmtLen(lNew), "loss", String.valueOf((int) Math.round((lOld - lNew))));
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1.2f);
        if (loser.isOnline()) loser.playSound(loser.getLocation(), Sound.ENTITY_COW_HURT, 1, 0.6f);
    }
}