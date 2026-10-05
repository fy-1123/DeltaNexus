# 三角联结（DeltaNexus）交接文档

  版本：0.5.0Beta（Forge 1.20.1 / Minecraft 1.20.1，兼容 Mohist 混合服务端
  模组 ID：`deltanexus`｜显示名：三角联结｜指令：`/dn`｜日志前缀：`[DN]`｜许可：**MIT**（见根目录 `LICENSE`）

---

## 一、项目概述

三角联结是一个**制造 + 仓库 + 格子格式背包 + 装备物品化**整合型 Mod，核心设计哲学：

- **配置热加载**：配方/升级树/工作台/权限/安全箱限制全部 JSON 化，`/dn reload` 即时生效；
- **时间戳驱动**：制造任务零 Tick 依赖，离线照常计时（时间戳差计算）；
- **增量网络包**：同步包只发轻量字段，仓库物品走容器标准同步；
- **格子格式背包**：背包/仓库/安全箱及全部 ≥9 格容器按物品占用尺寸
  网格化整理、跨格渲染、R 键旋转、物品「类」背景色。
- **装备物品化**：胸挂（4×3）/背包（6×4）本体是物品、内容存在物品自身 NBT，随物品被丢弃/交易/搬运；
  `/dn gear` 配置与装备，`/dn perm` 第六类 `gear` 独立授权。

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
| 0.4.1Beta                | **Web 编辑器可用性大改 + 代码审查与文档更正**：<br ① 记住上次活跃页签（`dn_activeTab`）、`Ctrl+K` 全局搜索（配方/工作台/商品/玩家，定位高亮）、右上角「下载配置」全量 JSON 备份；<br ② 配方与交易商品列表分页 + 密度列与筛选；配方行内勾选**批量改耗时/等级**（新增 `/api/recipe/batch`）与**一键复制**新 id（新增 `/api/recipe/copy`）；<br ③ Bug 修复：`recipeBatch` 单字段 NPE、复制 id 校验统一小写、页签白名单防注入、下载延迟 revokeObjectURL；<br ④ README/HANDOVER 更正（产物名 0.4.1Beta、补刷兵配置目录/指令/目录树、删重复按键行） | 
| 0.4.2Beta             | **工作量台倍率改为速度倍率 + Web 编辑器重写 + 交易行价格优化**：①`/dn setting speed` 语义由「工作量台倍率」改为「制造**速度**倍率」；②Web 编辑器全面重写为侧边栏导航；③交易行价格求值优化；④仓库行数上限提升至 256 |
| 0.5.0Beta             | **格子背包内核重写 + 装备物品化 + Web 统一物品选择器 + 权限第 6 类**（以代码实际为准）：<br ①**内核重写**：`GridStore` 成为格子容器唯一一等公民存储模型（`final class`，只存锚点 `LinkedHashMap<Integer,GridEntry>`、占用由 `footprint/occupancy` 现算、唯一写入口 `place/take/move/rotate/compact` 走「影子推演 → 不变式 → 守恒 → 提交或整体放弃」、`validate()` 任意时刻可判定、返回 `record Result(ok,reason,revision,taken)`）；条目改为不可变 `GridEntry`；存档统一 `GridNbt`（`format_version:1`，旧平铺首次加载自动迁移并保留 `.bak`）；`GridHandlerBridge` 重写并**从 `grid/v2/` 迁到 `grid/` 根**作为仓库/安全箱接线层（`extends ItemStackHandler`，调用点零改动，`serializeNBT()` 走 `GridNbt`，`deserializeNBT` 优先新格式、回退旧平铺迁移；构造期不回调 `Access`，持有者构造完成后 `refreshUsable()`）；新增 `StoreContainer` 把「只存锚点」模型暴露成原版 `Container`；<br ②**统一 NBT 标记 `GridMarker`**：单命名空间复合标签 `{ DeltaNexus: { rot, gear } }`，历史键 `deltanexus.grid.rotated`/`deltanexus.is_rotated`/`deltanexus.is_slave`/`deltanexus.master_slot` 只读兼容且**不再新写**；<br ③**装备物品化**：胸挂 `rig`（默认 4x3）/ 背包 `backpack`（默认 6x4，上限 9x6）为物品，容纳内容随物品 NBT（`GearData`）；新增 `GearKind`/`GearConfig`/`GearPolicy`/`GearData`/`GearService`/`GearMenu`/`GearScreen`/`GearSlot`/`GearClientState`；统一裁决入口 `GearService`（`insertIntoGear` 先胸挂后背包 / `insertInventory` 只在前 `GearPolicy.KEEP_SLOTS` 格找位、溢出进装备 / `moveToAllowed` Shift 快捷移动跳过被屏蔽格）；右键打开（物品自带右键行为则让位，按类反射缓存 `OWN_USE`）；新增 `/dn gear set|remove|list|equip|unequip|open`（+`help`，权限 2）；新增 C2S `C2SOpenGearPacket`、S2C `SyncGearPacket`；<br ④**权限第 6 类 `gear`**（六类型：warehouse/workbench/special/safe_box/trade/gear），`features` 硬开关（禁用后无法使用任何 mod 功能、UI 恢复原版）；<br ⑤**Web 统一物品选择器**：在线玩家/创造标签页物品数据 + 服务端物理解析；<br ⑥**清理废弃物品**：充能核心/附魔核心等，删除 `GridEnchantments`、`OverdriveUtils`、`grid/enchantment/` 子树；<br ⑦**Mixin**：新增 `mixin/`（`InventoryMixin`/`MenuMixin`/`SlotMixin`）+ `deltanexus.mixins.json`，`build.gradle` 补 dev 启动参数 `--mixin.config` 与 MANIFEST `MixinConfigs`；<br ⑧**Web 编辑器刷兵配置页（0.5.0Beta）**：侧边栏新增「刷兵系统」页——全局保护（`GlobalConfig` 总开关 / 黑名单世界 / 四类上限）、日志（`LogConfig` 级别 / 开关 / 文件路径）、世界层刷兵器与点位组（`Spawner` 全字段：实体或加权实体池、`count`（`3-6` / `per_point:2`）、点位名或 `group:组名` 引用、难度与在线人数条件、安全检测、生成后行为、实体 NBT JSON）；每行可**预览 / 干跑 / 执行一次**（零活动）；**从在线玩家取坐标**：概览与 SSE 的玩家条目新增 `dimension/x/y/z/yaw/pitch`，点位可「取该玩家坐标」或「以该玩家为点位」（维度与执行维度不一致会提示）；新增接口 POST `/api/spawner/global|logging|world/save|action|reload`、GET `/api/spawner/registry`（实体/粒子/音效 id 建议）与 GET `/api/players/positions`；世界层保存为**按名合并**（只删除 `removed_spawners` / `removed_groups` 显式列出的条目），避免两个管理员交替编辑互相覆盖；新增 GameTest `WebEditorSpawnerTests`（载荷结构 / 合并与落盘读回 / 玩家位置字段）；<br ⑨**体验与数据修复（同版本内）**：<br>a.**统一提示层** `DnOverlayTooltips` + `DnTooltipPass`（`ScreenEvent.Render.Post` 最低优先级）——提示不再在原版时机绘制（会被跨格大图标 z≈550 盖住而「看起来没有内容」），改为所有网格渲染之后、z 抬 1000 绘制，并顺带重绘光标物品（原 z≈382 同样会被大图标盖住）；<br>b.**人物预览不再压住窗口**：`DnUiTheme.drawPlayerPreview` 画完角色后清一次深度缓冲（模型会写入比 GUI 平面更靠前的深度值），装备浮动窗口整体再抬 z=900；<br>c.**口袋 / 快捷栏口径纠正**（此前正好相反）：口袋（主背包前 5 格）只收 1x1 普通物品，快捷栏默认 `0-8:ANY`——任意大小都能放进快捷栏（旧配置里的历史默认值 `0-3:ANY,4-8:GRID` 按新默认处理）；<br>d.**装备禁入区**：安全箱（`SafeBoxSlot` + `safeBoxClick` + `GridHandlerBridge.Access.accepts` 三层）与任何口袋（`GridSizes.pocketAccepts` + `GearService` 插入/合并入口）都不接受胸挂/背包；<br>e.**套包（嵌套装备）只接受胸挂/背包**：`GearGridService.applyGridClick` 在 `containerDepth > 1` 时拒绝普通物品（否则内层装备再也取不出来）；<br>f.**嵌套窗口级联收起**：`GearWindowState.validateAll` 逐层校验路径，上层容器里那件装备被取走后其窗口（及下级）立即关闭；<br>g.**装备内容数据安全**：`GearData.write` 不再因「物品未登记为装备」而静默丢弃写入、`GearData.read` 对缺少格式标记的内容块容错读取；`GearMenu` 写回改为「每次重新取当前已装备的那一件」（不再写回构造菜单时捕获的旧栈）；`SyncSafeBoxPacket` 增带 `carriedMenuId`，客户端只在菜单匹配时套用服务端光标栈（修掉容器界面里光标物品被抹成空的错位）；新增 `GridRegressionTests.gearContentsSurviveContainerRoundTrip` 覆盖「放进容器 → 拿出来 → 存档往返」；<br>h.**嵌套「空」改为递归判定**：`GearNest.isEmpty` 现在递归到叶子——里面只装着（同样空的）胸挂/背包时也算空，因此「装着空背包的背包」可以放进当前装备的背包（旧实现只数条目数，把它误判成有东西而拒绝嵌套）；新增 GameTest `nestedEmptyGearCountsAsEmpty`；<br>⑩**协议仍为 `dn3`**（`SyncSafeBoxPacket` 增 `carriedMenuId` 字段，属同一未发布版本内的载荷调整） |

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

构建：`gradlew.bat build` → 产物 `build/libs/deltanexus-0.5.0Beta.jar`

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
├── grid/                          # ★ 格式背包 + 装备模块（0.5.0Beta 内核重写）
│   ├── GridStore.java             # 现役内核：只存锚点 + 唯一写入口 place/take/move/rotate/compact（条目 GridEntry）
│   ├── GridNbt.java               # 现役存档格式（format_version:1，含旧平铺迁移 + .bak）
│   ├── GridHandlerBridge.java     # 现役接线层：GridStore ↔ 旧 ItemStackHandler 形态（仓库/安全箱）
│   ├── StoreContainer.java        # 把「只存锚点」模型暴露成原版 Container
│   ├── GridMarker.java            # 统一 NBT 标记 { DeltaNexus: { rot, gear } }（历史键只读兼容、不再新写）
│   ├── GridSize.java              # 占用尺寸值对象（宽高互换 = 旋转）
│   ├── GearConfig/GearData/GearKind/GearPolicy   # 装备登记表 / 内容 NBT / 种类(rig·backpack) / 原版背包保留格
│   ├── InventoryGridHandler.java  # 门面：0.2.x 全部静态入口 + 事件订阅（实现已下沉 core/adapter）
│   ├── core/                      # 纯函数内核（保留给回归测试与尚未迁移的链路）
│   │   ├── GridDim/UsableMask/StackSnapshot/GridContext   # 尺寸/可用格/快照/上下文
│   │   ├── GridSolver.java        # 求解器：大件优先 → 冲突+合并 → 重排/旋转 → 兜底
│   │   ├── SolvePlan.java         # 求解计划（归属表/落位物品/合并变更/处置建议）
│   │   ├── GridMutation.java      # 事务写入（0.5.0Beta：占位物只清不写）
│   │   ├── GridIntegrity/GridLayout # 一致性校验 / 全模组唯一几何推导
│   │   ├── GridLockManager.java   # canonical 顺序锁（禁止裸 synchronized）
│   │   ├── GridTags.java          # NBT 约定（is_slave/master_slot/rotated + 剥离）
│   │   └── GridService.java       # 脏标记/节流/生命周期（唯一“不纯”编排层）
│   ├── adapter/                   # 适配层
│   │   ├── MenuGridAdapter.java   # 菜单槽位 ↔ 网格视图；容器索引 ↔ 菜单索引；主格解析
│   │   ├── HandlerGridAdapter.java# 裸 ItemStackHandler（仓库/安全箱）适配
│   │   ├── InputGate.java         # 出售模式/白名单/功能开关统一门闸（不引用屏幕类）
│   │   └── GridRenderAdapter.java # 客户端渲染（跳过隐藏槽、按容器维度去重）
│   ├── v2/                        # 旧三层模型（GridInventory/GridOp/PlayerInventoryView），过渡期保留
│   ├── GridRegistry.java          # 容器显式注册（内置：背包/仓库/安全箱/原版 ≥9 格容器）
│   ├── GridSizes.java             # 尺寸规则（配置尺寸 + 旋转 + 快捷栏分级）
│   ├── ItemSizeConfig.java        # 物品尺寸配置（deltanexus-sizes.json + 运行时覆盖）
│   ├── GridConfig.java            # 快捷栏规则 + 容器注册 + legacy_any_container（common.toml）
│   ├── GridClassConfig.java       # 物品「类」背景色（grid_classes.json）
│   ├── GridClientRendering.java   # 客户端事件薄壳（渲染/门闸委托 adapter）
│   ├── GridItems.java             # blocked_slot 物品（仅历史数据只读）
│   ├── GridModEvents.java         # 事件订阅
│   └── network/RotationPacket.java
├── init/ModMenus.java             # 仓库/安全箱/装备菜单注册
├── menu/                          # GridAwareMenu（菜单层点击重定向）+ WarehouseMenu/SafeBoxSlot + GearMenu/GearSlot/ArmorValidSlot
├── mixin/                         # ★ 0.5.0Beta：InventoryMixin/MenuMixin/SlotMixin（+ deltanexus.mixins.json）
├── network/
│   ├── PacketHandler.java         # 通道（协议 dn3），全部包注册
│   └── packet/                    # C2S/S2C 包（仓库滚动/特勤处/旋转/捡起/网格配置/装备 C2SOpenGear·SyncGear 等）
├── server/
│   ├── CommandDN.java            # /dn 指令树（全部管理指令 + 启动迁移钩子）
│   ├── ManufacturingService.java  # 制造/仓库/安全箱/特勤处核心服务（材料识别背包+仓库）
│   ├── CurrencyManager.java       # 货币（物品/计分板/Vault/PlayerPoints）
│   ├── PermissionManager.java     # 权限（warehouse/workbench/special/safe_box/trade/gear，JSON 热加载）
│   ├── GearService.java           # 装备服务（0.5.0Beta：穿戴/打开/物品去向统一裁决 + 同步）
│   ├── GearCommand.java           # /dn gear 指令树（0.5.0Beta）
│   ├── TradeService.java          # 交易行服务（0.2.0Beta：求价/买入流水线/目录/Feed 定时刷新）
│   └── TradeAdminHandler.java     # /dn trade 管理指令（0.2.0Beta）
├── web/                           # Web 网页编辑器（HTTP 服务 + JSON API）
└── spawner/                       # ★ 刷兵系统（0.4.0Beta，零活动）
│   ├── Spawner.java               # 刷兵器数据模型（实体池/点位/条件/安全/行为）
│   ├── GlobalConfig.java          # 刷兵全局配置（spawner/global.json）
│   ├── LogConfig.java             # 刷兵日志配置（spawner/logging.json）
│   ├── SpawnerWorldStore.java     # 世界级 spawners.json + pointgroups.json 读写
│   ├── SpawnerManager.java        # 按世界文件夹路径缓存 + 重载
│   ├── SpawnerService.java        # run/preview/dry-run 执行 + 全局限制/条件/安全/行为/日志
│   └── SpawnerCommand.java        # /dn spawner 指令树
```

资源：`src/main/resources/assets/deltanexus/`（语言 zh_cn/en_us、GUI 贴图、web/index.html）

---

## 四、功能总览

| 模块 | 说明 |
| :--- | :--- |
| 制造系统 | 工作台注册表 + 配方 JSON（输入/输出/NBT 匹配/耗时/等级/并行上限），时间戳离线计时，三状态按钮 |
| 仓库 | 行式滚轮滚动（总行数配置 1~64×9 格），0 级默认解锁 9 格，升级树解锁（unlock_slots） |
| 安全箱 | 独立小仓储（最大 3×3），升级树按**行×列**解锁，行列形状渲染（弃用锁 UI），NBT 限制；**不存放胸挂/背包**（三种入口都拦） |
| 格式背包 | 全部 ≥9 格容器按物品尺寸网格化（0.5.0Beta：足迹格为普通空格、**不再写占位物**）、R 键旋转（可绑键）、放置自动旋转、同类叠加、类背景色；**口袋（主背包前 5 格）只收 1x1 普通物品**，**快捷栏默认 `0-8:ANY` 任意大小可放** |
| 装备物品化 | 0.5.0Beta：胸挂 `rig`（默认 4x3）/ 背包 `backpack`（默认 6x4）为物品，容纳内容随物品 NBT 走；内嵌网格**右键**打开浮动窗口（Windows 风格：标题栏拖动 + 右上角关闭，可取出/放入/Shift 转移；窗口恒在最上层，含人物预览之上）；装备（递归地）内部为空时可嵌套（**装着一个空背包的背包同样算空**），最多 7 层；**套包（嵌套层）只接受胸挂/背包**，普通物品一律拒绝；上层装备被取走后其窗口级联自动关闭；原版背包部分格被装备取代 |
| 特勤处 | 仓库/安全箱升级独立界面（V 键 / /dn open special），独立权限 special |
| 权限 | 仓库/工作台/特勤处/安全箱/交易行/装备**六类型**，全局默认 + 按玩家覆盖，OP 始终允许；`features` 硬开关可整体禁用 mod 功能（UI 恢复原版） |
| Web 编辑器 | 全功能管理页（配方/树/安全箱/玩家/权限/格式背包/交易行/刷兵系统），token 鉴权；刷兵页 = 全局保护 + 日志 + 世界层刷兵器与点位组全量编辑，可从**在线玩家当前位置**取点位坐标，并可预览 / 干跑 / 执行一次 |
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
spawner new|del|list|info|setup <名>  刷兵配置管理（setup 为配置向导）
spawner point|group|set|nbt <…>       点位、点位组、字段、NBT 设置
spawner run|preview|dry-run <…>       执行 / 预演 / 干跑刷兵（零活动，按需一次）
spawner log <…>                       查看刷兵日志配置
help                                  指令总览（/dn <指令  help 查看详情）
```

---

## 六、配置文件

| 文件 | 位置 | 说明 |
| :--- | :--- | :--- |
| ModConfig.toml | config/deltanexus/ | warehouse_rows（默认 12）、base_slots（默认 9）、货币、倍率、计时模式、安全箱默认尺寸、GUI 白名单 |
| common.toml | config/deltanexus/ | 快捷栏规则 hotbar_rules（默认 `0-8:ANY`；口袋固定只收 1x1 普通物品，不受本项影响） |
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
| spawner/global.json | config/deltanexus/spawner/ | 刷兵全局（总开关 enabled/黑名单世界/实体数/TPS/内存上限） |
| spawner/logging.json | config/deltanexus/spawner/ | 刷兵日志（级别/控制台开关/文件写入，输出 logs/deltanexus/spawn/<日期>.log） |
| spawner/<世界文件夹>/spawners.json | config/deltanexus/spawner/ | 刷兵器定义（实体池/点位/条件/安全/行为） |
| spawner/<世界文件夹>/pointgroups.json | config/deltanexus/spawner/ | 点位组（组名 → 点位列表） |

---

## 七、按键（选项 → 控制 → 三角联结）

| 键     | 功能 |
|:------| :--- |
| （未绑定） | 打开仓库 |
| （未绑定） | 打开工作台总览 |
| （未绑定） | 打开特勤处 |
| R     | 旋转光标物品（格式背包容器界面内） |
|（未绑定） | 打开交易行（默认不绑定，可自行设置） |

---

## 八、网络协议（通道 `deltanexus:main`，协议版本 dn3）

- C2S：OpenWarehouse / OpenSafeBox / OpenSpecialOps / StartTask / ClaimTask / CancelTask /
  RefreshTasks / UpgradeWarehouse / UpgradeSafeBox / WarehouseScroll / RequestWorkbenchData /
  RequestSafeBox / SafeBoxClick / Rotation / PickupGridStack / TradeOpen（0.2.0Beta）/ TradeBuy（0.2.0Beta）/
  TradeSell（0.2.0Beta：仓库界面卖出，多槽位+数量）/
  **SellMode（0.3.0Beta：进入/退出出售模式，服务端权威；`active` 布尔）** /
  **OpenGear（0.5.0Beta：请求打开胸挂/背包，`C2SOpenGearPacket`，带 `GearKind`）**
- S2C：OpenScreen（含 SCREEN_SPECIAL / SCREEN_TRADE / SCREEN_GEAR）/ SyncWarehouse / SyncManufacture /
  SyncWorkbenchData / GiveItem / SyncSafeBox / SyncGridSizes（物品尺寸 + 快捷栏规则 + 类配置）/
  SyncTradeCatalog（交易行目录，0.2.0Beta：打开前/成交/补货/重载后下发，含 match_mode/match_keys）/
  SyncGridLayout（网格布局，0.3.0Beta/dn3：菜单打开与布局变化时下发「锚点槽位 + 宽高 + 旋转 + 行宽」）/
  SyncGear（装备同步，0.5.0Beta：`SyncGearPacket`，下发胸挂/背包尺寸与内容，登录/变更时推送）

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

  **安全箱光标同步（dn3，0.5.0Beta 载荷调整）**：`SyncSafeBoxPacket` 增带 `carriedMenuId`——
  服务端下发「真实光标栈 + 它所属的菜单容器 id」，客户端只在 id 与当前菜单一致时套用该光标栈。
  不带 id 地无条件套用，会在容器/仓库界面下把客户端光标抹成空（服务端当时打包的是空栈）。

---

## 九、已知问题与注意点

1. **死亡数据**：已做三层防护（死亡备份 → 重生恢复 → 登录兜底）；若仍出现丢失，检查
   服务端日志 `[DN] 玩家 X 死亡，数据已备份` 与 `已从死亡备份恢复` 是否出现；
2. **旧配置迁移**：`base_slots=9` 的旧服务器启动时自动迁移至 108，0 级玩家登录自动补齐
   至 12 行；`warehouse_pages` 旧键自动迁移为 `warehouse_rows`（页数×9）；
3. **按键冲突**：V/R 键可能与 superbwarfare 等模组冲突（日志会警告），可在控制设置中改绑；
4. **占位物（`blocked_slot`）现状（0.5.0Beta 重写后）**：**没有任何写入路径**（`GridStore` 只存锚点，
   模型里根本没有占位物概念）；仅保留**检测与清理**
   （登录加载、收敛、落盘前）用于自愈旧存档；`C2SPickupGridStackPacket` 仅作兼容空实现保留（不动包表）；
5. **网格内核（0.5.0Beta 重写）**：仓库/安全箱/装备内容统一由 `grid.GridStore`（`final class`）持有——
   只存锚点 `LinkedHashMap<格号, GridEntry>`，占用由 `footprint()/occupancy()` **现算**（不入库、不同步），
   唯一写入口 `place/take/move/rotate/compact` 走「影子推演 → 不变式校验 → 守恒校验 → 提交，任一不过整体放弃」，
   `validate()` 可随时判定容器合法性；旧 `ItemStackHandler` 形态由 `grid.GridHandlerBridge`（已从 `grid/v2/`
   迁到 `grid/` 根）暴露，调用点零改动。`grid/v2/`（`GridInventory`/`GridOp`/`PlayerInventoryView`）为旧三层
   过渡实现保留（仍被 `GridService`/回归测试引用）。装备内容存<b>物品自身 NBT</b>
   （`DeltaNexus.gear`，见 `GearData`），随物品被丢弃/交易/搬运，不出现容器与内容分离。
   **存档格式**（`GridNbt`，`format_version:1`，只写锚点 `{cell,item,w,h,rotated}`）：首次加载自动迁移旧平铺数据，
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
14. **格式背包重写注意（0.5.0Beta 重写后）**：
   - **不再有占位物**：`GridStore` 只存锚点，足迹内其它格「不存在任何数据」即空；`blocked_slot` /
     `deltanexus.master_slot` 这类占位物标记<b>不再写入</b>，仅保留只读检测与清理用于自愈旧存档。
     旧的「占位物索引口径（`master_slot` = 容器索引）」只对旧档清理路径有意义，新代码不要依赖它。
   - **旧档自愈**：加载 / 收敛 / 落盘前会扫描并清除历史 `blocked_slot`；若在存档里看到该键，
     说明数据来自旧版本——登录会自动清除。
   - **容器接管范围**：未注册的模组容器不再启用网格（`/dn grid containers` 查看、`/dn grid register` 添加）。
     老服务器若依赖「所有 ≥9 格容器都被接管」，把 `common.toml` 的 `legacy_any_container` 设为 `true`。
   - **服务端出售门闸（0.3.0Beta，dn3）**：出售模式已服务端权威化——客户端发 `C2SSellModePacket`，
     服务端记在 `WarehouseMenu.setSellMode` 上，`GridAwareMenu.clicked`/`quickMoveStack` 与
     `InputGate.serverSellMode` 据此冻结一切物品移动；屏幕关闭/菜单销毁/断线自动解除。
     仍**必须**保留客户端冻结（`InputGate.clientFrozen()`）：它让界面手感一致、不发无谓点击包。
   - **不要删除** `InventoryGridHandler` 的事件订阅（每 tick 整理与收敛 / 菜单打开下发布局 / 关闭菜单清理 /
     禁止丢出网格内部搬运物）；删掉会让这些操作静默失效。
   - **复制/重叠的排查口径（0.3.0Beta 止血）**：每 tick 只做只读一致性校验，不一致才收敛；
     事务写入是「影子数组 + CAS + 守恒 + 引用共享校验」，任何一项不过关就整体放弃并打 ERROR。
     排障顺序：①日志搜 `网格事务中止` → 说明还有未覆盖的写路径（日志里的 before/after 数量能立刻定位方向）；
     ②带 `-Ddeltanexus.grid.debug=true` 搜 `网格需要收敛` → 看哪种违规在反复出现；
     ③若仍出现「物品复制」，优先查**不经过网格事务的写入点**（其他模组直接 `setStackInSlot`／指令给物／
     死亡掉落与维度切换搬运／`/give`）：它们的正确表现是「制造一次冲突、由下次收敛合法安置」，而不是复制。
15. **装备内容不丢的三条纪律（0.5.0Beta 修复后）**：
    - **写回只认「当前那一件」**：`GearData.write` 需要物品栈本身，任何跨帧/跨菜单持有物品栈的代码
      （历史上 `GearMenu#serverView` 捕获构造时的栈）都必须在使用前重新 `GearService.equipped(...)`；
      否则改动会落到一件已经不在身上的副本上（玩家看到的就是「放进容器再拿出来，里面的东西全没了」）。
    - **`GearData.write` 与登记表解耦**：写回只看物品 NBT，不因「物品当前未登记为装备」而静默 return；
      `GearData.read` 对缺少 `format_version` 的内容块做容错读取（绝不把「有内容但读不出格式」当成空容器，
      否则下一次写回会把内容抹掉）。新增 GameTest `gearContentsSurviveContainerRoundTrip` 覆盖
      「菜单写入 → 外部交付 → 存档往返 → 缺格式标记」四条链路。
    - **光标栈同步要带菜单 id**：`SyncSafeBoxPacket.carried` 只在 `carriedMenuId` 与当前菜单一致时套用。
      不带 id 就套用会让容器/仓库界面下的客户端光标被抹成空，玩家看到「手里的东西凭空消失」。
    - **快捷移动的「合并」必须用 `slot.set` 写回**：`SlotItemHandler`（仓库视口 / 安全箱）的
      `getItem()` 是**拷贝**，就地 `grow` 只会改到拷贝上而源栈照样 `shrink`——物品凭空减少。
      `GearService.moveToAllowed` 已改为 `slot.set(...)` + 按实际落位数量扣减，并新增 GameTest
      `quickMoveMergeKeepsItems` 守住「合并后总数守恒」。
    - **已知残留**：`MenuGridAdapter` 眼里被装备取代的 22 格（containerSlot 14..35）仍是「可用格」
      （只有 `SlotMixin`/界面层把它们隐藏）。因此从旧档升上来的、放在这些格里的物品仍然存在但界面不可见
      （可被制造扣料识别，无法在仓库界面取出/回收）；网格收敛也不会把它们挪走。若日后要彻底处理，
      应在 `MenuGridAdapter#context()` 里把 `GearPolicy` 屏蔽格标为不可用（需同时处理「挪不动」的日志刷屏）。
16. **提示与窗口的绘制层级（0.5.0Beta 修复后）**：格子界面的悬停提示统一由 `DnOverlayTooltips` +
    `DnTooltipPass`（`ScreenEvent.Render.Post` **最低**优先级）在所有网格渲染之后绘制，并把 z 抬 1000
    （网格大图标在 z≈550、提示自身 z≈400）；`DnUiTheme.drawPlayerPreview` 画完人物后清一次深度缓冲，
    装备浮动窗口再整体抬 z=900——三者共同保证「窗口 / 提示 / 人物预览」的上下关系稳定。
    新增格子界面时，要么实现 `DnOverlayTooltips`，要么**不要**在 `super.render` 的时机画提示。

---

## 十、构建与部署

```bat
cd D:\Work\java\DeltaNexus
gradlew.bat build
::::: 产物：build/libs/deltanexus-0.5.0Beta.jar → 放入服务器/客户端 mods/
```

开发运行：`gradlew.bat runClient` / `runServer`（工作目录 `run/`）。

回归测试（GameTest）：`gradlew.bat runGameTestServer`（跑完即退出，约 3 分钟）。
⚠️ 结构模板必须同时存在于 **`run/gameteststructures/`**（文件名 = `<测试类名小写>.<template>.snbt`，
`run/` 在 `.gitignore` 内，需要从 `src/main/resources/data/deltanexus/structures/` 拷一份）；
新增测试类时若忘了这一步，GameTestServer 会在启动测试批次时抛
`Could not find structure file gameteststructures\<类名>.empty.snbt` 并崩溃。

---

## 十一、许可

- **本项目（三角联结 / DeltaNexus）以 MIT License 发布**，全文见根目录 `LICENSE`；
  模组元数据 `gradle.properties` 中 `mod_license=MIT`（会写入 jar 内 `META-INF/mods.toml` 的 `license` 字段）。
- 第三方组件：
  - **Rhino 1.7.15**（MPL 2.0 / GPL 双许可）——交易行价格引擎 JS 沙箱，以已编译类内置进模组 jar，
    未修改源码（分发需保留其许可声明，`LICENSE` 文件末段已附）；
  - Minecraft Forge / MCP 数据等 MDK 模板文件沿用原授权（见 `LICENSE.txt`），不在 MIT 覆盖范围内。

