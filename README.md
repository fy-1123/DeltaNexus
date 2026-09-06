# 三角联结（DeltaNexus）

Minecraft 1.20.1 / Forge 47.4.x 的「制造 + 仓库」整合 Mod（缩写 `dn`，兼容 Mohist 混合服务端）。
版本 `0.1.0Beta`；模组 ID `deltanexus`。
中文名 **三角联结**、指令前缀 `/dn`、控制台日志前缀 `[DN]`、配置目录 `config/deltanexus/`。

> 核心设计哲学：**配置热加载（不停机修改）、时间戳驱动（零 Tick 依赖）、增量网络包（省流量）**。

## 功能总览

- **仓库**：行式滚动渲染（默认 12 行，上限 64 行 = 576 格），0 级初始解锁 9 格，等级与槽位由升级树逐级解锁；界面内嵌安全箱面板。
- **制造台 / 配方**：配方三状态按钮（制造 / 制造中 / 领取），输入材料 NBT 精确匹配，材料从背包与仓库扣除，时间戳离线计时。
- **工作台总览**：全屏三竖列（G 键）——左选工作台、中选配方、右看详情。
- **特勤处**（V 键 / `/dn open special`）：仓库与安全箱升级独立界面，材料识别背包 + 仓库。
- **安全箱**：独立小仓储（默认 1x1，最大 3x3），0 级 = 默认尺寸，等级 > 0 按安全箱升级树解锁**行 x 列**。
- **格式背包**（格子格式）：≥9 格容器与背包按物品占用尺寸整理，跨格渲染、R 键旋转、快捷栏分级、物品尺寸/类背景色配置。
- **Web 网页编辑器**：浏览器可视化管理配置 / 工作台 / 配方 / 升级树 / 玩家数据 / 权限（`/dn web`）。
- **货币三类型**：item（物品，默认绿宝石）/ scoreboard（计分板）/ vault（Vault 经济）/ playerpoints（PlayerPoints 点券）。

## 指令（`/dn`，权限要求2，部分要求4；全部子指令支持 TAB 动态补全，`/dn help` 一行一指令）

```
open warehouse|special|manufacture    打开仓库 / 特勤处 / 工作台总览（或 B / V / G 键）
reload                                热加载配方、升级树、工作台等全部 JSON 配置
export                                导出配置备份到 config/deltanexus/backup/
info                                  查看当前全部配置

setting speed <0.01-100>              制造速度倍率（只影响新任务）
setting queue <1-100>                 每工作台并行队列上限
setting mode offline|online           制造计时模式（离线/在线）
setting currency item <物品ID>         物品货币（默认 minecraft:emerald）
setting currency scoreboard <目标名>   计分板货币
setting currency vault                Vault 经济
setting currency playerpoints         PlayerPoints 点券

warehouse rows <1-64>                 仓库总行数（每行 9 格，自动扩容以升级树为准）
warehouse slots <0-576>               0 级初始解锁槽位
safe size <宽> <高>                    安全箱默认尺寸（1x1 ~ 3x3）
safe get                              查看安全箱配置与升级树
safe add <等级>|remove <等级>           增删安全箱升级等级节点
safe cost <等级> <费用>                 设置安全箱升级费用
safe rows <等级> <行> <列>             设置安全箱解锁行 x 列
safe additem <等级>|delitem <等级> <索引>|setitem <等级> <索引> <数量>
safe restrict <item|any> <exact|contains> <nbt>   添加安全箱 NBT 限制
safe restrictions                      查看限制列表
safe unrestrict <索引>                 删除限制

workbench list|add <id> <显示名>|remove <id>|rename <id> <新名>
recipe list <工作台id>|get <配方id>
recipe add <工作台id> <配方id>|remove <配方id>|rename <配方id> <显示名>
recipe addinput|addoutput|delinput|deloutput|setinput|setoutput <配方id> ...   主手物品含 NBT
recipe time <配方id> <秒>|level <配方id> <等级>|parallel <配方id> <上限>|reload

tree get|add <等级>|remove <等级>|cost <等级> <费用>|slots <等级> <槽位数>
tree additem <等级>|delitem <等级> <索引>|setitem <等级> <索引> <数量>

data <玩家> get|level <等级>|slots <槽位数>|safe <等级>|reset
perm get [目标]|set <目标> <warehouse|workbench|special|safe_box|all> <allow|deny>|remove <目标>
perm default <...> <allow|deny>|op <allow|deny>|getop
feature get [目标]|set <目标> <allow|deny>      玩家 mod 功能总开关

grid list|size <宽> <高>|remove <物品ID>|hotbar <规则>
grid setclass <物品ID> <类名>|unsetclass <物品ID>
class list|set <类名> <R> <G> <B>|remove <类名>|setclass <物品ID> <类名>|unsetclass <物品ID>
web on|off                               启停 Web 网页编辑器
help                                    指令帮助
```

## 按键（键名汉化，可在 选项 -> 控制 -> 三角联结 修改）

| 按键 | 功能 |
| :-- | :-- |
| **B** | 打开仓库 |
| **G** | 打开工作台总览 |
| **V** | 打开特勤处（2.0.3 起不再放仓库内按钮） |
| **R** | 旋转光标物品（格式背包容器界面内） |

## 格式背包（格子格式）

- **网格作用域**：玩家背包（原版 E 界面 / 各菜单玩家区）、仓库（每行 9 格，滚轮滚动）、安全箱（最大 3x3）
  及全部 ≥9 格的原版/模组容器（箱子/潜影盒/末影箱/发射器等）。
- 物品按**占用尺寸**整理（剑 1x2、弓 1x3、潜影盒 3x3、矿物 2x2 等），跨格物品缩放渲染 + 墙底，
  占用格以占位物填充；合成格（3x3 工作台）与小于 9 格的容器保持原版槽位行为。
- **放置校验**：物品足迹必须完整落位，越界/重叠/占位物占用均拒绝，冲突自动重排；无处可放回指针。
- **同类叠加 / 任意格捡起**：点击跨格物品的非左上角格，可合并堆叠同类或捡起整件。
- **旋转**：容器界面内光标持有物品时按 R 键旋转 90°（服务端权威）。
- **锁定格语义**：仓库未解锁槽位 / 安全箱未解锁格不可落位、不可被跨格物品跨越。
- **快捷栏分级**：默认规则 `0-3:ANY, 4-8:GRID`（1-4 号格任意大小、5-9 号格仅 1x1），`/dn grid hotbar` 修改。
- **物品尺寸**：默认内置常见物品尺寸，`/dn grid size <宽> <高>` 自定义主手物品，存于 `config/deltanexus-sizes.json`。
- **物品「类」背景色**：每个物品可归属一个「类」，类决定跨格物品背景色，配置存于 `config/deltanexus/grid_classes.json`。

## 仓库滚动渲染

- 仓库按**行**动态向下渲染（视口 12 行），滚轮滚动查看全部行；总容量 = `warehouse_rows` x 9（默认 12 行，上限 64 行 = 576 格）。
- 未解锁行不渲染（不画底图、不显示锁图标），滚动范围限制在已解锁行内；修改后在线玩家容量自动扩容，无需重登。
- 右侧独立升级面板显示下一级费用（货币/材料，持有不足标红），升级后界面实时刷新。

## 安全箱

- 独立小仓储，三处入口：仓库界面内嵌面板、背包界面（原版 E 键）右侧覆盖层、特勤处升级。
- 解锁按**行 x 列形状**（如 2x2 = 2 行 2 列）动态渲染，未解锁格不渲染；默认尺寸 `/dn safe size` 修改。
- 升级树存于 `config/deltanexus/upgrade_tree.json` 的 `safe_box_upgrades`（费用 / 解锁行列 / 材料，解锁格按 3x3 左上角排布）。
- **NBT 限制**：拥有指定 NBT 的物品无法放入安全箱，规则存于 `config/deltanexus/safe_box_restrictions.json`
  （`/dn safe restrict|restrictions|unrestrict`）。

## 制造与货币

- **计时模式**：离线模式（默认，时间戳驱动，离线照常计时）/ 在线模式（仅在线时段计时）。
- **制造流程**：开始制造校验并扣除输入材料（NBT 按 exact/contains/ignore 匹配）；完成自动切「领取」按钮；
  `max_parallel` 限制同时制造数量（服务端同样校验）。
- **货币**：`item` / `scoreboard` / `vault` / `playerpoints` 四选一，默认物品货币 = 绿宝石；
  未安装对应插件时货币系统不可用（控制台与聊天均提示）。

## 配置目录（`config/deltanexus/`，热加载）

| 文件 | 内容 |
| :--- | :--- |
| `ModConfig.toml` | 仓库行数 / 0 级槽位 / 制造倍率与队列 / 货币类型 / 计时模式 / 安全箱默认尺寸 / GUI 白名单 |
| `client-ui.toml` | 客户端界面白名单与背包/容器界面替换开关 |
| `common.toml` | 快捷栏规则 hotbar_rules |
| `deltanexus-sizes.json`（config/ 根） | 物品占用尺寸 |
| `grid_classes.json` | 物品类颜色与归属 |
| `upgrade_tree.json` | 仓库 + 安全箱升级树 |
| `workbenches.json` | 工作台注册表 |
| `recipes/<workbench>/` | 配方 JSON（改文件后 `/dn reload`） |
| `permissions.json` | 全局默认 + 玩家覆盖权限，玩家功能开关 |
| `safe_box_restrictions.json` | 安全箱 NBT 限制 |
| `web-editor.yml` | Web 编辑器 host / port / public-url / token_auth |
| `backup/` | `/dn export` 与死亡备份输出 |

首次启动自动生成默认工作台、示例配方、升级树与权限等文件；之后任意修改 JSON 均可热加载，无需重启。

## Web 网页编辑器

- `/dn web` 查看状态与访问地址；`/dn web on|off` 启停并写入自动启动开关（enabled）。
- 默认监听 `0.0.0.0:21003`，`token_auth` 默认开启（token 仅存内存，重启失效）。
- 监听地址 / 端口 / 鉴权仅能手动编辑 `config/deltanexus/web-editor.yml`（防注入/防社工）。

## 构建

```bat
gradlew build
```

产物：`build/libs/deltanexus-0.1.0Beta.jar`

## 历史

- 旧项目名：三角洲系统（DeltaForceSystem，modid `deltaforcesystem`），指令前缀 `/dfs`。
- 0.1.0Beta：全面更名 **三角联结（DeltaNexus）**，modid `deltanexus`，指令 `/dn`，配置迁移至 `config/deltanexus/`，
  旧版存档与配置视为不兼容，首启自动生成默认配置。
