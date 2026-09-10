# 三角联结（DeltaNexus）

Minecraft 1.20.1 / Forge 47.4.x 的「制造 + 仓库」整合 Mod（缩写 `dn`，兼容 Mohist 混合服务端）。
版本 `0.2.0Beta`；模组 ID `deltanexus`。
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
- **货币三类型**（0.2.0Beta 起移除物品货币）：scoreboard（计分板，**默认**，目标 `dn_money` 由模组启动时自动创建）/
  vault（Vault 经济）/ playerpoints（PlayerPoints 点券）。旧配置 `currency_type=item` 启动时自动迁移为 scoreboard。
- **交易行**（0.2.0Beta）：系统商店——不新增任何物品，商品目录默认为空、由管理员逐件上架并**自定价格**；
  **卖出在仓库界面完成**（出售模式 → 多选 → 二次确认）。详见下文「交易行」章节。

## 指令（`/dn`，权限要求2，部分要求4；全部子指令支持 TAB 动态补全，`/dn help` 一行一指令）

```
open warehouse|special|manufacture|trade   打开仓库 / 特勤处 / 工作台总览 / 交易行（或 B / V / G 键）
reload                                热加载配方、升级树、工作台等全部 JSON 配置
export                                导出配置备份到 config/deltanexus/backup/
info                                  查看当前全部配置

setting speed <0.01-100>              制造速度倍率（只影响新任务）
setting queue <1-100>                 每工作台并行队列上限
setting mode offline|online           制造计时模式（离线/在线）
setting currency scoreboard <目标名>   计分板货币（默认 dn_money，模组自动创建目标）
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
perm get [目标]|set <目标> <warehouse|workbench|special|safe_box|trade|all> <allow|deny>|remove <目标>
perm default <...> <allow|deny>|op <allow|deny>|getop
feature get [目标]|set <目标> <allow|deny>      玩家 mod 功能总开关

trade list|get <商品id>                       交易行总览 / 商品详情
trade cat list|add <id> <显示名>|rename <id> <名>|remove <id>
trade good add <分类> <id> [显示名]            用主手物品上架（含 NBT 模板）
trade good remove|rename|move|enable|buyable <…>
trade item fromhand <id>|unit <id> <n>|mode <id> <id|full_nbt|partial_nbt>
trade item key add <id> <键> [exact|contains|specified] [值]|list|remove <…>
                                  （specified = 指定键名与对应值；旧 ignore 已弃用）
trade item durability <id> <on|off> [op] [值]   耐久度要求（= / < / > / <= / >=，默认关）
trade price <id> <buy|sell|market> fixed|formula|unset <…>   （code 脚本请用 Web/JSON）
trade limits <id> <min> <max> [block]         库存上下限（下限可作缺货阻断线）
trade stock <id> add <n>|set <n>              查看 / 补货
trade feed list|refresh|setting get|multiplier|feed_interval|timeout|sell_enabled|spread_guard|reload
                                 （sell_enabled 回收开关；spread_guard warn|block|off 价差保护）
                                 （玩家侧卖出：仓库界面点「出售」→ 多选物品 → 「确认」）

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
| （未绑定） | 打开交易行（默认不绑定，可在控制设置中指定，如 T） |

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
- **货币**：`scoreboard` / `vault` / `playerpoints` 三选一，**默认计分板**（目标 `dn_money`，服务器启动时自动创建）；
  `item` 物品货币已于 0.2.0Beta 移除，旧配置自动迁移；未安装对应插件时货币系统不可用（控制台与聊天均提示）。

## 交易行（0.2.0Beta）

- **形态**：系统商店（玩家↔系统）。商品目录默认为空，`/dn trade` 或 Web 编辑器逐件上架；玩家打开
  `/dn open trade`（或自设按键），一级界面左分类右商品列表，二级详情含 数量/当前价格/卖出价/买入价，
  可输入数量买入。**卖出**在仓库界面进行（见下）。
- **商品规格**：物品注册 id + NBT 模板（配置时用手持物品抓取）+ 匹配模式：
  `id`（只认物品）/ `full_nbt`（整份 NBT 精确）/ `partial_nbt`（只比指定键，键规则三选一：
  `exact`/`contains` 取值于模板，**`specified`＝指定键名与对应值**；旧 `ignore` 已弃用，读取时按“不参与匹配”处理）。
- **耐久度要求**（0.2.0Beta，默认关闭，可与任意匹配模式共存）：对可损坏物品比较
  **剩余耐久 = 最大耐久 − Damage**，运算符支持 `= / < / > / <= / >=`；不满足的物品不可回收、
  也不被视为该商品规格（买入生成物按模板生成，不受影响）。
- **价格策略**：每方向（buy 买入 / sell 回收 / market 参考）三种模式：
  - `fixed` 固定价；
  - `formula` 单行 JS 表达式，例如
    `floor(clamp(feed('moligod').price('1029') * 0.9 * (1 - 0.02*floor(good.stock/100)), 1, 1000000))`；
  - `code` JS 函数体 `function(ctx){ … }`（多行脚本用 Web/编辑 JSON 录入）。
  统一 Rhino 受限沙箱执行（禁 Java 访问、指令计数超时 `timeout_ms`），结果向下取整并 ≥1；
  0/负/异常 = 定价不可用，该商品禁止交易并告警。
- **ctx 变量**：`good.{id,displayName,stock,stockMin,stockMax,unitCount}`、`limits.{stockMin,stockMax}`、
  `qty`（本次数量）、`direction`（buy/sell/market）、`time.{hour,weekday,day}`、
  `feed(name)`（外部源：`rows/price(key)/find(cond)/meta`）、`clamp(x,lo,hi)` 与标准 `Math`。
- **库存与上下限**：运行时库存独立存 `trade-stock.json`（与定义文件分离，防高频写放大）；
  `min/max` 库存上下限对公式开放为变量；`block_below_min`（默认开）在 stock ≤ min（且 min>0）时禁止买入，
  限购量 = stock − min；库存由管理员 `trade stock add|set` 补货（指令与 Web 均有）。
- **买入结算**：服务端权威——校验上架/可买/库存/上下限 → 求价×全局倍率 → 货币（沿用当前货币，不足拒绝）
  → **格式背包兼容的空间预演**（仅解锁槽、足迹不越界、不压占位物、可合并同 NBT）→ 不足即“仓库空间不足”拒绝，
  不落入背包、不丢弃 → 落库并扣库存、重推目录。
- **卖出（回收，仓库界面）**：仓库界面行信息条左侧「出售」按钮进入出售模式——
  - **可回收来源（0.2.0Beta）：仓库 + 玩家背包/快捷栏 + 安全箱**（同一界面内直接点选；盔甲/副手不参与，防误卖）；
  - 左键点击 = 选中/取消**整堆**；右键 = 在「整堆 ⇄ 仅 1 个」间切换；选中格金框 + 数量角标，可回收格绿框；
  - 悬停格 tooltip 显示 `可回收：商品 · 单价/个`；按钮显示 `出售(N)`，行信息条右侧显示**预计总额**；
  - 点「出售」→ 按钮变「确认」（红色）→ 点「确认」提交选中的全部槽位（点别处/ESC 取消确认，ESC 再按退出模式）；
  - 服务端逐项权威校验：来源槽位（含解锁）/物品/匹配（优先级 `full_nbt > partial_nbt > id`，含耐久要求）/
    库存上限（`stock ≥ max` 停止回收）/价差保护（`sell ≥ buy` 时按 `spread_guard` 告警或禁止）/价格 ≥1
    → 扣物（按来源扣除）→ **计分板加分** → 加库存 → `arrange()` 清理占位物 → 刷新视口/背包/安全箱与余额；
    失败项跳过并汇总提示（如“回收已满 2 件、不可回收 1 件”）；
  - 付款失败会自动还原物品（不扣库存）；全局开关 `trade setting sell_enabled false` 可整体关闭回收。
- **客户端性能**：可回收判定只针对**当前打开的菜单槽位**（仓库视口 ≤108 + 背包 36 + 安全箱 ≤9，约 150 格）；
  商品按物品 id 分桶 + 优先级排序，索引随目录修订号缓存；NBT 模板预解析（`ItemSpec` 内部缓存）；
  每格匹配结果按“目录修订号 + 同步修订号”缓存，渲染每帧仅读缓存；tooltip 只在悬停格懒计算。商品数量很大时也不会卡顿。
- **外部价格源（Feed SPI）**：`com.deltanexus.system.trade.MarketFeed` + `TradeFeedRegistry`；
  核心在启动时于 mod 总线发布 `RegisterMarketFeedsEvent`。你的私有 moligod companion（仅服务端 mod、
  不进分发）可监听该事件注册 `feed('moligod')`：把快照行表（`{id,name,display_name,current_price,…}`）原样暴露，
  0 价歧义在 feed 侧剔除；分发版不含任何源细节。没有外部源时固定价/公式照常工作。
- **默认文件**：`config/deltanexus/trade.json`（分类/商品/全局设置）与 `trade-stock.json`（库存）首启自动生成，
  全部热加载（`/dn reload` 或 Web 刷新）。
- **第三方组件**：价格引擎内置 **Rhino 1.7.15**（Mozilla Public License 2.0 / GPL 双许可）的已编译类并直接并入模组
  包（dev 与正式 jar 均可用，无需另行安装）。

## 数据安全（0.2.0Beta）

- **备份策略**：每位有进度的玩家都会保留一份数据快照（内存 + `config/deltanexus/backup/<uuid>.dat`）。
  触发点：**濒死检测（tick 检测 health ≤ 0 / isDeadOrDying）**、`LivingDeathEvent`、**登出**；
  这样即使服务端/插件用 `setHealth(0)` 之类绕过死亡事件的路径，也能在数据被清空前拿到快照。
- **绝不空覆盖**：空标签或“无进度”的数据不会写入备份，避免把唯一副本覆盖成空白。
- **恢复点**：`PlayerEvent.Clone`（重生/换维度）→ 重生事件 → 登录兜底，任一环节发现实时数据为空即从备份恢复；
  **恢复后保留备份**（每人至少留一份）。
- 管理员 `/dn data <玩家> reset` 与 Web“重置玩家数据”会**显式清除备份**（否则登录兜底会把旧数据恢复回来）。

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
| `trade.json`（0.2.0Beta） | 交易行：全局设置（倍率/feed 间隔/超时/**回收开关/价差保护**）+ 分类 + 商品（规格/匹配/价格/库存上下限） |
| `trade-stock.json`（0.2.0Beta） | 交易行运行时库存（买入扣除 / 管理员补货） |
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

产物：`build/libs/deltanexus-0.2.0Beta.jar`

编译元数据（`gradle.properties` 的 `mod_license`）与模组列表展示的许可均为 **MIT**。

## 许可

- 本项目（三角联结 / DeltaNexus）以 **MIT License** 发布，全文见 [LICENSE](LICENSE)：
  可自由使用、修改、再分发与商用，保留版权声明即可，无任何担保。
- **第三方组件**：
  - **Rhino 1.7.15**（Mozilla Public License 2.0 / GPL 双许可）——交易行价格引擎的 JS 沙箱，
    以已编译类内置进模组 jar（`build.gradle` 的 `embedRhino`），未修改其源码；
  - Minecraft Forge / MCP 数据等 MDK 模板文件沿用其原始授权（见 `LICENSE.txt`），不在 MIT 覆盖范围内。

## 历史
- **1.0.0Alpha~2.0.6Alpha版本仅为内部版本，现以0.1.0Beta版本为基准，之后的版本号请以0.1.0Beta为基础**
- 旧项目名：三角洲系统（DeltaForceSystem，modid `deltaforcesystem`），指令前缀 `/dfs`。
- 0.1.0Beta：全面更名 **三角联结（DeltaNexus）**，modid `deltanexus`，指令 `/dn`，配置迁移至 `config/deltanexus/`，
  旧版存档与配置视为不兼容，首启自动生成默认配置。
- 0.2.0Beta（当前）：**交易行（系统商店）**、**仓库界面卖出/回收（仓库+背包+安全箱）**、货币默认计分板 `dn_money`
  （移除物品货币）、`specified` 键规则与商品耐久度要求、死亡数据丢失修复、协议 `dn1 → dn2`、许可改为 MIT。

> ### ⚠️ 版本号记录事故警示（必读）
>
> **事故**：交易行相关开发曾被错误地标成 `3.0` / `3.1Alpha`——交接文档里甚至写了两条 `3.0.0Alpha`、`3.1.0Alpha`
> “版本历史”行，README 的交易行/货币/配置章节也带着 `（3.0）`、`（3.1）` 标注；而项目真实版本号始终只是
> `gradle.properties` 的 `mod_version`（当时 `0.1.0Beta` → `0.2.0Beta`）。这些记录**已删除或改写**，内容全部并入
> **0.2.0Beta**；源码与 Web 页注释里的 `（3.0）`/`（3.1）` 也一并改为 `（0.2.0Beta）`。
>
> **纪律**（后续改动务必遵守）：
> 1. 任何文档、注释、提交信息、更新日志中的版本号，**只能取自 `gradle.properties` 的 `mod_version`**，
>    禁止按“功能模块数量”或“感觉规模”自行编号；
> 2. 开发中的功能一律记在**当前版本**下，**不得预写未来版本行**；版本号要升就先升 `mod_version`，再写记录；
> 3. `dn1` / `dn2` 是**网络协议版本**（`PacketHandler.PROTOCOL`），与模组版本号无关——协议升到 `dn2` 发生在 0.2.0Beta；
