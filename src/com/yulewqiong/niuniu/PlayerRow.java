package com.yulewqiong.niuniu;

/**
 * 玩家数据行：内存中玩家数据的唯一载体，字段与数据库表一一对应。
 */
public class PlayerRow {

    public String uuid;
    public double length = 0;
    public double seasonPeak = 0;
    public int stamina = 0;
    public String lastDate = "";
    public long cooldown = 0;
    public int wins = 0;
    public int battles = 0;
    public String buyDate = "";
    public int buysToday = 0;
}