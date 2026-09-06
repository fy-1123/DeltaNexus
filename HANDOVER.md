# 三角联结（DeltaNexus）交接文档

> 版本：0.1.0Beta（Forge 1.20.1 / Minecraft 1.20.1，兼容 Mohist 混合服务端；更名自 三角洲系统 DeltaForceSystem）
> 仓库：`D:\Work\java\DeltaForceSystem`（本地 git，无远程 push 目标）
> 模组 ID：`deltanexus`｜显示名：三角联结｜指令：`/dn`｜日志前缀：`[DN]`

---

## 一、项目概述

三角联结是一个**制造 + 仓库 + 格子格式背包**整合型 Mod，核心设计哲学：

- **配置热加载**：配方/升级树/工作台/权限/安全箱限制全部 JSON 化，`/dn reload` 即时生效；
- **时间戳驱动**：制造任务零 Tick 依赖，离线照常计时（时间戳差计算）；
- **增量网络包**：同步包只发轻量字段，仓库物品走容器标准同步；
- **格子格式背包**：背包/仓库/安全箱及全部 ≥9 格容器按物品占用尺寸
  网格化整理、跨格渲染、R 键旋转、物品「类」背景色。

### 版本历史

| 版本 | 内容 |
| :--- | :--- |
| 1.0.0Alpha~1.1.0Alpha | 制造台 + 仓库翻页 + 安全箱 + 权限管理 + Web 编辑器基线 |
| 2.0.0Alpha | 格式背包模块融合（expansionpack 源码并入 `com.deltanexus.system.grid`，含 git 历史） |
| 2.0.1Alpha | 仓库翻页→滚轮滚动（12 行视口）、安全箱升级树行列制、网格性能与放置校验 |
| 2.0.2Alpha | 特勤处独立升级界面、格式背包配置指令/Web、无处可放回指针、同类叠加 |
| 2.0.3Alpha | 特勤处 V 键+独立权限、安全箱行列形状渲染、材料识别仓库、物品类背景色、非左上角捡起 |
| 2.0.4Alpha | 死亡备份恢复、12 行视口、放置自动旋转、旋转可绑键、特勤处打开修复、安全箱跨格渲染修复 |
| 2.0.5Alpha | 死亡重生兜底、base_slots 迁移与登录补齐、旋转按键改屏幕事件、1×1 类色渲染、撤销点击移动 |
| 2.0.6Alpha | Web 权限页特勤处支持、登录补齐强制 12 行、类命令顶层别名、死亡备份日志 |
| 0.1.0Beta | 项目更名 **三角联结（DeltaNexus）**：modid `deltanexus`、指令 `/dn`、日志前缀 `[DN]`、包名 `com.deltanexus.system`、配置目录 `config/deltanexus/`、NBT 键 `deltanexus.*`；旧档与旧配置视为不兼容 |

---

## 二、技术栈与环境

| 项 | 值 |
| :--- | :--- |
| Minecraft | 1.20.1（official mappings） |
| Forge | 47.4.10（gradle.properties `forge_version`，兼容 47.4.x） |
| Java | 17（toolchain） |
| Gradle | 8.8（wrapper），ForgeGradle 6.x |
| 兼容目标 | Mohist 混合服务端（Vault/PlayerPoints 经 Bukkit API 反射访问） |

构建：`gradlew.bat build` → 产物 `build/libs/deltanexus-0.1.0Beta.jar`

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
│   ├── ClientEvents.java          # B/G/V/R 键、按键处理
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
│       └── PlayerLayout.java      # 玩家栏位布局
├── common/                        # Task/TaskStatus/WorkbenchRegistry/FormatUtil/NbtMatcher
├── config/
│   ├── ModConfig.java             # 服务端配置（ModConfig.toml，warehouse_rows/base_slots 等 + 迁移）
│   ├── Recipe/RecipeCache         # 配方模型与缓存（JSON 热加载）
│   ├── UpgradeConfig.java         # 仓库/安全箱升级树（行列制）
│   └── SafeBoxRestrictions.java   # 安全箱 NBT 限制
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
│   └── PermissionManager.java     # 权限（warehouse/workbench/special，JSON 热加载）
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
| 权限 | 仓库/工作台/特勤处三类型，全局默认 + 按玩家覆盖，OP 始终允许 |
| Web 编辑器 | 全功能管理页（配方/树/安全箱/玩家/权限/格式背包），token 鉴权 |
| 货币 | 物品（默认绿宝石）/计分板/Vault/PlayerPoints |

---

## 五、指令大全（`/dn`，OP 权限 4）

```
open warehouse|special|manufacture    打开仓库/特勤处/工作台总览
reload / export                       重载配置 / 导出备份
setting speed|queue|mode|currency     全局参数（倍率/队列/计时模式/货币）
warehouse rows <1-64>|slots <0-576>   仓库总行数 / 0 级初始解锁
safe size <w> <h>|get|add|remove|cost|rows <等级> <行> <列>|additem|delitem|setitem
safe restrict|restrictions|unrestrict 安全箱 NBT 限制管理
grid list|size|remove|hotbar          格式背包：物品尺寸 / 快捷栏规则
grid class list|set|remove            格式背包：类颜色管理
grid (class) setclass|unsetclass      格式背包：物品归属类（顶层与 class 子命令等价）
perm get|set|remove|default           权限（warehouse/workbench/special/safe_box/all）
perm op|getop                            OP 豁免开关
feature get|set                         玩家 mod 功能总开关
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
| permissions.json | config/deltanexus/ | 权限（default_* + players 覆盖，含 special/safe_box/features） |
| safe_box_restrictions.json | config/deltanexus/ | 安全箱 NBT 限制 |
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

---

## 八、网络协议（通道 `deltanexus:main`，协议版本 dn1）

- C2S：OpenWarehouse / OpenSafeBox / OpenSpecialOps / StartTask / ClaimTask / CancelTask /
  RefreshTasks / UpgradeWarehouse / UpgradeSafeBox / WarehouseScroll / RequestWorkbenchData /
  RequestSafeBox / SafeBoxClick / Rotation / PickupGridStack
- S2C：OpenScreen（含 SCREEN_SPECIAL）/ SyncWarehouse / SyncManufacture / SyncWorkbenchData /
  GiveItem / SyncSafeBox / SyncGridSizes（物品尺寸 + 快捷栏规则 + 类配置，登录/修改后推送）

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
   （`/dn grid class setclass <物品> <类>`），修改后自动同步客户端（登录时亦推送）。

---

## 十、构建与部署

```bat
cd D:\Work\java\DeltaNexus
gradlew.bat build
:: 产物：build/libs/deltanexus-0.1.0Beta.jar → 放入服务器/客户端 mods/
```

开发运行：`gradlew.bat runClient` / `runServer`（工作目录 `run/`）。
