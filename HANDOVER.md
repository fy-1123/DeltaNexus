# 三角联结（DeltaNexus）交接文档

  版本：0.4.0Beta（Forge 1.20.1 / Minecraft 1.20.1，兼容 Mohist 混合服务端
  模组 ID：`deltanexus`｜显示名：三角联结｜指令：`/dn`｜日志前缀：`[DN]`｜许可：**MIT**（见根目录 `LICENSE`）

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

| 版本                    | 内容 |
|:----------------------| :--- |
| 1.0.0Alpha~1.1.0Alpha | 制造台 + 仓库翻页 + 安全箱 + 权限管理 + Web 编辑器基线 |
| 2.0.0Alpha            | 格式背包模块 |
| 2.0.1Alpha            | 仓库翻页→滚轮滚动（12 行视口）、安全箱升级树行列制、网格性能与放置校验 |
| 2.0.2Alpha            | 特勤处独立升级界面、格式背包配置指令/Web、无处可放回指针、同类叠加 |
| 2.0.3Alpha            | 特勤处 V 键+独立权限、安全箱行列形状渲染、材料识别仓库、物品类背景色、非左上角捡起 |
| 2.0.4Alpha            | 死亡备份恢复、12 行视口、放置自动旋转、旋转可绑键、特勤处打开修复、安全箱跨格渲染修复 |
| 2.0.5Alpha            | 死亡重生兜底、base_slots 迁移与登录补齐、旋转按键改屏幕事件、1×1 类色渲染、撤销点击移动 |
| 2.0.6Alpha            | Web 权限页特勤处支持、登录补齐强制 12 行、类命令顶层别名、死亡备份日志 |
| 0.1.0Beta             | 项目更名 **三角联结（DeltaNexus）**：modid `deltanexus`、指令 `/dn`、日志前缀 `[DN]`、包名 `com.deltanexus.system`、配置目录 `config/deltanexus/`、NBT 键 `deltanexus.*`；旧档与旧配置视为不兼容 |
| 0.2.0Beta             | **交易行 + 仓库卖出/回收 + 货币改造 + 数据安全**（本版本一次性发布，此前误记为 `3.0.0Alpha`/`3.1.0Alpha` 的内容全部归入本行）：<br ①**交易行（系统商店）**：目录默认空、管理员逐件上架并自定价格；价格三模式 fixed/formula/code（Rhino JS 受限沙箱统一执行）+ ctx 库存/时间/方向/feed 变量；Feed SPI（私有 moligod companion 仅服务端注册外部价格源）；匹配模式 id/full_nbt/partial_nbt，`ignore` 弃用 → **`specified`（指定键名与对应值）**；商品**耐久度要求**（默认关，= / < /   / <= /  = 比较剩余耐久，可与匹配模式共存）；库存上下限 + 补货；买入落玩家仓库（格式背包空间判定，不足禁买并提示）；`/dn open trade` + 未绑定按键 + `trade` 权限 + `/dn trade` 管理指令 + Web 交易行页；<br ②**仓库界面卖出（回收）**：来源 = 仓库 + 背包/快捷栏 + 安全箱（`C2STradeSellPacket` 增 source）；出售模式多选（左键整堆 / 右键 1 个）+ 二次确认、可回收/选中高亮、tooltip 回收价、预计总额；服务端权威逐项结算（匹配优先级 / 库存上限 / 价差保护 / 计分板加分 / 失败跳过汇总）；客户端 id 分桶索引 + NBT 预解析 + 视口结果缓存；<br ③**货币改造**：默认计分板并自动创建 `dn_money`，移除 item 物品货币（旧配置自动迁移）；<br ④**数据安全**：tick 濒死检测 + 死亡事件 + 登出快照（覆盖 `setHealth(0)` 等绕过 `LivingDeathEvent` 的路径）、备份绝不空覆盖、Clone/重生/登录恢复且**恢复后保留备份**、仅管理员重置才清备份；<br ⑤协议 `dn1 → dn2`（客户端与服务端必须同版本）；⑥许可改为 **MIT** |
| 0.2.1Beta             | **出售交互修复（三项）**：<br ①**「取消」语义**：仓库界面进入出售模式但未选中任何物品时，按钮文字为「取消」（新增 lang 键 `gui.dn.trade.sell.cancel`），点击即 `clearSellState()` 退回正常存储功能——原实现在出售模式下按钮仍写「出售」，用户无从得知点它会退出；<br ②**按钮不再压格子**：槽位物品区 16×16、而槽位底图（含边框）18×18 且向右下各多 2px，原按钮按物品区定位（`whY+214`）会重叠仓库最后一行格子；现按外框对齐为 `(whX+2, 行信息条顶边+1, 54, 17)`，行信息条顶边 = `whY + 已解锁行数(≤12)*18`（新增 `infoStripY()`：面板按解锁行数绘制，固定偏移会让低等级玩家的按钮悬在面板外），行信息/预计总额文字同步为 `infoStripY()+5`；<br ③**出售模式冻结物品**：`WarehouseScreen.slotClicked` 在出售模式下直接 return（屏蔽左/右键取放、Shift 快捷移动、数字键换位、Q 丢弃，不管物品能否回收），`mouseClicked` 在出售模式下吞掉一切点击（槽位外点击也不会把光标物品丢进世界）；`GridClientRendering` 的全局拦截（跨格「非左上角捡起」`C2SPickupGridStackPacket`、R 旋转光标物品）也加了 `WarehouseScreen#isSellMode()` 判断 |
| 0.3.0Beta             | **格式背包（格子背包）内核重写**——按《0.3.0Beta格子背包系统重写文档.md》落地（以代码实际为准）：<br ①**架构**：`grid` 拆为 `grid.core`（`GridDim`/`UsableMask`/`StackSnapshot`/`GridContext`/`GridSolver` 纯函数求解/`SolvePlan`/`GridMutation` 事务写入/`GridLockManager` 顺序锁/`GridService` 脏标记与生命周期）与 `grid.adapter`（`MenuGridAdapter`/`HandlerGridAdapter`/`InputGate`/`GridInputRedirect`/`GridRenderAdapter`）；`InventoryGridHandler` 从 758 行单体降级为**门面**（保留 0.2.x 全部静态入口 + 3 个事件订阅）；<br ②**菜单层统一点击重定向**：`GridAwareMenu`（`WarehouseMenu` 继承）在 `clicked`/`quickMoveStack` 把占位物格解析到主格，客户端在 `WarehouseScreen`/`BackpackScreen`/`DnContainerScreen` 的 `slotClicked` 同样重定向——PICKUP/QUICK_MOVE/SWAP/THROW/QUICK_CRAFT 全路径覆盖，修掉「点击非主格格子导致主格瞬移」；<br ③**索引口径统一**：占位物 `master_slot` 改为记录**容器索引**（旧版混用菜单索引，仓库滚动一行即全部失配 → 每滚一次重写整片占位物），解析时按容器索引反查菜单槽位；<br ④**占位物只存在于运行态**：`PlayerDataImpl.buildTag()` 落盘前清理（自动保存/备份/Clone 均覆盖）、登录与重生扫描清除、`isEmptyData` 不再把「只有占位物的仓库」当进度（避免阻断死亡备份恢复）；仓库/安全箱 Handler 覆写 `extractItem`/`insertItem`/`isItemValid` 守卫占位物格；<br ⑤**容器显式注册**：`GridRegistry` + `common.toml` 的 `registered_containers` / `legacy_any_container`（默认 false = 未注册的模组容器不再接管，原版 ≥9 格容器与玩家背包/仓库/安全箱为内置注册）+ `/dn grid containers|register|unregister`；<br ⑥**渲染修正**：跳过 `!slot.isActive()`、去重键改「容器身份 + 容器索引」（旧键跨组撞车导致偶发少画两格）；grid 包不再引用任何屏幕类（`InputGate` 接口注册出售模式）；<br ⑦**旋转**：标记改 `deltanexus.grid.rotated`（读取永久兼容旧键 `deltanexus.is_rotated`），`RotationPacket` 补权限/门闸/网格容器校验，`GridTags.stripped()` 供交易与管道比较剥离网格键；<br ⑧**网络包校验补强**：`C2SPickupGridStackPacket` 校验容器/光标为空/出售门闸并按容器索引解析主格；<br ⑨新增 `GridRegressionTests`（7 个内核 GameTest：足迹与占位物、锁定格重排、旋转落位、同类合并、孤立占位物清理、精确数量回退、旋转键迁移与剥离）；<br ⑩**协议 `dn2 → dn3`**：新增 C2S `C2SSellModePacket`（进入/退出出售模式），出售模式改为**服务端权威**——`WarehouseMenu.setSellMode` + `GridAwareMenu` 门闸冻结一切物品移动，`InputGate.serverSellMode` 同样拦住跨格拾取与 R 旋转；屏幕关闭/菜单销毁/断线自动解除；<br ⑪**止血三步（同版本内，针对复制/重叠）**：①**不再每 tick 重排**——每 tick 只做只读一致性校验 `GridIntegrity.check`（越界/跨锁定格/跨口袋分区/足迹重叠/缺占位物/孤立占位物），不一致才收敛一次，空闲零写入；②**事务原子化**——`GridMutation` 与 `GridService.placeInto` 改为「影子数组 + 逐项 CAS（源格内容未变 + 目标足迹空闲或属自己旧占位物）+ 守恒校验（总量不变）+ `ItemStack` 实例共享校验 + 只写变化格」，任何一项不过关即**整体放弃写入并打 ERROR**；占位物改为按影子**实际内容**重新推导（不再照抄计划 owner 表）；③**几何判定唯一化**——手动放置/整理/交付/买入预演共用同一 `GridContext` 足迹判定；新增 5 个安全网 GameTest（过期计划不复活物品、不覆盖已占足迹、事务守恒、几何违规判定、守恒/共享工具自检）；调试开关 `-Ddeltanexus.grid.debug=true`；<br ⑫**第二阶段：布局显式化（随 dn3 一并发布）**——新增 `GridLayout.derive(ctx, cells)` 作为**全模组唯一几何推导**（求解/占位物重建/一致性校验/下发客户端四处共用）；新增 S2C `SyncGridLayoutPacket`（菜单打开与布局变化时下发锚点槽位+宽高+旋转+行宽，指纹相同不发包、玩家侧版本单调）；客户端 `GridLayoutClient` 驱动渲染与点击重定向（无布局时回退占位物 NBT），消除「客户端自行推导几何」造成的假性复制/重叠；新增 2 个布局推导 GameTest；<br>⑬**第三阶段：去掉占位物（协议仍 dn3）**——①`GridMutation` <b>只清不写</b>占位物（足迹非主格=普通空格，旧残留按历史数据清除）；②`GridLayout.derive` 不再把「足迹格为空」判为违规，只把残留占位物判为待清理；③点击重定向改由布局承担：服务端新增 `GridService.anchorSlotOf(menu, slot)`（与下发布局同一份推导）作为 `GridAwareMenu.resolveMaster` 的首选，占位物 NBT 仅兜底；④新增 `GridFootprints.covered(handler, slot, width)` 作为外部写入**软闸**，仓库/安全箱 `insertItem`/`isItemValid` 拒绝落在足迹内的插入（管道/漏斗/其他模组）；⑤决策：**旋转标记继续存物品 NBT**（稳定性优先：随物品走、不引入槽位侧表、不会因移动/滚动/重连失配）；⑥Shift 快捷移动仍可能先落进足迹格，由下一次收敛**合法位移**（只移动 + 守恒校验）纠正，不复制不覆盖 |
| 0.4.0Beta             | **刷兵系统（零活动设计）**——按《0.4.0beta刷兵系统设计方案.md》落地（以代码实际为准）：<br ①**零活动**：不监听事件、不自动刷兵、无冷却/定时/波次，仅在收到执行请求时刷一次并立即结束；<br ②**配置分层**：全局配置 `global.json`（总开关 `enabled`、黑名单世界 `blacklisted_worlds`、上限）与 `logging.json`（日志级别/控制台/文件开关）；世界级配置 `spawners.json`（刷兵器）与 `pointgroups.json`（点位组）按**世界文件夹路径缓存**到 `config/deltanexus/spawner/<world>/`；<br ③**指令**：新增 `/dn spawner` 子指令树——`new/del/list/info/set/nbt/point/group/run/preview/dry-run/log`，权限 `2`；热加载挂在现有 `/dn reload` 上（`SpawnerManager.reloadAll` 重载全部世界配置，不重启即生效）；<br ④**执行**：`SpawnerService.run/preview/dry-run`——校验实体池→全局限制（实体数/TPS/内存/黑名单）→条件（难度/在线玩家数）→点位与数量（`count` 支持 `per_point` 区间）→安全寻位（坚实地面/熔岩水规避/最大尝试）→应用 NBT/行为/粒子/音效/命令；<br ⑤**Zero-Activity 日志**：执行全程按 `logging.json` 输出到控制台与 `logs/deltanexus/spawn/<日期>.log` | 


  ⚠️ **版本号记录事故警示**：交易行相关开发曾被错误地标成 `3.0` / `3.1Alpha`——本表里写过 `3.0.0Alpha`、
  `3.1.0Alpha` 两条“版本历史”行，README 的交易行/货币/配置章节与源码注释也带着 `（3.0）`、`（3.1）` 标注；
  而项目当时真实存在的版本号只有 `gradle.properties` 的 `mod_version`（`0.1.0Beta` → `0.2.0Beta`）。
  错误的两行**已删除**，内容全部并入 **0.2.0Beta** 一行；README 与源码/Web 页注释中的 `（3.0）`/`（3.1）` 一并改为 `（0.2.0Beta）`。
  **纪律（后续务必遵守）**：
  1. 文档 / 注释 / 提交信息 / 更新日志中的版本号，**只能取自 `gradle.properties` 的 `mod_version`**，禁止按“功能模块数量”等自行编号；
  2. 开发中的功能一律记在**当前版本**行下，**不得预写未来版本行**（哪怕功能已开发完、只是没发版）；
  3. 仅 `1.0.0Alpha~2.0.6Alpha` 这些既有历史 Alpha 版本是事实记录，可继续引用；
  4. `dn1` / `dn2` 是**网络协议版本**，与模组版本号无关（协议升到 `dn2` 发生在 0.2.0Beta）；
  5. 发版前自查残留：`git grep -n "3\.0\|3\.1"`（`3.0f` 等浮点字面量属正常代码，忽略）。

---

## 二、技术栈与环境

| 项 | 值 |
| :--- | :--- |
| Minecraft | 1.20.1（official mappings） |
| Forge | 47.4.10（gradle.properties `forge_version`，兼容 47.4.x） |
| Java | 17（toolchain） |
| Gradle | 8.8（wrapper），ForgeGradle 6.x |
| 兼容目标 | Mohist 混合服务端（Vault/PlayerPoints 经 Bukkit API 反射访问） |

构建：`gradlew.bat build` → 产物 `build/libs/deltanexus-0.3.0Beta.jar`

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
├── grid/                          # ★ 格式背包模块（0.3.0Beta 内核重写）
│   ├── InventoryGridHandler.java  # 门面：0.2.x 全部静态入口 + 3 个事件订阅（实现已下沉 core/adapter）
│   ├── core/                      # 纯函数内核（可单元测试，不碰玩家/菜单/网络）
│   │   ├── GridDim/UsableMask/StackSnapshot/GridContext   # 尺寸/可用格/快照/上下文
│   │   ├── GridSolver.java        # 求解器：大件优先 → 冲突+合并 → 重排/旋转 → 兜底
│   │   ├── SolvePlan.java         # 求解计划（归属表/落位物品/合并变更/处置建议）
│   │   ├── GridMutation.java      # 事务写入（占位物/孤立占位物/精确数量回退）
│   │   ├── GridLockManager.java   # canonical 顺序锁（禁止裸 synchronized）
│   │   ├── GridTags.java          # NBT 约定（is_slave/master_slot/rotated + 剥离）
│   │   └── GridService.java       # 脏标记/节流/生命周期（唯一“不纯”编排层）
│   ├── adapter/                   # 适配层
│   │   ├── MenuGridAdapter.java   # 菜单槽位 ↔ 网格视图；容器索引 ↔ 菜单索引；主格解析
│   │   ├── HandlerGridAdapter.java# 裸 ItemStackHandler（仓库/安全箱）适配
│   │   ├── InputGate.java         # 出售模式/白名单/功能开关统一门闸（不引用屏幕类）
│   │   └── GridRenderAdapter.java # 客户端渲染（跳过隐藏槽、按容器维度去重）
│   ├── GridRegistry.java          # 容器显式注册（内置：背包/仓库/安全箱/原版 ≥9 格容器）
│   ├── GridSizes.java             # 尺寸规则（配置尺寸 + 旋转 + 快捷栏分级）
│   ├── ItemSizeConfig.java        # 物品尺寸配置（deltanexus-sizes.json + 运行时覆盖）
│   ├── GridConfig.java            # 快捷栏规则 + 容器注册 + legacy_any_container（common.toml）
│   ├── GridClassConfig.java       # 物品「类」背景色（grid_classes.json）
│   ├── GridClientRendering.java   # 客户端事件薄壳（渲染/门闸委托 adapter）
│   ├── GridItems/GridEnchantments # blocked_slot 物品、附魔注册
│   ├── GridModEvents/OverdriveUtils
│   ├── enchantment/               # 固定打击、充能核心
│   └── network/RotationPacket.java
├── init/ModMenus.java             # 仓库/安全箱菜单注册
├── menu/                          # GridAwareMenu（0.3.0Beta：菜单层点击重定向）+ WarehouseMenu/SafeBoxSlot
├── network/
│   ├── PacketHandler.java         # 通道（协议 dn3），全部包注册
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
| 交易行 | 系统商店：管理员上架（物品 id + NBT 模板 + 匹配模式 + **耐久度要求**）、价格三模式（fixed/formula/code，Rhino JS）、库存上下限与补货、买入落玩家仓库（格式背包空间判定）、**仓库界面卖出回收（仓库+背包+安全箱多来源，出售模式多选 + 二次确认；未选中时按钮显示「取消」；出售期间物品完全冻结）**、Feed SPI 外部价格源 |

---

## 五、指令大全（`/dn`，OP 权限 4）

```
open warehouse|special|manufacture|trade    打开仓库/特勤处/工作台总览/交易行
reload / export                       重载配置 / 导出备份
setting speed|queue|mode|currency     全局参数（倍率/队列/计时模式/货币）
warehouse rows <1-64 |slots <0-576    仓库总行数 / 0 级初始解锁
safe size <w  <h |get|add|remove|cost|rows <等级  <行  <列 |additem|delitem|setitem
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
trade item key add <id  <键  [exact|contains|specified] [值] 指定键规则（specified=键名与值）
trade item durability <id  <on|off  [op] [值]                耐久度要求（剩余耐久，默认关）
                                      玩家卖出：仓库界面「出售」→ 多选（仓库/背包/安全箱）→「确认」
info                                  查看当前配置
workbench|recipe|tree|data|web        工作台/配方/升级树/玩家数据/网页编辑器
help                                  指令总览（/dn <指令  help 查看详情）
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
| recipes/<workbench / | config/deltanexus/ | 配方 JSON（热加载） |
| workbenches.json | config/deltanexus/ | 工作台注册表 |
| permissions.json | config/deltanexus/ | 权限（default_* + players 覆盖，含 special/safe_box/trade/features） |
| safe_box_restrictions.json | config/deltanexus/ | 安全箱 NBT 限制 |
| trade.json | config/deltanexus/ | 交易行（全局设置 + 分类 + 商品定义；商品含 item/NBT/匹配/价格/上下限） |
| trade-stock.json | config/deltanexus/ | 交易行运行时库存（买入扣除/管理员补货，与定义分离防写放大） |
| web-editor.yml | config/deltanexus/ | Web 编辑器（host/port/public-url/token_auth） |

---

## 七、按键（选项 → 控制 → 三角联结）

| 键     | 功能 |
|:------| :--- |
| （未绑定） | 打开仓库 |
| （未绑定） | 打开工作台总览 |
| （未绑定） | 打开特勤处 |
| R     | 旋转光标物品（格式背包容器界面内） |
| R     | 旋转光标物品（容器界面内） |
| （未绑定） | 打开交易行（默认不绑定，可自行设置） |

---

## 八、网络协议（通道 `deltanexus:main`，协议版本 dn3）

- C2S：OpenWarehouse / OpenSafeBox / OpenSpecialOps / StartTask / ClaimTask / CancelTask /
  RefreshTasks / UpgradeWarehouse / UpgradeSafeBox / WarehouseScroll / RequestWorkbenchData /
  RequestSafeBox / SafeBoxClick / Rotation / PickupGridStack / TradeOpen（0.2.0Beta）/ TradeBuy（0.2.0Beta）/
  TradeSell（0.2.0Beta：仓库界面卖出，多槽位+数量）/
  **SellMode（0.3.0Beta：进入/退出出售模式，服务端权威；`active` 布尔）**
- S2C：OpenScreen（含 SCREEN_SPECIAL / SCREEN_TRADE）/ SyncWarehouse / SyncManufacture /
  SyncWorkbenchData / GiveItem / SyncSafeBox / SyncGridSizes（物品尺寸 + 快捷栏规则 + 类配置）/
  SyncTradeCatalog（交易行目录，0.2.0Beta：打开前/成交/补货/重载后下发，含 match_mode/match_keys）/
  SyncGridLayout（网格布局，0.3.0Beta/dn3：菜单打开与布局变化时下发「锚点槽位 + 宽高 + 旋转 + 行宽」）

> **协议版本 0.3.0Beta 起为 `dn3`**（新增 C2S `C2SSellModePacket` 与 S2C `SyncGridLayoutPacket`）：客户端与服务端必须同版本。
> ⚠️ **握手串必须固定为 `dn3`，且不要再引入任何“指纹/包表校验”**：曾把「包表指纹」加入握手串，导致单人游戏 `Version test … REJECTED / mismatched mod list`、无法进入世界（并连带集成服务端在 GLFW 销毁后取时间抛 NPE）。该机制**已整体删除**；混装构建靠“发版时客户端/服务端同版本发布”约束。
> 历史：`dn1 → dn2` 在 0.2.0Beta；`dn2 → dn3` 在 0.3.0Beta——0.3.0Beta 未公开发布，出售模式包与网格布局包
> 统一记为同一次 `dn3` 变更（不额外占用协议版本号）；后续若做「去除占位物」等改变槽位同步语义的改动，再评估是否需要新协议号。
>
> **网格布局（dn3）**：`GridLayout.derive` 是全模组唯一的几何推导，服务端在菜单打开（`PlayerContainerEvent.Open`）
  与每次布局变化后下发跨格物品的锚点/尺寸/旋转/行宽；客户端 `GridLayoutClient` 用它渲染与点击重定向
  （无布局信息时回退占位物 NBT 判定）。指纹相同不发包，玩家侧版本号单调递增。
 
  **出售模式（dn3）**：客户端在进入/退出出售模式时发包；服务端把它记在 `WarehouseMenu` 上
  （`setSellMode`），`GridAwareMenu.clicked` / `quickMoveStack` 在开启时直接返回，从而**服务端冻结**左/右键取放、
  Shift 快捷移动、数字键换位、Q 丢弃、拖拽；`C2SPickupGridStackPacket`（跨格拾取）与 `RotationPacket`（R 旋转）
  也读同一门闸（`InputGate.serverSellMode`）。成交走 `C2STradeSellPacket`（不是点击，不受门闸影响）。
  屏幕关闭 / 菜单销毁 / 断线时服务端解除状态，`GridService.onMenuClosed` 兜底清理。

---

## 九、已知问题与注意点

1. **死亡数据**：已做三层防护（死亡备份 → 重生恢复 → 登录兜底）；若仍出现丢失，检查
   服务端日志 `[DN] 玩家 X 死亡，数据已备份` 与 `已从死亡备份恢复` 是否出现；
2. **旧配置迁移**：`base_slots=9` 的旧服务器启动时自动迁移至 108，0 级玩家登录自动补齐
   至 12 行；`warehouse_pages` 旧键自动迁移为 `warehouse_rows`（页数×9）；
3. **按键冲突**：V/R 键可能与 superbwarfare 等模组冲突（日志会警告），可在控制设置中改绑；
4. **占位物（`blocked_slot`）现状（0.3.0Beta 重写后）**：**没有任何写入路径**；仅保留**检测与清理**
   （登录加载、收敛、落盘前）用于自愈旧存档；`C2SPickupGridStackPacket` 仅作兼容空实现保留（不动包表）；
5. **网格内核 v2（0.3.0Beta）**：仓库/安全箱由 `grid.v2.GridInventory` 直接持有（只存锚点、占用派生、
   `GridOp` 唯一入口、每次提交校验不变式 + 守恒），旧 `ItemStackHandler` 形态由 `GridHandlerBridge` 暴露
   （调用点零改动）；玩家背包走 `PlayerInventoryView` 派生视图（只允许合法位移，不创建/删除物品）。
   **存档格式已变**（`format_version:1`，只写锚点 `{cell,item,w,h,rotated}`）：首次加载自动迁移旧平铺数据，
   日志 `[DN] 网格格式迁移完成：条目 N 件（原位保留 x，移位 y，退化 1x1 z）`；迁移规则
   （原位 → 首个空位 → 强制原位 → 退化 1x1）保证**只移动不丢弃**；回滚请用迁移前的 `world/` 备份。
   锁定格上允许存在既有物品（管理员降级/旧档遗留），不会被判违规而丢物品；
6. **Web 编辑器**：token 仅内存保存，重启失效；`/dn web on` 后按提示链接访问；
7. **类背景色**：需先创建类（`/dn grid class set <名> <R> <G> <B>`）再归属物品
   （`/dn grid class setclass <物品> <类>`），修改后自动同步客户端（登录时亦推送）；
8. **Rhino 打包（0.2.0Beta）**：价格引擎的 Rhino 以“解包并并入模组自身类输出”的方式内置
   （build.gradle `embedRhino` → `sourceSets.main.output.dir`），dev userdev 与正式 jar 均可用；
   **不要回退为** `implementation + META-INF/jarjar`（Forge dev 模块与 loader 都读不到普通 implementation 库）。
   许可：Rhino 1.7.15（MPL 2.0 / GPL 双许可），README 已注明；
9. **货币迁移（0.2.0Beta）**：`item` 物品货币已移除——旧 `ModConfig.toml` 中 `currency_type=item` 启动时自动改写为
   `scoreboard` 并打 WARN；`dn_money` 目标由服务器启动时自动创建（不设侧栏显示）。计分板为 int，
   买入/卖出均做上限校验（超过 21 亿会拒绝，需分批）；
10. **仓库卖出注意**：卖出按商品匹配优先级 `full_nbt   partial_nbt   id`；同一物品若被多个商品命中，
   只回收"最具体"的那个（管理员应避免重复上架同物品导致歧义）；`partial_nbt/id` 商品回收后库存只记数量，
   再次买入时按**商品模板**生成物品（NBT 变体被归一化）；`spread_guard=block` 时 `sell ≥ buy` 的商品禁止回收；
   **0.2.1Beta**：出售模式下物品被完全冻结（`slotClicked` 直接返回 + `mouseClicked` 吞掉点击 + 全局网格拦截检查 `isSellMode()`），
   玩家点格子只会「选中/取消选中」，不会取放物品；要移动物品请先点「取消」或按 ESC 退出出售模式；
11. **数据安全（0.2.0Beta）**：备份触发点 = tick 濒死检测（health ≤ 0 / isDeadOrDying，覆盖 `setHealth(0)` 等
   绕过 `LivingDeathEvent` 的路径）+ 死亡事件 + 登出；空数据永不覆盖备份；恢复点 = Clone / Respawn / 登录；
   **恢复后保留备份**；只有 `/dn data <玩家  reset`（含 Web 重置）才会清除备份——排障时先看
   `config/deltanexus/backup/<uuid .dat` 是否存在与日志中的「数据快照」「已从数据备份恢复」；
12. **匹配规则变更（0.2.0Beta）**：`partial_nbt` 的 `ignore` 已弃用（读取旧配置时该键按“不参与匹配”跳过），
   新规则为 `specified`（指定键名与对应值，值按 SNBT/数字/字符串顺序解析）；
13. **版本号纪律（重要）**：交易行开发曾被误记为 `3.0.0Alpha`/`3.1.0Alpha`（已删除，内容并入 0.2.0Beta）。
   写文档/注释/提交信息前先看 `gradle.properties` 的 `mod_version`，**只按真实版本号写记录，不预写未来版本行**；
   协议号 `dn1`/`dn2`/`dn3` 与模组版本号无关（`dn1→dn2` 在 0.2.0Beta，`dn2→dn3` 在 0.3.0Beta）。
   详见第一节版本历史下的「版本号记录事故警示」；
14. **格式背包重写注意（0.3.0Beta）**：
   - **占位物索引口径**：`deltanexus.master_slot` = **容器索引**。任何时候都不要把它当菜单槽位索引用；
     需要菜单槽位请走 `InventoryGridHandler.resolveMasterSlot(menu, slot)`。
   - **占位物绝不落盘**：`PlayerDataImpl.buildTag()`（自动保存/备份/Clone）与登出/重生钩子都会清理；
     若在存档里看到 `blocked_slot`，说明数据来自旧版本——登录会自动扫描清除。
   - **容器接管范围**：未注册的模组容器不再启用网格（`/dn grid containers` 查看、`/dn grid register` 添加）。
     老服务器若依赖「所有 ≥9 格容器都被接管」，把 `common.toml` 的 `legacy_any_container` 设为 `true`。
   - **服务端出售门闸（0.3.0Beta，dn3）**：出售模式已服务端权威化——客户端发 `C2SSellModePacket`，
     服务端记在 `WarehouseMenu.setSellMode` 上，`GridAwareMenu.clicked`/`quickMoveStack` 与
     `InputGate.serverSellMode` 据此冻结一切物品移动；屏幕关闭/菜单销毁/断线自动解除。
     仍**必须**保留客户端冻结（`InputGate.clientFrozen()`）：它让界面手感一致、不发无谓点击包。
   - **不要删除** `InventoryGridHandler` 的三个事件订阅（每 tick 整理 / 关闭菜单清占位物 / 禁止丢出占位物）。
   - **复制/重叠的排查口径（0.3.0Beta 止血）**：每 tick 只做只读一致性校验，不一致才收敛；
     事务写入是「影子数组 + CAS + 守恒 + 引用共享校验」，任何一项不过关就整体放弃并打 ERROR。
     排障顺序：①日志搜 `网格事务中止` → 说明还有未覆盖的写路径（日志里的 before/after 数量能立刻定位方向）；
     ②带 `-Ddeltanexus.grid.debug=true` 搜 `网格需要收敛` → 看哪种违规在反复出现；
     ③若仍出现「物品复制」，优先查**不经过网格事务的写入点**（其他模组直接 `setStackInSlot`／指令给物／
     死亡掉落与维度切换搬运／`/give`）：它们的正确表现是「制造一次冲突、由下次收敛合法安置」，而不是复制。

---

## 十、构建与部署

```bat
cd D:\Work\java\DeltaNexus
gradlew.bat build
:: 产物：build/libs/deltanexus-0.3.0Beta.jar → 放入服务器/客户端 mods/
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

