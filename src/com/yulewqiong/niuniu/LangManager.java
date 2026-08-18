package com.yulewqiong.niuniu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;

/**
 * 语言管理器：负责加载 lang.yml 并提供消息格式化与发送功能。
 */
public class LangManager {

    private final NiuNiu plugin;
    private FileConfiguration lang;
    private File langFile;

    public LangManager(NiuNiu plugin) {
        this.plugin = plugin;
    }

    public void load() {
        langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    public String prefix() {
        return lang.getString("prefix", "&7[&6牛牛&7] &r");
    }

    /**
     * 获取格式化后的消息文本（带颜色）。
     */
    public String t(String key, String... kv) {
        String s = lang.getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return color(s);
    }

    public void sendMsg(Player p, String key, String... kv) {
        p.sendMessage(color(prefix() + t(key, kv)));
    }

    public void broadcast(String key, String... kv) {
        Bukkit.broadcastMessage(color(prefix() + t(key, kv)));
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}