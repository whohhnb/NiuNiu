# 🐮 NiuNiu 牛牛对战系统

一个幽默风格的 Minecraft 养成 + 回合制对战插件。培养你的「牛牛」，与其他玩家一决高下，冲击赛季排行榜冠军。

- **平台**：Paper 1.21+
- **语言**：Java 21
- **构建**：Maven

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🍼 养成系统 | 「打胶」让牛牛成长，每打一次必成功 +N cm，有冷却 |
| ⚔️ 回合制对战 | 三种技能（甩击 / 筹茬 / 牛盾），怒气系统 + 克制关系 |
| 🏆 赛季排行榜 | 按赛季最高长度排名，赛季冠军获得永久称号 |
| 🔋 体力系统 | 每日自动回满，可花金币购买，每日限购 |
| 💾 数据库存储 | SQLite（默认）或 MySQL，数据唯一持久化源 |
| 🖥️ GUI 界面 | 主菜单 / 选人 / 排行榜 / 玩家信息，一键操作 |

### 回合制对战详解

每个牛牛有 **HP** 与 **怒气**，回合双方同时选择技能：

| 技能 | 编号 | 效果 | 怒气消耗 |
|------|------|------|----------|
| 猛烈甩击 | 1 | 高伤害，可打断「暴力筹茬」 | 高 |
| 暴力筹茬 | 2 | 中伤害，低消耗 | 低 |
| 牛盾 | 3 | 格挡所有伤害，回复怒气 | 回复 |

克制关系：甩击打断筹茬 → 牛盾格挡甩击 → 筹茬稳定输出，形成剪刀石头布博弈。

---

## 📦 依赖

| 插件 | 必需 | 用途 |
|------|------|------|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | ✅ 必须 | 经济系统（购买体力） |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 可选 | 提供变量（如 `%niu_length%`） |
| [PlayerTitle](https://www.spigotmc.org/resources/) | 可选 | 赛季冠军授予永久称号 |
| [PlayerMenu](https://www.spigotmc.org/resources/) | 可选 | 返回服务器主菜单 |

---

## 📥 安装

1. 下载 `NiuNiu-<版本>.jar`
2. 放入服务器 `plugins/` 目录
3. 确保已安装 Vault（及配套经济插件）
4. 重启或 `/reload` 服务器
5. 编辑 `plugins/NiuNiu/config.yml` 按需配置

---

## ⚙️ 配置

### 存储后端（数据库是唯一数据源）

```yaml
storage:
  type: sqlite          # sqlite（本地单文件，默认）或 mysql（远程）
  sqlite-file: 'niuniu.db'
  table-prefix: 'niuniu_'   # 多服共用同一库时的表前缀
  pool-max-size: 5
  pool-min-idle: 1

mysql:                   # type: mysql 时生效
  host: localhost
  port: 3306
  database: niuniu
  username: root
  password: ''
```

### 切换存储后端

若要从 SQLite 迁移到 MySQL（或反向）：

1. 修改 `storage.type` 并填好目标数据库连接信息
2. 重启服务器（此时插件连上「目标」后端）
3. 控制台执行 `niu migrate`，即把「源」后端数据完整迁移到当前后端

> 参考了 CMI 的 `/cmi migratedatabase` 设计。

### 玩法参数

`settings` 段可调整体力、成长范围、冷却、技能伤害、赛季天数等，详见 `config.yml` 内置注释。

---

## 🎮 命令

主命令：`/niu`（别名 `niuniu`、`cow`、`niuNiu`）

| 命令 | 说明 |
|------|------|
| `/niu` | 打开主菜单 |
| `/niu battle <玩家>` | 向指定玩家发起对战 |
| `/niu brush` | 打胶（牛牛成长，有冷却） |
| `/niu buy` | 花金币购买体力 |
| `/niu top` | 查看赛季排行榜 |
| `/niu season` | 查看赛季信息 |
| `/niu accept` / `/niu decline` | 接受 / 拒绝对战邀请 |
| `/niu skill <1/2/3>` | 对战时选择技能 |
| `/niu forcebrush [玩家]` | 管理员强制打胶 |
| `/niu setlength <玩家> <长度> [-p]` | 管理员设置玩家长度（`-p` 同时覆盖赛季最高） |
| `/niu season start` | 管理员强制开启新赛季 |
| `/niu migrate` | 迁移数据到另一存储后端 |
| `/niu reload` | 重载配置 |

---

## 🔑 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `niu.admin` | 管理员（强制打胶 / 开赛季 / 重载 / 迁移） | OP |
| `niu.bypass.cooldown` | 无视打胶冷却 | OP |
| `niu.setlength` | 设置玩家长度（/niu setlength） | OP |

---

## 📊 PlaceholderAPI 变量

安装 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 后即可使用以下变量（`niuniu` 与 `niu` 等价）。

### 个人变量

| 变量 | 说明 | 示例 |
|------|------|------|
| `%niu_length%` | 当前长度（1 位小数） | `15.6` |
| `%niu_length_raw%` | 当前长度（原始值） | `15.6` |
| `%niu_peak%` | 赛季最高长度 | `26.4` |
| `%niu_stamina%` | 当前体力 | `90` |
| `%niu_max_stamina%` | 体力上限 | `100` |
| `%niu_cp%` | 战斗力 | `41` |
| `%niu_rank%` | 评级（纯文本） | `小牛` |
| `%niu_rank_color%` | 评级（带颜色码） | `&a小牛` |
| `%niu_max_hp%` | 最大 HP | `91` |
| `%niu_wins%` | 胜场 | `3` |
| `%niu_battles%` | 总场次 | `5` |
| `%niu_winrate%` | 胜率 | `60%` |
| `%niu_cooldown%` | 打胶冷却剩余秒数 | `0` |
| `%niu_buys_today%` | 今日已购次数 | `2` |
| `%niu_buy_limit%` | 每日限购 | `5` |
| `%niu_season%` | 当前赛季号 | `2` |
| `%niu_season_days_left%` | 赛季剩余天数 | `7` |
| `%niu_has_cow%` | 是否已激活牛牛 | `true` |

### 排行榜变量（全局）

数字可替换为任意名次，按赛季最高长度降序。

| 变量 | 说明 |
|------|------|
| `%niu_top_name_1%` | 第 N 名玩家名 |
| `%niu_top_length_1%` | 第 N 名当前长度 |
| `%niu_top_peak_1%` | 第 N 名赛季最高 |
| `%niu_top_wins_1%` | 第 N 名胜场 |

> 离线玩家支持 `length` / `peak` / `wins` / `battles` / `winrate` / `has_cow`，其余变量需在线。

---

## 🔨 构建

```bash
mvn clean package -DskipTests
```

产物输出至 `target/NiuNiu-<版本>.jar`（已通过 shade 打包 SQLite / MySQL 驱动与 HikariCP）。

---

## 📄 许可

本项目采用 [MIT License](LICENSE)。

---

> 尊贵的打胶人士，冲鸭！ 🐮