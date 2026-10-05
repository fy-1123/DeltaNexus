# 三角联结（DeltaNexus）

Minecraft 1.20.1 / Forge 47.4.x 的「制造 + 仓库」整合 Mod。缩写 `dn`，兼容 Mohist 混合服务端。  
当前版本 `0.5.0Beta`；模组 ID `deltanexus`；中文名三角联结；指令前缀 `/dn`；日志前缀 `[DN]`；配置目录 `config/deltanexus/`；网络协议 `dn3`。  
核心设计：配置热加载、时间戳驱动、增量网络包。

## 功能总览

- **仓库**：行式滚动渲染，默认 12 行，上限 64 行 = 576 格；0 级初始解锁 9 格，升级树逐级解锁；界面内嵌安全箱。
- **制造台 / 配方**：三状态按钮（制造 / 制造中 / 领取），输入材料 NBT 精确匹配，从背包与仓库扣料，时间戳离线计时。
- **工作台总览**：G 键全屏三竖列：左选工作台、中选配方、右看详情。
- **特勤处**：V 键 / `/dn open special`，仓库与安全箱升级独立界面。
- **安全箱**：独立小仓储，默认 1x1，最大 3x3；等级 > 0 按行 x 列解锁；支持 NBT 限制。
- **格式背包**：≥9 格容器与背包按物品占用尺寸整理，跨格渲染，R 旋转，快捷栏分级，物品尺寸/类背景色可配。
- **装备物品化**：胸挂 `rig` 默认 4x3，背包 `backpack` 默认 6x4；内容随物品 NBT 携带；在内嵌网格中**右键**胸挂 / 背包可打开浮动窗口（Windows 风格，按住标题栏拖动、右上角关闭）查看与操作其内容，或用 `/dn gear` 管理；装备仅在**递归地**内部为空时可被放入另一件装备（装着一个空背包的背包同样算空），最多嵌套 7 层；套包（嵌套层）只接受胸挂/背包，普通物品一律拒绝；原版背包部分格由装备取代。
- **Web 网页编辑器**：`/dn web` 管理配置、工作台、配方、升级树、安全箱、玩家数据、权限、格式背包、交易行、刷兵系统（含从在线玩家取点位坐标）。
- **货币三类型**：`scoreboard`（默认，目标 `dn_money`）/ `vault` / `playerpoints`；物品货币已移除。
- **交易行**：系统商店，不新增物品；商品目录默认为空，管理员逐件上架并自定价格；卖出在仓库界面完成。
- **刷兵系统**：零活动设计，不监听事件、不自动刷兵，仅 `/dn spawner run` 按请求刷一次。

## 按键

| 按键 | 功能 |
|:---|:---|
| 未绑定 | 打开仓库 |
| 未绑定 | 打开工作台总览 |
| 未绑定 | 打开特勤处 |
| **R** | 旋转光标物品（格式背包容器界面内） |
| 未绑定 | 打开交易行（默认不绑定） |

键名已汉化，可在 `选项 -> 控制 -> 三角联结` 修改。

## 指令

权限要求 2，部分要求 4；全部子指令支持 TAB 动态补全，`/dn help` 一行一指令。

```text
open warehouse|special|manufacture|trade   打开仓库 / 特勤处 / 工作台总览 / 交易行（或 B / V / G 键）
reload                                热加载配方、升级树、工作台等全部 JSON 配置
export                                导出配置备份到 config/deltanexus/backup/
info                                  查看当前全部配置

setting speed <0.01-100>              制造速度倍率（只影响新任务）
setting queue <1-100>                 每工作台并行队列上限
setting mode offline|online           制造计时模式（离线/在线）
setting currency scoreboard <目标名>   计分板货币（默认 dn_money）
setting currency vault                Vault 经济
setting currency playerpoints         PlayerPoints 点券

warehouse rows <1-64>                 仓库总行数（每行 9 格）
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
recipe addinput|addoutput|delinput|deloutput|setinput|setoutput <配方id> ...
recipe time <配方id> <秒>|level <配方id> <等级>|parallel <配方id> <上限>|reload

tree get|add <等级>|remove <等级>|cost <等级> <费用>|slots <等级> <槽位数>
tree additem <等级>|delitem <等级> <索引>|setitem <等级> <索引> <数量>

data <玩家> get|level <等级>|slots <槽位数>|safe <等级>|reset
perm get [目标]|set <目标> <warehouse|workbench|special|safe_box|trade|gear|all> <allow|deny>|remove <目标>
perm default <...> <allow|deny>|op <allow|deny>|getop
feature get [目标]|set <目标> <allow|deny>

trade list|get <商品id>
trade cat list|add <id> <显示名>|rename <id> <名>|remove <id>
trade good add <分类> <id> [显示名]            用主手物品上架（含 NBT 模板）
trade good remove|rename|move|enable|buyable <…>
trade item fromhand <id>|unit <id> <n>|mode <id> <id|full_nbt|partial_nbt>
trade item key add <id> <键> [exact|contains|specified] [值]|list|remove <…>
trade item durability <id> <on|off> [op] [值]   耐久度要求（= / < / > / <= / >=，默认关）
trade price <id> <buy|sell|market> fixed|formula|unset <…>
trade limits <id> <min> <max> [block]         库存上下限
trade stock <id> add <n>|set <n>              查看 / 补货
trade feed list|refresh|setting get|multiplier|feed_interval|timeout|sell_enabled|spread_guard|reload
trade setting sell_enabled false              关闭回收
trade setting spread_guard warn|block|off     价差保护

grid list|size <宽> <高>|remove <物品ID>|hotbar <规则>
grid setclass <物品ID> <类名>|unsetclass <物品ID>
class list|set <类名> <R> <G> <B>|remove <类名>|setclass <物品ID> <类名>|unsetclass <物品ID>
grid containers|register|unregister <类名>    容器注册管理

gear set <backpack|rig> <宽> <高>         手持物品登记为该类装备（上限 9×6）
gear remove|list                        取消手持物品登记 / 列出全部登记项
gear equip                              手持装备穿上并打开容器
gear unequip <backpack|rig>|open <backpack|rig>   卸下并放回背包 / 打开已装备容器

web on|off                               启停 Web 网页编辑器
spawner new|del|list|info <…>              刷兵配置管理
spawner setup <名字>                       配置向导
spawner point|group|set|nbt <…>            点位、点位组、字段、NBT 设置
spawner run|preview|dry-run <…>            执行 / 预演 / 干跑刷兵
spawner log <…>                            查看刷兵日志配置
help                                    指令帮助
```

## 配置目录

`config/deltanexus/`，热加载。

| 文件 | 内容 |
|:---|:---|
| `ModConfig.toml` | 仓库行数 / 0 级槽位 / 制造倍率与队列 / 货币类型 / 计时模式 / 安全箱默认尺寸 / GUI 白名单 |
| `client-ui.toml` | 客户端界面白名单与背包/容器界面替换开关 |
| `common.toml` | 快捷栏规则、网格容器注册、兼容开关 |
| `deltanexus-sizes.json`（config/ 根） | 物品占用尺寸 |
| `grid_classes.json` | 物品类颜色与归属 |
| `upgrade_tree.json` | 仓库 + 安全箱升级树 |
| `workbenches.json` | 工作台注册表 |
| `recipes/<workbench>/` | 配方 JSON |
| `permissions.json` | 全局默认 + 玩家覆盖权限，玩家功能开关 |
| `safe_box_restrictions.json` | 安全箱 NBT 限制 |
| `trade.json` | 交易行全局设置 + 分类 + 商品 |
| `trade-stock.json` | 交易行运行时库存 |
| `web-editor.yml` | Web 编辑器 host / port / public-url / token_auth |
| `backup/` | `/dn export` 与死亡备份输出 |
| `spawner/global.json` | 刷兵全局 |
| `spawner/logging.json` | 刷兵日志 |
| `spawner/<世界文件夹>/spawners.json` | 刷兵器定义 |
| `spawner/<世界文件夹>/pointgroups.json` | 点位组 |

首次启动自动生成默认文件；之后修改 JSON 可热加载。

## 格式背包与网格内核（0.5.0Beta）

0.5.0Beta 内核重写为 `GridStore`。协议仍为 `dn3`，客户端/服务端必须同版本。

### 核心模型：只存锚点

| 层 | 内容 |
|:---|:---|
| `GridStore` | 只存锚点 `LinkedHashMap<Integer, GridEntry>`，占用由 `footprint/occupancy` 现算 |
| 写入口 | `place / take / move / rotate / compact`，唯一写路径 |
| `GridEntry` | 不可变 record |
| `GridNbt` | `{format_version:1, entries:[…]}`，旧平铺格式自动迁移并保留 `.bak` |
| `GridHandlerBridge` | 把 `GridStore` 暴露成旧 `ItemStackHandler` 形态 |
| `GridMarker` | `{ DeltaNexus: { rot, gear } }`，历史键只读兼容，不再新写 |
| `StoreContainer` | 把「只存锚点」模型暴露成原版容器 |

### 关键保证

- **无占位物**：足迹内非锚点格就是空；`blocked_slot` 只作为历史数据被检测并清理。
- **不可能复制**：`move/rotate/compact` 强制守恒；`place/take` 明确标注物品进出容器；提交前后校验实例共享。
- **不可能重叠**：`validate()` 任意时刻可判定；非法即回滚。锁定格上允许既有物品，不因此丢物品。
- **单一几何引擎**：布局下发、点击解析、交易交付、整理全部以 `GridStore` 条目为准。
- **构造期不回调**：`GridHandlerBridge` 构造期不得回调 `Access`，持有者构造完成后调用 `refreshUsable()`。
- **每 tick 只读校验**：容器由内核自持体检；玩家背包组只在检测到违规时做一次合法位移。

### 格式背包行为

- 作用域：玩家背包、仓库（每行 9 格）、安全箱（最大 3x3）、已注册容器。
- 物品按占用尺寸整理；未配置尺寸一律 1x1；跨格物品缩放渲染 + 墙底。
- 点击跨格物品任意格与点击主格同一路径：左键、右键、Shift、数字键、Q、拖拽全部重定向到主格。
- 放置校验：足迹必须完整落位，越界/重叠拒绝，冲突自动重排，无处可放回指针。
- 同类叠加：点击非左上角格可合并堆叠同类；整件拿起走主格。
- 旋转：R 键旋转 90°，服务端权威校验。
- 锁定格：仓库未解锁槽位 / 安全箱未解锁格不可落位、不可被跨越。
- 快捷栏分级：默认 `0-8:ANY`（快捷栏 1-9 号格无视尺寸，任意大小都能放）；
  **口袋**（主背包前 5 格）固定只收 1x1 的普通物品——大件与胸挂/背包不进任何口袋。
- 物品尺寸：`/dn grid size <宽> <高>`。
- 物品类背景色：`/dn grid setclass`。
- 容器注册：`/dn grid containers` 查看；`/dn grid register|unregister <类名>` 增删；`legacy_any_container = true` 可恢复旧行为。
- 外部写入软闸：管道/漏斗等外部插入在 `insertItem` / `isItemValid` 里拒绝写进足迹内。

### 排障速查

- `[DN] 网格格式迁移完成：条目 N 件（原位保留 x，移位 y，退化 1x1 z）`：首次加载旧档。
- `[DN] 网格引擎异常（已隔离…）` + 堆栈：网格内异常，请上报。
- `[DN] 出售模式：目录商品 N 件（带卖出价 M 件），本界面可回收槽位 K 个`：出售识别诊断。
- `[DN] 网络通道：协议 dn3，已注册 N 个包`：排障用，不做比对。

## 仓库滚动渲染

- 按行动态向下渲染，视口 12 行，滚轮滚动；总容量 = `warehouse_rows` x 9。
- 未解锁行不渲染；滚动范围限制在已解锁行内；修改后在线玩家容量自动扩容。
- 右侧升级面板显示下一级费用，持有不足标红；升级后实时刷新。

## 安全箱

- 独立小仓储，三处入口：仓库内嵌面板、背包界面右侧覆盖层、特勤处升级。
- 按行 x 列形状动态渲染；默认尺寸 `/dn safe size` 修改。
- 升级树存于 `config/deltanexus/upgrade_tree.json` 的 `safe_box_upgrades`。
- NBT 限制存于 `config/deltanexus/safe_box_restrictions.json`。

## 制造与货币

- 计时模式：离线模式（默认，时间戳驱动）/ 在线模式。
- 制造流程：开始制造校验并扣除输入材料；完成自动切「领取」；`max_parallel` 限制同时制造数量。
- 货币：`scoreboard` / `vault` / `playerpoints` 三选一，默认计分板 `dn_money`；未安装对应插件时货币系统不可用。

## 交易行

- 形态：系统商店。商品目录默认为空，管理员逐件上架；玩家打开 `/dn open trade` 或自设按键。
- 商品规格：物品注册 ID + NBT 模板 + 匹配模式 `id` / `full_nbt` / `partial_nbt`。
- 键规则：`exact` / `contains` / `specified`；旧 `ignore` 已弃用。
- 耐久度要求：默认关闭，可比较剩余耐久 `= / < / > / <= / >=`。
- 价格策略：`fixed` / `formula` / `code`；统一 Rhino 受限沙箱，结果向下取整且 ≥1；0/负/异常禁止交易并告警。
- `ctx` 变量：`good`、`limits`、`qty`、`direction`、`time`、`feed(name)`、`clamp` 与标准 `Math`。
- 库存：运行时库存独立存 `trade-stock.json`；`min/max` 上下限对公式开放；`block_below_min` 默认开。
- 买入结算：服务端权威，校验上架/可买/库存/上下限 → 求价×倍率 → 货币 → 格式背包空间预演 → 落库扣库存。
- 卖出：在仓库界面完成。可回收来源为仓库 + 玩家背包/快捷栏 + 安全箱；左键整堆，右键整堆⇄1 个；确认后提交。
- 出售模式由服务端持有，冻结一切物品移动；屏幕关闭、菜单销毁、断线解除。
- 性能：可回收判定只针对当前菜单槽位；商品按 ID 分桶 + 优先级排序；缓存按目录修订号 + 同步修订号。
- Feed SPI：`MarketFeed` + `TradeFeedRegistry`；外部源可注册 `feed('moligod')`。
- 默认文件：`trade.json` 与 `trade-stock.json` 首启生成，全部热加载。
- 第三方：内置 Rhino 1.7.15 已编译类。

## 数据安全

- 每位有进度玩家保留数据快照：内存 + `config/deltanexus/backup/<uuid>.dat`。
- 触发点：濒死检测、`LivingDeathEvent`、登出。
- 空标签或“无进度”数据不写入备份，避免空覆盖。
- 恢复点：`PlayerEvent.Clone`、重生事件、登录兜底；恢复后保留备份。
- `/dn data <玩家> reset` 与 Web 重置会显式清除备份。

## 刷兵系统

零活动设计：不监听事件、不自动刷兵、无冷却/定时/波次。仅在 `/dn spawner run` 请求时按当前配置刷一次。

- 全局：`spawner/global.json` 总开关、黑名单世界、实体数 / TPS / 内存上限。
- 日志：`spawner/logging.json`，输出到 `logs/deltanexus/spawn/<日期>.log`。
- 世界级：`spawner/<世界文件夹>/spawners.json` 与 `pointgroups.json`。
- 刷兵器：实体池或单实体、点位、点位组、数量区间、条件、安全寻位、全局上限、粒子/音效/命令/日志。
- 执行流程：校验实体池 → 全局限制 → 条件 → 目标点位与数量 → 安全寻位 → 应用 NBT/行为。
- `preview` 只做静态推算；`dry-run` 走完整流程但不生成实体。
- 指令：`/dn spawner new|del|list|info`、`point`、`group`、`set`、`nbt`、`run|preview|dry-run`、`log`。

## Web 网页编辑器

- `/dn web` 查看状态与访问地址；`/dn web on|off` 启停并写入自动启动开关。
- 默认监听 `0.0.0.0:21003`，`token_auth` 默认开启；token 仅存内存，重启失效。
- 监听地址 / 端口 / 鉴权仅能手动编辑 `config/deltanexus/web-editor.yml`。
- 特性：记住上次页签；`Ctrl+K` 全局搜索（配方 / 工作台 / 商品 / 玩家 / 刷兵器 / 点位组）；配方/交易列表分页 + 密度列与筛选；配方批量改耗时/等级与一键复制；顶部下载配置全量 JSON 备份。
- **刷兵系统页**：全局保护（总开关 / 黑名单世界 / 实体与 TPS 上限）、日志（级别 / 开关 / 文件路径）、世界层刷兵器与点位组全量编辑；刷兵器支持实体或加权实体池、数量（`3-6` / `per_point:2`）、点位名或 `group:组名` 引用、难度与在线人数条件、安全检测、生成后行为（发光/持久/静音/无 AI、粒子、音效、命令）、实体 NBT（JSON 形式）；每一行可**预览 / 干跑 / 执行一次**（零活动，只有点这一下才刷）。
- **从在线玩家取坐标**：点位表格里每个点位都有「取该玩家坐标」，或用「以该玩家为点位」直接以选中玩家的当前位置新建点位（重名自动加序号）；维度与页面顶部「执行维度」不一致时会提示。位置数据来自 `GET /api/players/positions`（也随概览与 SSE 一起下发）。

## 构建与许可

```bat
gradlew build
```

产物：`build/libs/deltanexus-0.5.0Beta.jar`

- 本项目以 **MIT License** 发布，可自由使用、修改、再分发与商用，保留版权声明即可，无担保。
- 第三方组件：
  - **Rhino 1.7.15**（MPL 2.0 / GPL 双许可），交易行价格引擎 JS 沙箱，以已编译类内置进模组 jar，未修改源码。
  - Minecraft Forge / MCP 数据等 MDK 模板文件沿用原始授权，不在 MIT 覆盖范围内。

## 版本历史（新上旧下）

- **0.5.0Beta（当前）**：格子背包内核重写 + 装备物品化。
  - `GridStore` 成为格子容器唯一一等公民存储模型：只存锚点、占用现算、唯一写入口 `place/take/move/rotate/compact`，走「影子推演 → 不变式 → 守恒 → 提交或整体放弃」；`validate()` 任意时刻可判定。
  - 条目改为不可变 `GridEntry`；存档统一为 `GridNbt`，首次加载自动迁移旧平铺格式并保留 `.bak`。
  - `GridHandlerBridge` 重写并迁到 `grid/` 根，作为仓库/安全箱接线层。
  - 统一 NBT 标记 `GridMarker`：`{ DeltaNexus: { rot, gear } }`，历史键只读兼容且不再新写。
  - 装备物品化：胸挂 `rig` 默认 4x3，背包 `backpack` 默认 6x4，内容随物品 NBT 走；统一裁决入口 `GearService`。
  - 新增 `/dn gear set|remove|list|equip|unequip|open`。
  - 权限新增第 6 类 `gear`，六类型：仓库/工作台/特殊/安全箱/交易/装备；新增 `features` 硬开关。
  - Web 统一物品选择器；清理废弃物品；新增 Mixin。
  - 协议仍为 `dn3`。
- **0.4.2Beta**：工作量台倍率改为速度倍率；Web 编辑器全面重写与侧边栏导航；交易行价格求值优化；仓库行数上限 256。
- **0.4.1Beta**：Web 编辑器可用性大改：记住上次页签 + `Ctrl+K` 全局搜索；配方/交易列表分页与密度列；配方批量改参与一键复制；修复 `recipeBatch` NPE 等。
- **0.4.0Beta**：刷兵系统（零活动设计，仅供 `/dn spawner run` 按需刷一次）；`/dn spawner` 指令树；配置分层热加载。
- **0.3.0Beta**：格式背包内核重写，`grid` 拆成 `core` 与 `adapter`；菜单层统一点击重定向；占位物统一为容器索引、绝不落盘；仓库/安全箱禁止抽取或覆盖占位物；容器显式注册；渲染跳过隐藏槽；旋转标记改 `deltanexus.grid.rotated`；新增 7 个内核 GameTest。
- **0.2.1Beta**：出售交互修复。未选中物品时按钮显示「取消」；按钮按 18×18 槽位外框对齐；出售模式下物品完全冻结。
- **0.2.0Beta**：交易行（系统商店）；仓库界面卖出/回收（仓库 + 背包 + 安全箱）；货币默认计分板 `dn_money`；移除物品货币；`specified` 键规则与商品耐久度要求；死亡数据丢失修复；协议 `dn1 → dn2`；许可改为 MIT。
- **0.1.0Beta**：全面更名三角联结（DeltaNexus），modid `deltanexus`，指令 `/dn`，配置迁移至 `config/deltanexus/`；旧版存档与配置视为不兼容，首启自动生成默认配置。
- **旧项目名**：三角洲系统（DeltaForceSystem，modid `deltaforcesystem`），指令前缀 `/dfs`。
- **1.0.0Alpha~2.0.6Alpha**：仅为内部版本，现以 0.1.0Beta 版本为基准。

## 版本号纪律

- 任何文档、注释、提交信息、更新日志中的版本号，只能取自 `gradle.properties` 的 `mod_version`。
- 禁止按“功能模块数量”或“感觉规模”自行编号。
- 开发中的功能一律记在当前版本下，不得预写未来版本行。
- `dn1` / `dn2` / `dn3` 是网络协议版本，与模组版本号无关：
  - `dn1 → dn2` 在 0.2.0Beta；
  - `dn2 → dn3` 在 0.3.0Beta，覆盖出售模式包与网格布局包。