// DeltaNexus curated architecture map (v8) — deterministic clean routing.
// Every edge is routed through empty inter-lane corridors or an overhead bus
// (multiple levels), each route using its own corridor x-offset, so no route
// overlaps another collinearly and no route crosses an unrelated node.
import { writeFileSync } from 'node:fs';

const NODE_H = 48;
const ROW_PITCH = 84;
const LANE_GAP = 210;
const REGION_PAD = 18;
const MARGIN = 46;
const BUS_TOP = 36;                 // first bus level y (must clear region labels)
const NODE_TOP = 250;               // first node row (big band for bus levels)
const BUS_LEVELS = 16;
const BUS_PITCH = 10;               // max bus y = 36+15*10 = 186 < frame top 220

let BUS_USED = 0;

function px(s, cjk, latin) {
  let p = 0;
  for (const ch of s) p += /[\u2E80-\u9FFF\uF900-\uFAFF]/.test(ch) ? cjk : latin;
  return p;
}
function measure(label, sub) {
  const w = Math.max(px(label, 12.5, 6.9), px(sub, 9.0, 5.1)) + 40;
  return { w: Math.max(170, Math.ceil(w)), h: NODE_H };
}

const NODES = {
  'DeltaNexus': { kind: 'backend', label: 'DeltaNexus', sub: '模组入口：注册/启动' },
  'ModMenus': { kind: 'backend', label: 'ModMenus', sub: 'init · 菜单类型注册' },
  'PacketHandler': { kind: 'messagebus', label: 'PacketHandler', sub: 'network · 通道注册 dn1' },
  'C2SGroup': { kind: 'messagebus', label: 'C2S 请求包 ×13', sub: '开仓/滚动/制造/升级/安全箱/网格' },
  'S2CGroup': { kind: 'messagebus', label: 'S2C 同步包 ×8', sub: '仓库/安全箱/制造/总览/UI 同步' },
  'ManufacturingService': { kind: 'backend', label: 'ManufacturingService', sub: '制造+仓库核心服务' },
  'CommandDN': { kind: 'backend', label: 'CommandDN', sub: '/dn 指令树（配置全指令化）' },
  'CurrencyManager': { kind: 'backend', label: 'CurrencyManager', sub: '货币：item/scoreboard/vault/pp' },
  'PermissionManager': { kind: 'backend', label: 'PermissionManager', sub: '仓库/工作台/特勤处/安全箱权限' },
  'WarehouseMenu': { kind: 'backend', label: 'WarehouseMenu', sub: '仓库容器：滚动视口' },
  'SafeBoxSlot': { kind: 'backend', label: 'SafeBoxSlot', sub: '安全箱槽：服务端四重校验' },
  'GridAwareSlot': { kind: 'backend', label: 'GridAwareSlot', sub: '网格感知槽：预校验' },
  'WarehouseScreen': { kind: 'frontend', label: 'WarehouseScreen', sub: '仓库 GUI' },
  'WorkbenchScreen': { kind: 'frontend', label: 'WorkbenchScreen', sub: '工作台总览 GUI' },
  'ManufactureScreen': { kind: 'frontend', label: 'ManufactureScreen', sub: '制造台进度 GUI' },
  'SpecialOpsScreen': { kind: 'frontend', label: 'SpecialOpsScreen', sub: '升级界面（特勤处）' },
  'BackpackScreen': { kind: 'frontend', label: 'BackpackScreen', sub: '替换原版背包界面' },
  'SafeBoxOverlay': { kind: 'frontend', label: 'SafeBoxOverlay', sub: '安全箱渲染覆盖层' },
  'ClientEvents': { kind: 'frontend', label: 'ClientEvents', sub: 'FORGE 事件：按键开界面' },
  'ClientSetup': { kind: 'frontend', label: 'ClientSetup', sub: '客户端 MOD 事件' },
  'KeyBindings': { kind: 'frontend', label: 'KeyBindings', sub: '按键定义（B/G/V/R）' },
  'IPlayerData': { kind: 'database', label: 'IPlayerData', sub: 'api · 玩家数据接口' },
  'PlayerDataImpl': { kind: 'database', label: 'PlayerDataImpl', sub: 'capability · 数据实现/备份' },
  'CapabilityAttacher': { kind: 'backend', label: 'CapabilityAttacher', sub: 'capability · 实体绑定' },
  'ConfigData': { kind: 'database', label: '配置层 config', sub: '7 个配置/缓存/落盘类' },
  'TaskData': { kind: 'database', label: '任务/工具 common', sub: 'Task/注册表/NBT 匹配等 5 类' },
  'GridEngine': { kind: 'backend', label: 'InventoryGridHandler', sub: 'grid · 网格引擎（服务端权威）' },
  'GridSupport': { kind: 'database', label: '网格配置/注册 grid', sub: '尺寸/规则/类色/渲染等 9 类' },
  'WebEditor': { kind: 'backend', label: 'WebEditorServer', sub: 'web · 内嵌 HTTP 编辑器' },
  'WebCfg': { kind: 'database', label: 'WebConfig', sub: 'web-editor.yml' },
};

// row order inside each lane keeps same-lane neighbours adjacent.
const LANES = [
  { label: '客户端界面 · client / client.gui', files: ['KeyBindings', 'ClientEvents', 'ClientSetup', 'WarehouseScreen', 'WorkbenchScreen', 'ManufactureScreen', 'SpecialOpsScreen', 'BackpackScreen', 'SafeBoxOverlay'] },
  { label: '网络层 · network / network.packet', files: ['PacketHandler', 'C2SGroup', 'S2CGroup'] },
  { label: '服务端核心 · server / init / menu', files: ['DeltaNexus', 'ModMenus', 'WarehouseMenu', 'ManufacturingService', 'CurrencyManager', 'PermissionManager', 'SafeBoxSlot', 'GridAwareSlot', 'CommandDN'] },
  { label: '数据/配置 · api/capability/config/common', files: ['IPlayerData', 'CapabilityAttacher', 'PlayerDataImpl', 'TaskData', 'ConfigData'] },
  { label: '网页编辑器 · web', files: ['WebCfg', 'WebEditor'] },
  { label: '格式背包 · grid', files: ['GridEngine', 'GridSupport'] },
];

const EDGES = [
  ['DeltaNexus', 'PacketHandler', '注册通道'],
  ['DeltaNexus', 'ModMenus', '注册菜单'],
  ['ModMenus', 'WarehouseMenu', '注册类型'],
  ['ClientSetup', 'ModMenus', '取菜单类型'],
  ['ClientSetup', 'WarehouseScreen', '绑定屏幕'],
  ['DeltaNexus', 'ConfigData', '注册/写默认'],
  ['DeltaNexus', 'TaskData', '写默认 JSON'],
  ['DeltaNexus', 'GridSupport', '注册物品/附魔'],
  ['DeltaNexus', 'WebEditor', '启动 Web'],
  ['ClientEvents', 'C2SGroup', 'B/G/V 键'],
  ['WarehouseScreen', 'C2SGroup', '开仓/滚动'],
  ['WorkbenchScreen', 'C2SGroup', '任务/数据'],
  ['ManufactureScreen', 'C2SGroup', '制造操作'],
  ['SpecialOpsScreen', 'C2SGroup', '升级请求'],
  ['BackpackScreen', 'C2SGroup', '点击/捡起'],
  ['SafeBoxOverlay', 'C2SGroup', '请求状态'],
  ['C2SGroup', 'ManufacturingService', '业务受理'],
  ['C2SGroup', 'GridEngine', '旋转/跨格'],
  ['ManufacturingService', 'S2CGroup', '回发同步'],
  ['CommandDN', 'S2CGroup', 'open 开屏'],
  ['CommandDN', 'ManufacturingService', '驱动业务'],
  ['S2CGroup', 'WarehouseScreen', '刷新'],
  ['S2CGroup', 'WorkbenchScreen', '全量渲染'],
  ['S2CGroup', 'ManufactureScreen', '进度'],
  ['S2CGroup', 'SpecialOpsScreen', '刷新'],
  ['S2CGroup', 'BackpackScreen', '渲染'],
  ['S2CGroup', 'SafeBoxOverlay', '渲染'],
  ['ManufacturingService', 'WarehouseMenu', '打开容器'],
  ['ManufacturingService', 'TaskData', '创建任务'],
  ['ManufacturingService', 'ConfigData', '读配方/升级'],
  ['ManufacturingService', 'CurrencyManager', '扣货币'],
  ['ManufacturingService', 'PermissionManager', '权限校验'],
  ['WarehouseMenu', 'IPlayerData', '读仓库'],
  ['SafeBoxSlot', 'IPlayerData', '解锁格判定'],
  ['SafeBoxSlot', 'PermissionManager', '权限校验'],
  ['SafeBoxSlot', 'ConfigData', 'NBT 限制'],
  ['GridAwareSlot', 'GridEngine', '预校验落位'],
  ['CapabilityAttacher', 'PlayerDataImpl', '附加实例'],
  ['PlayerDataImpl', 'IPlayerData', '实现接口'],
  ['PlayerDataImpl', 'TaskData', '持有任务'],
  ['GridEngine', 'IPlayerData', '读容器'],
  ['GridEngine', 'ConfigData', '限制/尺寸/类色'],
  ['GridEngine', 'GridSupport', '配置与注册'],
  ['GridSupport', 'S2CGroup', '网格同步'],
  ['BackpackScreen', 'GridEngine', '网格交互'],
  ['CommandDN', 'ConfigData', '改配置'],
  ['CommandDN', 'TaskData', 'workbench/recipe'],
  ['CommandDN', 'IPlayerData', 'data 指令'],
  ['CommandDN', 'PermissionManager', 'perm 指令'],
  ['WebEditor', 'WebCfg', '读配置'],
  ['WebEditor', 'ConfigData', '编辑配置'],
  ['WebEditor', 'TaskData', '管理工作台'],
  ['WebEditor', 'PermissionManager', '管理权限'],
  ['WebEditor', 'IPlayerData', '查玩家'],
  ['WebEditor', 'ManufacturingService', '触发动作'],
  ['WebEditor', 'CurrencyManager', '货币操作'],
];

// ---------- geometry ----------
function layout() {
  const laneInfo = [];
  for (const lane of LANES) {
    const items = lane.files.map((id) => ({ id, ...measure(NODES[id].label, NODES[id].sub) }));
    const w = Math.max(...items.map((i) => i.w)) + REGION_PAD * 2;
    laneInfo.push({ lane, items, w });
  }
  const geo = {};
  const comps = [];
  let x = MARGIN;
  const laneX = [];
  for (let li = 0; li < laneInfo.length; li++) {
    laneX.push(x);
    const baseX = x + REGION_PAD;
    laneInfo[li].items.forEach((item, r) => {
      const y = NODE_TOP + r * ROW_PITCH;
      geo[item.id] = { x: baseX, y, w: item.w, h: NODE_H, lane: li, row: r, cx: baseX + item.w / 2, cy: y + NODE_H / 2, left: baseX, right: baseX + item.w, top: y, bottom: y + NODE_H };
      comps.push({ id: item.id, type: NODES[item.id].kind, label: NODES[item.id].label, sublabel: NODES[item.id].sub, pos: [Math.round(baseX), Math.round(y)], size: [item.w, NODE_H] });
    });
    x += laneInfo[li].w + LANE_GAP;
  }
  const totalW = x - LANE_GAP + MARGIN;
  const maxRows = Math.max(...laneInfo.map((l) => l.items.length));
  const totalH = NODE_TOP + (maxRows - 1) * ROW_PITCH + NODE_H + 90;
  const bounds = laneInfo.map((li) => ({ kind: 'region', label: li.lane.label, wraps: li.items.map((i) => i.id) }));
  return { comps, bounds, geo, laneInfo, laneX, totalW, totalH };
}
const { comps, bounds, geo, laneInfo, laneX, totalW, totalH } = layout();

function corridorRange(laneA, laneB) {
  const left = Math.min(laneA, laneB);
  const start = laneX[left] + laneInfo[left].w + 6;
  const end = laneX[left + 1] - 6;
  return [start, end];
}
let slotIdx = 0;
function nextCorridorX(range, pad = 10) {
  const w = range[1] - range[0];
  const slots = Math.max(1, Math.floor((w - pad) / 12));
  const idx = slotIdx++ % slots;
  return range[0] + pad + idx * 12;
}
function nextBusY() {
  const y = BUS_TOP + (BUS_USED % BUS_LEVELS) * BUS_PITCH;
  BUS_USED++;
  return y;
}

function route(fromId, toId) {
  const s = geo[fromId];
  const t = geo[toId];
  const dLane = t.lane - s.lane;
  if (dLane === 0) {
    if (Math.abs(t.row - s.row) === 1) {
      const down = t.row > s.row;
      const r = down ? { fromSide: 'bottom', toSide: 'top' } : { fromSide: 'top', toSide: 'bottom' };
      // put a semantic label at the clear gap between the two boxes
      r.labelAt = down ? [s.cx, s.bottom + (t.top - s.bottom) / 2] : [s.cx, t.bottom + (s.top - t.bottom) / 2];
      return r;
    }
    // same lane, non-adjacent rows: sideways to corridor, climb, cross, descend, re-enter
    const useLeft = s.lane > 0;
    const [sg0, sg1] = useLeft ? corridorRange(s.lane - 1, s.lane) : corridorRange(s.lane, s.lane + 1);
    const overY = nextBusY();
    const corrS = Math.max(sg0 + 6, Math.min(sg1 - 6, nextCorridorX([sg0, sg1])));
    const [tg0, tg1] = useLeft ? corridorRange(t.lane - 1, t.lane) : corridorRange(t.lane, t.lane + 1);
    const corrT = Math.max(tg0 + 6, Math.min(tg1 - 6, nextCorridorX([tg0, tg1])));
    const via = [[corrS, s.cy], [corrS, overY], [corrT, overY], [corrT, t.cy]];
    const fromSide = useLeft ? 'left' : 'right';
    const toSide = useLeft ? 'left' : 'right';
    return { fromSide, toSide, via };
  }
  const leftToRight = dLane > 0;
  const adjacent = Math.abs(dLane) === 1;
  if (adjacent) {
    const [g0, g1] = corridorRange(s.lane, t.lane);
    const corrX = Math.max(g0 + 6, Math.min(g1 - 6, nextCorridorX([g0, g1])));
    const via = [[corrX, s.cy], [corrX, t.cy]];
    return leftToRight ? { fromSide: 'right', toSide: 'left', via } : { fromSide: 'left', toSide: 'right', via };
  }
  // far lanes: source corridor on the side facing target, climb, cross at bus
  // level, descend in target-side corridor, enter target side.
  const srcCorrSide = leftToRight ? 'right' : 'left';
  const srcLaneA = srcCorrSide === 'right' ? s.lane : s.lane - 1;
  const srcLaneB = srcCorrSide === 'right' ? s.lane + 1 : s.lane;
  const [sg0, sg1] = corridorRange(srcLaneA, srcLaneB);
  const corrS = Math.max(sg0 + 6, Math.min(sg1 - 6, nextCorridorX([sg0, sg1])));
  const tgtCorrSide = leftToRight ? 'left' : 'right';
  const tgtLaneA = tgtCorrSide === 'right' ? t.lane : t.lane - 1;
  const tgtLaneB = tgtCorrSide === 'right' ? t.lane + 1 : t.lane;
  const [tg0, tg1] = corridorRange(tgtLaneA, tgtLaneB);
  const corrT = Math.max(tg0 + 6, Math.min(tg1 - 6, nextCorridorX([tg0, tg1])));
  const overY = nextBusY();
  const via = [[corrS, s.cy], [corrS, overY], [corrT, overY], [corrT, t.cy]];
  return { fromSide: srcCorrSide, toSide: tgtCorrSide, via };
}

const connections = EDGES.map(([from, to, label, variant], i) => {
  const c = { id: `e${i}`, from, to };
  const r = route(from, to);
  if (r.fromSide) c.fromSide = r.fromSide;
  if (r.toSide) c.toSide = r.toSide;
  if (r.via) c.via = r.via;
  if (r.labelAt && label) c.labelAt = r.labelAt;
  if (label) c.label = label;
  if (variant) c.variant = variant;
  return c;
});

const doc = {
  schema_version: 1,
  diagram_type: 'architecture',
  meta: {
    title: '三角联结 DeltaNexus — Java 结构与核心依赖',
    quality_profile: 'standard',
    viewBox: [Math.ceil((totalW + MARGIN) / 20) * 20, Math.ceil(totalH / 20) * 20],
    views: [
      { id: 'boot', label: '入口注册链', focus: ['DeltaNexus', 'ModMenus', 'PacketHandler', 'ConfigData', 'WebEditor'], note: 'DeltaNexus 启动注册通道/菜单并写默认 JSON 配置。' },
      { id: 'request', label: '仓库主流程', focus: ['ClientEvents', 'C2SGroup', 'ManufacturingService', 'WarehouseMenu', 'S2CGroup', 'WarehouseScreen'], note: 'B 键 → C2S → 服务端开仓库 → S2C 回发刷新。' },
      { id: 'manufacture', label: '制造流程', focus: ['WorkbenchScreen', 'C2SGroup', 'ManufacturingService', 'TaskData', 'S2CGroup', 'ManufactureScreen'], note: '选配方 → 开始制造 → 时间戳任务 → 进度同步。' },
      { id: 'web', label: '网页编辑器', focus: ['WebCfg', 'WebEditor', 'ConfigData', 'TaskData', 'PermissionManager'], note: '浏览器经内嵌 HTTP 服务读写 JSON 配置与权限。' },
    ],
  },
  cards: [
    { dot: 'cyan', title: '分层与职责', items: ['GUI 只渲染/收发包，业务由 ManufacturingService 受理'] },
    { dot: 'emerald', title: '核心设计', items: ['配置热加载 + 时间戳零 Tick 任务 + 增量网络包'] },
    { dot: 'violet', title: '聚合网络包', items: ['C2S×13 + S2C×8（Sync*/GiveItem/OpenScreen），成员见上文字段'] },
    { dot: 'rose', title: '关键关系', items: ['界面↔网络包↔服务端↔Capability 数据；menu 槽位权威校验'] },
  ],
  components: comps,
  boundaries: bounds,
  connections,
};

writeFileSync(new URL('./deltanexus-map.json', import.meta.url), JSON.stringify(doc, null, 2), 'utf8');
console.log(`comps=${comps.length} bounds=${bounds.length} edges=${connections.length} canvas=${totalW + MARGIN}x${totalH} bus=${BUS_USED}/${BUS_LEVELS}`);
