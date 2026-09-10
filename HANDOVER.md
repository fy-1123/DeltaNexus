# 三角联结（DeltaNexus）交接文档

> 版本：0.2.0Beta（Forge 1.20.1 / Minecraft 1.20.1，兼容 Mohist 混合服务端
> 模组 ID：`deltanexus`｜显示名：三角联结｜指令：`/dn`｜日志前缀：`[DN]`｜许可：**MIT**（见根目录 `LICENSE`）

---

## 一、项目概述

三角联结是一个**制造 + 仓库 + 格子格式背包**整合型 Mod，核心设计哲学：

- **配置热加载**：配方/升级树/工作台/权限/安全箱限制全部 JSON 化，`/dn reload` 即时生效；
- **时间戳驱动**：制造任务零 Tick 依赖，离线照常计时（时间戳差计算）；
- **增量网络包**：同步包只发轻量字段，仓库物品走容器标准同步；
- **格子格式背包**：背包/仓库/安全箱及全部 ≥9 格容器按物品占用尺寸
  网格化整理、跨格渲染、R 键旋转、物品「类」背景色。

### 版本历史

**1.0.0Alpha~2.0.6Alpha版本仅为内部版本，现以0.1.0Beta版本为基准，之后的版本号请以0.1.0Beta为基础**

| 版本 | 内容 |
| :--- | :--- |
| 1.0.0Alpha~1.1.0Alpha | 制造台 + 仓库翻页 + 安全箱 + 权限管理 + Web 编辑器基线 |
| 2.0.0Alpha | 格式背包模块 |
| 2.0.1Alpha | 仓库翻页→滚轮滚动（12 行视口）、安全箱升级树行列制、网格性能与放置校验 |
| 2.0.2Alpha | 特勤处独立升级界面、格式背包配置指令/Web、无处可放回指针、同类叠加 |
| 2.0.3Alpha | 特勤处 V 键+独立权限、安全箱行列形状渲染、材料识别仓库、物品类背景色、非左上角捡起 |
| 2.0.4Alpha | 死亡备份恢复、12 行视口、放置自动旋转、旋转可绑键、特勤处打开修复、安全箱跨格渲染修复 |
| 2.0.5Alpha | 死亡重生兜底、base_slots 迁移与登录补齐、旋转按键改屏幕事件、1×1 类色渲染、撤销点击移动 |
| 2.0.6Alpha | Web 权限页特勤处支持、登录补齐强制 12 行、类命令顶层别名、死亡备份日志 |
| 0.1.0Beta | 项目更名 **三角联结（DeltaNexus）**：modid `deltanexus`、指令 `/dn`、日志前缀 `[DN]`、包名 `com.deltanexus.system`、配置目录 `config/deltanexus/`、NBT 键 `deltanexus.*`；旧档与旧配置视为不兼容 |
| 0.2.0Beta | **交易行 + 仓库卖出/回收 + 货币改造 + 数据安全**（本版本一次性发布，此前误记为 `3.0.0Alpha`/`3.1.0Alpha` 的内容全部归入本行）：<br>①**交易行（系统商店）**：目录默认空、管理员逐件上架并自定价格；价格三模式 fixed/formula/code（Rhino JS 受限沙箱统一执行）+ ctx 库存/时间/方向/feed 变量；Feed SPI（私有 moligod companion 仅服务端注册外部价格源）；匹配模式 id/full_nbt/partial_nbt，`ignore` 弃用 → **`specified`（指定键名与对应值）**；商品**耐久度要求**（默认关，= / < / > / <= / >= 比较剩余耐久，可与匹配模式共存）；库存上下限 + 补货；买入落玩家仓库（格式背包空间判定，不足禁买并提示）；`/dn open trade` + 未绑定按键 + `trade` 权限 + `/dn trade` 管理指令 + Web 交易行页；<br>②**仓库界面卖出（回收）**：来源 = 仓库 + 背包/快捷栏 + 安全箱（`C2STradeSellPacket` 增 source）；出售模式多选（左键整堆 / 右键 1 个）+ 二次确认、可回收/选中高亮、tooltip 回收价、预计总额；服务端权威逐项结算（匹配优先级 / 库存上限 / 价差保护 / 计分板加分 / 失败跳过汇总）；客户端 id 分桶索引 + NBT 预解析 + 视口结果缓存；<br>③**货币改造**：默认计分板并自动创建 `dn_money`，移除 item 物品货币（旧配置自动迁移）；<br>④**数据安全**：tick 濒死检测 + 死亡事件 + 登出快照（覆盖 `setHealth(0)` 等绕过 `LivingDeathEvent` 的路径）、备份绝不空覆盖、Clone/重生/登录恢复且**恢复后保留备份**、仅管理员重置才清备份；<br>⑤协议 `dn1 → dn2`（客户端与服务端必须同版本）；⑥许可改为 **MIT** |

> ⚠️ **版本号记录事故警示**：交易行相关开发曾被错误地标成 `3.0` / `3.1Alpha`——本表里写过 `3.0.0Alpha`、
> `3.1.0Alpha` 两条“版本历史”行，README 的交易行/货币/配置章节与源码注释也带着 `（3.0）`、`（3.1）` 标注；
> 而项目当时真实存在的版本号只有 `gradle.properties` 的 `mod_version`（`0.1.0Beta` → `0.2.0Beta`）。
> 错误的两行**已删除**，内容全部并入 **0.2.0Beta** 一行；README 与源码/Web 页注释中的 `（3.0）`/`（3.1）` 一并改为 `（0.2.0Beta）`。
> **纪律（后续务必遵守）**：
> 1. 文档 / 注释 / 提交信息 / 更新日志中的版本号，**只能取自 `gradle.properties` 的 `mod_version`**，禁止按“功能模块数量”等自行编号；
> 2. 开发中的功能一律记在**当前版本**行下，**不得预写未来版本行**（哪怕功能已开发完、只是没发版）；
> 3. 仅 `1.0.0Alpha~2.0.6Alpha` 这些既有历史 Alpha 版本是事实记录，可继续引用；
> 4. `dn1` / `dn2` 是**网络协议版本**，与模组版本号无关（协议升到 `dn2` 发生在 0.2.0Beta）；

---

## 二、技术栈与环境

| 项 | 值 |
| :--- | :--- |
| Minecraft | 1.20.1（official mappings） |
| Forge | 47.4.10（gradle.properties `forge_version`，兼容 47.4.x） |
| Java | 17（toolchain） |
| Gradle | 8.8（wrapper），ForgeGradle 6.x |
| 兼容目标 | Mohist 混合服务端（Vault/PlayerPoints 经 Bukkit API 反射访问） |

构建：`gradlew.bat build` → 产物 `build/libs/deltanexus-0.2.0Beta.jar`

---

## 三、源码目录结构

```
src/main/java/com/deltanexus/system/
├── DeltaNexus.java          # 主类：配置注册、网格/附魔注册、commonSetup 默认配置生成
├── api/IPlayerData.java           # 玩家数据能力接口（仓库/安全箱/任务）
├── capability/
│   ├── CapabilityAttacher.java    # 能力绑定、死亡备份/重生恢复/登录补齐、登录推送网格配置
│   └── PlayerDataImpl.java        # 玩家数据实现（序列化/反序列化、行列解锁）
├── client/
│   ├── ClientEvents.java          # 按键处理
│   ├── ClientSetup.java           # 按键注册
│   ├── KeyBindings.java           # 按键定义（含 ROTATE_ITEM）
│   └── gui/
│       ├── WarehouseScreen.java   # 仓库界面（滚轮滚动视口）
│       ├── BackpackScreen.java    # 原版背包替换界面（三列布局）
│       ├── DnContainerScreen.java # 原版容器替换界面（三列布局）
│       ├── DnTheme.java           # 界面主题配色
│       ├── SafeBoxOverlay.java    # 背包界面安全箱覆盖层（网格渲染/点击）
│       ├── SpecialOpsScreen.java  # 特勤处（升级独立界面，V 键）
│       ├── ManufactureScreen.java # 制造界面
│       ├── WorkbenchScreen.java   # 工作台总览（G 键）
│       ├── TradeScreen.java       # 交易行界面（0.2.0Beta：一级列表/二级详情+买入）
│       └── PlayerLayout.java      # 玩家栏位布局
├── common/                        # Task/TaskStatus/WorkbenchRegistry/FormatUtil/NbtMatcher
├── config/
│   ├── ModConfig.java             # 服务端配置（ModConfig.toml，warehouse_rows/base_slots 等 + 迁移）
│   ├── Recipe/RecipeCache         # 配方模型与缓存（JSON 热加载）
│   ├── UpgradeConfig.java         # 仓库/安全箱升级树（行列制）
│   └── SafeBoxRestrictions.java   # 安全箱 NBT 限制
├── trade/                         # ★ 交易行（0.2.0Beta）
│   ├── ItemSpec.java              # 商品规格：id + NBT 模板 + 匹配模式（id/full/partial 键+运算符）
│   ├── PricePolicy.java           # 价格策略（fixed/formula/code 三模式）
│   ├── PriceEngine.java           # Rhino 受限 JS 引擎（禁 Java、超时、ctx 注入）
│   ├── TradeConfig.java           # trade.json（分类/商品/全局设置，热加载）
│   ├── TradeGood.java/TradeCategory.java/TradeStockStore.java
│   └── MarketFeed/FeedSnapshot/TradeFeedRegistry/RegisterMarketFeedsEvent  # 外部价格源 SPI
├── grid/                          # ★ 格式背包模块
│   ├── InventoryGridHandler.java  # 网格引擎核心：求解/占位物/旋转/渲染/类背景/点击捡起
│   ├── ItemSizeConfig.java        # 物品尺寸配置（deltanexus-sizes.json + 运行时覆盖）
│   ├── GridConfig.java            # 快捷栏规则（deltanexus-common.toml）
│   ├── GridClassConfig.java       # 物品「类」背景色（grid_classes.json）
│   ├── GridItems/GridEnchantments # blocked_slot 物品、附魔注册
│   ├── GridModEvents/OverdriveUtils/InventoryManager
│   ├── enchantment/               # 固定打击、充能核心
│   └── network/RotationPacket.java
├── init/ModMenus.java             # 仓库/安全箱菜单注册
├── menu/                          # WarehouseMenu（12 行视口槽位）、SafeBoxMenu、SafeBoxSlot
├── network/
│   ├── PacketHandler.java         # 通道（协议 dn1），全部包注册
│   └── packet/                    # C2S/S2C 包（仓库滚动/特勤处/旋转/捡起/网格配置同步等）
├── server/
│   ├── CommandDN.java            # /dn 指令树（全部管理指令 + 启动迁移钩子）
│   ├── ManufacturingService.java  # 制造/仓库/安全箱/特勤处核心服务（材料识别背包+仓库）
│   ├── CurrencyManager.java       # 货币（物品/计分板/Vault/PlayerPoints）
│   ├── PermissionManager.java     # 权限（warehouse/workbench/special/safe_box/trade，JSON 热加载）
│   ├── TradeService.java          # 交易行服务（0.2.0Beta：求价/买入流水线/目录/Feed 定时刷新）
│   └── TradeAdminHandler.java     # /dn trade 管理指令（0.2.0Beta）
└── web/                           # Web 网页编辑器（HTTP 服务 + JSON API）
```

资源：`src/main/resources/assets/deltanexus/`（语言 zh_cn/en_us、GUI 贴图、web/index.html）

---

## 四、功能总览

| 模块 | 说明 |
| :--- | :--- |
| 制造系统 | 工作台注册表 + 配方 JSON（输入/输出/NBT 匹配/耗时/等级/并行上限），时间戳离线计时，三状态按钮 |
| 仓库 | 行式滚轮滚动（总行数配置 1~64×9 格），0 级默认解锁 9 格，升级树解锁（unlock_slots） |
| 安全箱 | 独立小仓储（最大 3×3），升级树按**行×列**解锁，行列形状渲染（弃用锁 UI），NBT 限制 |
| 格式背包 | 全部 ≥9 格容器按物品尺寸网格化（占位物机制）、R 键旋转（可绑键）、放置自动旋转、同类叠加、类背景色 |
| 特勤处 | 仓库/安全箱升级独立界面（V 键 / /dn open special），独立权限 special |
| 权限 | 仓库/工作台/特勤处/安全箱/交易行五类型，全局默认 + 按玩家覆盖，OP 始终允许 |
| Web 编辑器 | 全功能管理页（配方/树/安全箱/玩家/权限/格式背包/交易行），token 鉴权 |
| 货币 | 计分板（默认，`dn_money` 启动自动创建）/Vault/PlayerPoints；item 物品货币 0.2.0Beta 起移除并自动迁移 |
| 交易行 | 系统商店：管理员上架（物品 id + NBT 模板 + 匹配模式 + **耐久度要求**）、价格三模式（fixed/formula/code，Rhino JS）、库存上下限与补货、买入落玩家仓库（格式背包空间判定）、**仓库界面卖出回收（仓库+背包+安全箱多来源，出售模式多选 + 二次确认）**、Feed SPI 外部价格源 |

---

## 五、指令大全（`/dn`，OP 权限 4）

```
open warehouse|special|manufacture|trade    打开仓库/特勤处/工作台总览/交易行
reload / export                       重载配置 / 导出备份
setting speed|queue|mode|currency     全局参数（倍率/队列/计时模式/货币）
warehouse rows <1-64>|slots <0-576>   仓库总行数 / 0 级初始解锁
safe size <w> <h>|get|add|remove|cost|rows <等级> <行> <列>|additem|delitem|setitem
safe restrict|restrictions|unrestrict 安全箱 NBT 限制管理
grid list|size|remove|hotbar          格式背包：物品尺寸 / 快捷栏规则
grid class list|set|remove            格式背包：类颜色管理
grid (class) setclass|unsetclass      格式背包：物品归属类（顶层与 class 子命令等价）
perm get|set|remove|default           权限（warehouse/workbench/special/safe_box/trade/all）
perm op|getop                            OP 豁免开关
feature get|set                         玩家 mod 功能总开关
trade list|get|reload                  交易行总览/详情/热载
trade cat/good/item/price/limits/stock 交易行管理（主手含 NBT、上下限、补货、feed、setting）
trade setting sell_enabled|spread_guard 回收开关 / 价差保护（warn|block|off）
trade item key add <id> <键> [exact|contains|specified] [值] 指定键规则（specified=键名与值）
trade item durability <id> <on|off> [op] [值]                耐久度要求（剩余耐久，默认关）
                                      玩家卖出：仓库界面「出售」→ 多选（仓库/背包/安全箱）→「确认」
info                                  查看当前配置
workbench|recipe|tree|data|web        工作台/配方/升级树/玩家数据/网页编辑器
help                                  指令总览（/dn <指令> help 查看详情）
```

---

## 六、配置文件

| 文件 | 位置 | 说明 |
| :--- | :--- | :--- |
| ModConfig.toml | config/deltanexus/ | warehouse_rows（默认 12）、base_slots（默认 9）、货币、倍率、计时模式、安全箱默认尺寸、GUI 白名单 |
| common.toml | config/deltanexus/ | 快捷栏规则 hotbar_rules（默认 0-3:ANY,4-8:GRID） |
| deltanexus-sizes.json | config/ | 物品占用尺寸（"item_id": {w,h}） |
| client-ui.toml | config/deltanexus/ | 客户端界面白名单与背包/容器替换开关 |
| grid_classes.json | config/deltanexus/ | 类颜色 + 物品归属 |
| upgrade_tree.json | config/deltanexus/ | 仓库（unlock_slots）+ 安全箱（unlock_rows/unlock_cols）升级树 |
| recipes/<workbench>/ | config/deltanexus/ | 配方 JSON（热加载） |
| workbenches.json | config/deltanexus/ | 工作台注册表 |
| permissions.json | config/deltanexus/ | 权限（default_* + players 覆盖，含 special/safe_box/trade/features） |
| safe_box_restrictions.json | config/deltanexus/ | 安全箱 NBT 限制 |
| trade.json | config/deltanexus/ | 交易行（全局设置 + 分类 + 商品定义；商品含 item/NBT/匹配/价格/上下限） |
| trade-stock.json | config/deltanexus/ | 交易行运行时库存（买入扣除/管理员补货，与定义分离防写放大） |
| web-editor.yml | config/deltanexus/ | Web 编辑器（host/port/public-url/token_auth） |

---

## 七、按键（选项 → 控制 → 三角联结）

| 键 | 功能 |
| :--- | :--- |
| B | 打开仓库 |
| G | 打开工作台总览 |
| V | 打开特勤处 |
| R | 旋转光标物品（格式背包容器界面内） |
| R | 旋转光标物品（容器界面内） |
| （未绑定） | 打开交易行（默认不绑定，可自行设置） |

---

## 八、网络协议（通道 `deltanexus:main`，协议版本 dn2）

- C2S：OpenWarehouse / OpenSafeBox / OpenSpecialOps / StartTask / ClaimTask / CancelTask /
  RefreshTasks / UpgradeWarehouse / UpgradeSafeBox / WarehouseScroll / RequestWorkbenchData /
  RequestSafeBox / SafeBoxClick / Rotation / PickupGridStack / TradeOpen（0.2.0Beta）/ TradeBuy（0.2.0Beta）/
  **TradeSell（0.2.0Beta：仓库界面卖出，多槽位+数量）**
- S2C：OpenScreen（含 SCREEN_SPECIAL / SCREEN_TRADE）/ SyncWarehouse / SyncManufacture /
  SyncWorkbenchData / GiveItem / SyncSafeBox / SyncGridSizes（物品尺寸 + 快捷栏规则 + 类配置）/
  SyncTradeCatalog（交易行目录，0.2.0Beta：打开前/成交/补货/重载后下发，含 match_mode/match_keys）

> **协议版本 0.2.0Beta 起为 `dn2`**（SyncTradeCatalogPacket 增字段 + 新增 C2STradeSellPacket）：客户端与服务端必须同版本。

---

## 九、已知问题与注意点

1. **死亡数据**：已做三层防护（死亡备份 → 重生恢复 → 登录兜底）；若仍出现丢失，检查
   服务端日志 `[DN] 玩家 X 死亡，数据已备份` 与 `已从死亡备份恢复` 是否出现；
2. **旧配置迁移**：`base_slots=9` 的旧服务器启动时自动迁移至 108，0 级玩家登录自动补齐
   至 12 行；`warehouse_pages` 旧键自动迁移为 `warehouse_rows`（页数×9）；
3. **按键冲突**：V/R 键可能与 superbwarfare 等模组冲突（日志会警告），可在控制设置中改绑；
4. **占位物**：菜单关闭时自动清理容器占位物（含箱子），重开重建；若服务器在菜单打开时
   保存，箱内可能出现 blocked_slot 物品（自愈）；
5. **Web 编辑器**：token 仅内存保存，重启失效；`/dn web on` 后按提示链接访问；
6. **类背景色**：需先创建类（`/dn grid class set <名> <R> <G> <B>`）再归属物品
   （`/dn grid class setclass <物品> <类>`），修改后自动同步客户端（登录时亦推送）；
7. **Rhino 打包（0.2.0Beta）**：价格引擎的 Rhino 以“解包并并入模组自身类输出”的方式内置
   （build.gradle `embedRhino` → `sourceSets.main.output.dir`），dev userdev 与正式 jar 均可用；
   **不要回退为** `implementation + META-INF/jarjar`（Forge dev 模块与 loader 都读不到普通 implementation 库）。
   许可：Rhino 1.7.15（MPL 2.0 / GPL 双许可），README 已注明；
8. **货币迁移（0.2.0Beta）**：`item` 物品货币已移除——旧 `ModConfig.toml` 中 `currency_type=item` 启动时自动改写为
   `scoreboard` 并打 WARN；`dn_money` 目标由服务器启动时自动创建（不设侧栏显示）。计分板为 int，
   买入/卖出均做上限校验（超过 21 亿会拒绝，需分批）；
9. **仓库卖出注意**：卖出按商品匹配优先级 `full_nbt > partial_nbt > id`；同一物品若被多个商品命中，
   只回收"最具体"的那个（管理员应避免重复上架同物品导致歧义）；`partial_nbt/id` 商品回收后库存只记数量，
   再次买入时按**商品模板**生成物品（NBT 变体被归一化）；`spread_guard=block` 时 `sell ≥ buy` 的商品禁止回收；
10. **数据安全（0.2.0Beta）**：备份触发点 = tick 濒死检测（health ≤ 0 / isDeadOrDying，覆盖 `setHealth(0)` 等
   绕过 `LivingDeathEvent` 的路径）+ 死亡事件 + 登出；空数据永不覆盖备份；恢复点 = Clone / Respawn / 登录；
   **恢复后保留备份**；只有 `/dn data <玩家> reset`（含 Web 重置）才会清除备份——排障时先看
   `config/deltanexus/backup/<uuid>.dat` 是否存在与日志中的「数据快照」「已从数据备份恢复」；
11. **匹配规则变更（0.2.0Beta）**：`partial_nbt` 的 `ignore` 已弃用（读取旧配置时该键按“不参与匹配”跳过），
   新规则为 `specified`（指定键名与对应值，值按 SNBT/数字/字符串顺序解析）；
12. **版本号纪律（重要）**：交易行开发曾被误记为 `3.0.0Alpha`/`3.1.0Alpha`（已删除，内容并入 0.2.0Beta）。
   写文档/注释/提交信息前先看 `gradle.properties` 的 `mod_version`，**只按真实版本号写记录，不预写未来版本行**；
   协议号 `dn1`/`dn2` 与模组版本号无关。详见第一节版本历史下的「版本号记录事故警示」。

---

## 十、构建与部署

```bat
cd D:\Work\java\DeltaNexus
gradlew.bat build
:: 产物：build/libs/deltanexus-0.2.0Beta.jar → 放入服务器/客户端 mods/
```

开发运行：`gradlew.bat runClient` / `runServer`（工作目录 `run/`）。

---

## 十一、许可

- **本项目（三角联结 / DeltaNexus）以 MIT License 发布**，全文见根目录 `LICENSE`；
  模组元数据 `gradle.properties` 中 `mod_license=MIT`（会写入 jar 内 `META-INF/mods.toml` 的 `license` 字段）。
- 第三方组件：
  - **Rhino 1.7.15**（MPL 2.0 / GPL 双许可）——交易行价格引擎 JS 沙箱，以已编译类内置进模组 jar，
    未修改源码（分发需保留其许可声明，`LICENSE` 文件末段已附）；
  - Minecraft Forge / MCP 数据等 MDK 模板文件沿用原授权（见 `LICENSE.txt`），不在 MIT 覆盖范围内。

