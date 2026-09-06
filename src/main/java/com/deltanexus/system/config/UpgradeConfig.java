package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库升级树配置（config/deltanexus/upgrade_tree.json，1.1.0 起含安全箱升级树）。
 *
 * <pre>{@code
 * { "warehouse_upgrades": [
 *     { "level": 1, "cost_money": 1000, "unlock_slots": 20, "required_items": { "minecraft:oak_planks": 20 } },
 *     { "level": 2, "cost_money": 5000, "unlock_slots": 35, "required_items": { "minecraft:iron_ingot": 10 } }
 *   ],
 *   "safe_box_upgrades": [
 *     { "level": 1, "cost_money": 500, "unlock_rows": 2, "unlock_cols": 2, "required_items": { "minecraft:oak_planks": 10 } }
 *   ]
 * }
 * }</pre>
 *
 * <p>仓库升级树：unlock_slots（累计解锁槽位数）。</p>
 *
 * <p>安全箱升级树（2.0.1 起）：解锁**行 x 列**（unlock_rows / unlock_cols，各 1~3，
 * 解锁格数 = 行 x 列，按 3x3 网格左上角排布）；旧版 unlock_slots 自动迁移为规范化行列
 * （1x1 / 1x2 / 1x3 / 2x2 / 2x3 / 3x3）。0 级 = 配置默认尺寸（safe_box_width x safe_box_height）。</p>
 */
public class UpgradeConfig {

    /** 升级所需材料（含 NBT 匹配，结构与配方原料一致）。 */
    public static class RequiredItem {
        public String item = "minecraft:air";
        public int count = 1;
        public String nbt = "";
        public com.deltanexus.system.common.NbtMatcher.MatchType matchType = com.deltanexus.system.common.NbtMatcher.MatchType.IGNORE;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("item", item);
            o.addProperty("count", count);
            if (!nbt.isBlank()) {
                o.addProperty("nbt", nbt);
            }
            if (matchType != com.deltanexus.system.common.NbtMatcher.MatchType.IGNORE) {
                o.addProperty("match_type", matchType.key());
            }
            return o;
        }

        public static RequiredItem fromJson(JsonObject o) {
            RequiredItem r = new RequiredItem();
            r.item = o.has("item") ? o.get("item").getAsString() : "minecraft:air";
            r.count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
            r.nbt = o.has("nbt") ? o.get("nbt").getAsString() : "";
            r.matchType = com.deltanexus.system.common.NbtMatcher.MatchType.parse(
                    o.has("match_type") ? o.get("match_type").getAsString() : "ignore");
            return r;
        }
    }

    public static class UpgradeLevel {
        public int level;
        public int costMoney;
        public int unlockSlots;
        /** 安全箱升级树（2.0.1）：解锁行数（1 ~ 3；0 = 未设置，沿用 unlock_slots 迁移）。 */
        public int unlockRows;
        /** 安全箱升级树（2.0.1）：解锁列数（1 ~ 3；0 = 未设置）。 */
        public int unlockCols;
        /** 升级所需材料列表（支持 NBT 匹配）。 */
        public final List<RequiredItem> requiredItems = new ArrayList<>();

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("level", level);
            obj.addProperty("cost_money", costMoney);
            obj.addProperty("unlock_slots", unlockSlots);
            if (unlockRows > 0 && unlockCols > 0) {
                obj.addProperty("unlock_rows", unlockRows);
                obj.addProperty("unlock_cols", unlockCols);
            }
            JsonArray items = new JsonArray();
            for (RequiredItem r : requiredItems) {
                items.add(r.toJson());
            }
            obj.add("required_items", items);
            return obj;
        }

        public static UpgradeLevel fromJson(JsonObject obj) {
            UpgradeLevel u = new UpgradeLevel();
            u.level = obj.has("level") ? Math.max(1, obj.get("level").getAsInt()) : 1;
            u.costMoney = obj.has("cost_money") ? Math.max(0, obj.get("cost_money").getAsInt()) : 0;
            if (obj.has("unlock_rows") && obj.has("unlock_cols")) {
                // 2.0.1 新格式：解锁行 x 列（解锁格数 = 行 x 列）
                u.unlockRows = Math.max(1, Math.min(3, obj.get("unlock_rows").getAsInt()));
                u.unlockCols = Math.max(1, Math.min(3, obj.get("unlock_cols").getAsInt()));
                u.unlockSlots = u.unlockRows * u.unlockCols;
            } else {
                // 兼容旧格式：unlock_slots（行列留 0，安全箱树读取后统一迁移）
                u.unlockSlots = obj.has("unlock_slots") ? Math.max(1, obj.get("unlock_slots").getAsInt()) : 1;
            }
            if (obj.has("required_items")) {
                JsonElement el = obj.get("required_items");
                if (el.isJsonArray()) {
                    // 新格式：数组，每项含 item/count/nbt/match_type
                    for (JsonElement e : el.getAsJsonArray()) {
                        if (e.isJsonObject()) {
                            u.requiredItems.add(RequiredItem.fromJson(e.getAsJsonObject()));
                        }
                    }
                } else if (el.isJsonObject()) {
                    // 兼容旧格式：{ "item_id": count }（无 NBT）
                    for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                        RequiredItem r = new RequiredItem();
                        r.item = e.getKey();
                        r.count = e.getValue().getAsInt();
                        u.requiredItems.add(r);
                    }
                }
            }
            return u;
        }
    }

    private static UpgradeConfig INSTANCE;

    private final Path path;
    private final List<UpgradeLevel> upgrades = new ArrayList<>();
    /** 安全箱升级树（1.1.0）：等级 -> 解锁格数（1~9，尺寸按 safeDims 规范化）。 */
    private final List<UpgradeLevel> safeUpgrades = new ArrayList<>();

    private UpgradeConfig(Path path) {
        this.path = path;
    }

    public static synchronized UpgradeConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new UpgradeConfig(FMLPaths.CONFIGDIR.get().resolve("deltanexus/upgrade_tree.json"));
        }
        return INSTANCE;
    }

    public Path path() {
        return path;
    }

    public synchronized void reload() {
        upgrades.clear();
        safeUpgrades.clear();
        JsonObject root = JsonConfigWriter.readJson(path);
        if (root == null) {
            return;
        }
        if (root.has("warehouse_upgrades")) {
            for (JsonElement el : root.getAsJsonArray("warehouse_upgrades")) {
                if (el.isJsonObject()) {
                    upgrades.add(UpgradeLevel.fromJson(el.getAsJsonObject()));
                }
            }
        }
        if (root.has("safe_box_upgrades")) {
            for (JsonElement el : root.getAsJsonArray("safe_box_upgrades")) {
                if (el.isJsonObject()) {
                    safeUpgrades.add(UpgradeLevel.fromJson(el.getAsJsonObject()));
                }
            }
        }
        upgrades.sort((a, b) -> Integer.compare(a.level, b.level));
        safeUpgrades.sort((a, b) -> Integer.compare(a.level, b.level));
        migrateSafeDims();
        DeltaNexus.LOGGER.info("[DN] 升级树热加载完成：主树 {} 级，安全箱 {} 级",
                upgrades.size(), safeUpgrades.size());
    }

    /** 安全箱树兼容迁移（2.0.1）：旧 unlock_slots 换算为规范化行列（1x1/1x2/1x3/2x2/2x3/3x3）。 */
    private void migrateSafeDims() {
        for (UpgradeLevel u : safeUpgrades) {
            if (u.unlockRows <= 0 || u.unlockCols <= 0) {
                int[] dims = safeDims(u.unlockSlots);
                u.unlockRows = dims[1];
                u.unlockCols = dims[0];
                u.unlockSlots = u.unlockRows * u.unlockCols;
            }
        }
    }

    public List<UpgradeLevel> all() {
        return upgrades;
    }

    public UpgradeLevel getLevel(int level) {
        for (UpgradeLevel u : upgrades) {
            if (u.level == level) {
                return u;
            }
        }
        return null;
    }

    /** 当前等级之后的下一级升级方案；无则返回 null。 */
    public UpgradeLevel next(int currentLevel) {
        UpgradeLevel best = null;
        for (UpgradeLevel u : upgrades) {
            if (u.level > currentLevel && (best == null || u.level < best.level)) {
                best = u;
            }
        }
        return best;
    }

    public int maxLevel() {
        int max = 0;
        for (UpgradeLevel u : upgrades) {
            max = Math.max(max, u.level);
        }
        return max;
    }

    /** 升级树中所有等级解锁槽位的最大值（无等级时 0）；用于页数自动扩容检测。 */
    public int maxUnlockSlots() {
        int max = 0;
        for (UpgradeLevel u : upgrades) {
            max = Math.max(max, u.unlockSlots);
        }
        return max;
    }

    /**
     * 指定等级对应的解锁槽位数：取不高于 level 的最高已定义等级的 unlockSlots
     * （升级语义为累计解锁数）；无已定义等级时返回 0。
     */
    public int unlockSlotsForLevel(int level) {
        UpgradeLevel best = null;
        for (UpgradeLevel u : upgrades) {
            if (u.level <= level && (best == null || u.level > best.level)) {
                best = u;
            }
        }
        return best == null ? 0 : best.unlockSlots;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (UpgradeLevel u : upgrades) {
            arr.add(u.toJson());
        }
        root.add("warehouse_upgrades", arr);
        JsonArray safeArr = new JsonArray();
        for (UpgradeLevel u : safeUpgrades) {
            safeArr.add(u.toJson());
        }
        root.add("safe_box_upgrades", safeArr);
        return root;
    }

    /** 解析 + 内存刷新 + 异步防反跳落盘（仓库树与安全箱树同时更新）。 */
    public synchronized void save(String jsonString) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(jsonString).getAsJsonObject();
            upgrades.clear();
            if (root.has("warehouse_upgrades")) {
                for (JsonElement el : root.getAsJsonArray("warehouse_upgrades")) {
                    if (el.isJsonObject()) {
                        upgrades.add(UpgradeLevel.fromJson(el.getAsJsonObject()));
                    }
                }
            }
            safeUpgrades.clear();
            if (root.has("safe_box_upgrades")) {
                for (JsonElement el : root.getAsJsonArray("safe_box_upgrades")) {
                    if (el.isJsonObject()) {
                        safeUpgrades.add(UpgradeLevel.fromJson(el.getAsJsonObject()));
                    }
                }
            }
            upgrades.sort((a, b) -> Integer.compare(a.level, b.level));
            safeUpgrades.sort((a, b) -> Integer.compare(a.level, b.level));
            migrateSafeDims();
            JsonConfigWriter.saveJsonDebounced(path, toJson(), ModConfig.saveDebounceMs());
            DeltaNexus.LOGGER.info("[DN] 升级树已更新");
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 升级树 JSON 解析失败: {}", e.getMessage());
        }
    }

    /** 获取或创建指定等级节点（不存在则创建空节点并加入列表）。 */
    public synchronized UpgradeLevel getOrCreate(int level) {
        for (UpgradeLevel u : upgrades) {
            if (u.level == level) {
                return u;
            }
        }
        UpgradeLevel u = new UpgradeLevel();
        u.level = level;
        upgrades.add(u);
        upgrades.sort((a, b) -> Integer.compare(a.level, b.level));
        return u;
    }

    /** 将当前内存状态落盘（防反跳）。 */
    public synchronized void saveNow() {
        JsonConfigWriter.saveJsonDebounced(path, toJson(), ModConfig.saveDebounceMs());
    }

    /** 添加升级所需材料（含 NBT 匹配模式）。 */
    public synchronized void addRequiredItem(int level, String itemId, int count,
                                              String nbt, com.deltanexus.system.common.NbtMatcher.MatchType matchType) {
        UpgradeLevel u = getOrCreate(level);
        RequiredItem r = new RequiredItem();
        r.item = itemId;
        r.count = count;
        r.nbt = nbt;
        r.matchType = matchType;
        u.requiredItems.add(r);
        saveNow();
    }

    /** 删除升级所需材料（按索引）。 */
    public synchronized boolean removeRequiredItem(int level, int index) {
        UpgradeLevel u = getLevel(level);
        if (u == null || index < 0 || index >= u.requiredItems.size()) {
            return false;
        }
        u.requiredItems.remove(index);
        saveNow();
        return true;
    }

    /** 修改升级所需材料数量（按索引，1.0.4）。 */
    public synchronized boolean updateRequiredItem(int level, int index, int count) {
        UpgradeLevel u = getLevel(level);
        if (u == null || index < 0 || index >= u.requiredItems.size() || count < 1) {
            return false;
        }
        u.requiredItems.get(index).count = count;
        saveNow();
        return true;
    }

    // ------------------------------------------------------------------
    // 安全箱升级树（1.1.0）
    // ------------------------------------------------------------------

    public List<UpgradeLevel> safeAll() {
        return safeUpgrades;
    }

    public UpgradeLevel safeGetLevel(int level) {
        for (UpgradeLevel u : safeUpgrades) {
            if (u.level == level) {
                return u;
            }
        }
        return null;
    }

    /** 安全箱当前等级之后的下一级升级方案；无则返回 null。 */
    public UpgradeLevel safeNext(int currentLevel) {
        UpgradeLevel best = null;
        for (UpgradeLevel u : safeUpgrades) {
            if (u.level > currentLevel && (best == null || u.level < best.level)) {
                best = u;
            }
        }
        return best;
    }

    public int safeMaxLevel() {
        int max = 0;
        for (UpgradeLevel u : safeUpgrades) {
            max = Math.max(max, u.level);
        }
        return max;
    }

    /** 安全箱指定等级对应的解锁格数（累计语义：取不高于 level 的最高已定义等级；行 x 列）；无定义返回 0。 */
    public int safeUnlockSlotsForLevel(int level) {
        UpgradeLevel best = null;
        for (UpgradeLevel u : safeUpgrades) {
            if (u.level <= level && (best == null || u.level > best.level)) {
                best = u;
            }
        }
        return best == null ? 0 : Math.max(1, Math.min(9, best.unlockSlots));
    }

    /**
     * 安全箱指定等级对应的尺寸 {宽(列), 高(行)}（累计语义同 {@link #safeUnlockSlotsForLevel}）；
     * 无已定义等级时返回 null。
     */
    public int[] safeDimsForLevel(int level) {
        UpgradeLevel best = null;
        for (UpgradeLevel u : safeUpgrades) {
            if (u.level <= level && (best == null || u.level > best.level)) {
                best = u;
            }
        }
        if (best == null) {
            return null;
        }
        return new int[]{Math.max(1, Math.min(3, best.unlockCols)), Math.max(1, Math.min(3, best.unlockRows))};
    }

    /**
     * 安全箱格数 -> 规范化尺寸 {宽, 高}（最大 3x3）：
     * 1→1x1，2→1x2，3→1x3，4→2x2，6→2x3，9→3x3；非法值（5/7/8）向上取整到下一个合法格数。
     */
    public static int[] safeDims(int slots) {
        int s = Math.max(1, Math.min(9, slots));
        int[] valid = {1, 2, 3, 4, 6, 9};
        for (int v : valid) {
            if (s <= v) {
                s = v;
                break;
            }
        }
        return switch (s) {
            case 1 -> new int[]{1, 1};
            case 2 -> new int[]{1, 2};
            case 3 -> new int[]{1, 3};
            case 4 -> new int[]{2, 2};
            case 6 -> new int[]{2, 3};
            default -> new int[]{3, 3};
        };
    }

    /** 获取或创建安全箱升级等级节点（不存在则创建空节点并加入列表）。 */
    public synchronized UpgradeLevel safeGetOrCreate(int level) {
        for (UpgradeLevel u : safeUpgrades) {
            if (u.level == level) {
                return u;
            }
        }
        UpgradeLevel u = new UpgradeLevel();
        u.level = level;
        safeUpgrades.add(u);
        safeUpgrades.sort((a, b) -> Integer.compare(a.level, b.level));
        return u;
    }

    /** 删除安全箱升级等级。 */
    public synchronized boolean safeRemove(int level) {
        boolean removed = safeUpgrades.removeIf(u -> u.level == level);
        if (removed) {
            saveNow();
        }
        return removed;
    }

    /** 添加安全箱升级所需材料（含 NBT 匹配模式）。 */
    public synchronized void safeAddRequiredItem(int level, String itemId, int count,
                                                  String nbt, com.deltanexus.system.common.NbtMatcher.MatchType matchType) {
        UpgradeLevel u = safeGetOrCreate(level);
        RequiredItem r = new RequiredItem();
        r.item = itemId;
        r.count = count;
        r.nbt = nbt;
        r.matchType = matchType;
        u.requiredItems.add(r);
        saveNow();
    }

    /** 删除安全箱升级所需材料（按索引）。 */
    public synchronized boolean safeRemoveRequiredItem(int level, int index) {
        UpgradeLevel u = safeGetLevel(level);
        if (u == null || index < 0 || index >= u.requiredItems.size()) {
            return false;
        }
        u.requiredItems.remove(index);
        saveNow();
        return true;
    }

    /** 修改安全箱升级所需材料数量（按索引）。 */
    public synchronized boolean safeUpdateRequiredItem(int level, int index, int count) {
        UpgradeLevel u = safeGetLevel(level);
        if (u == null || index < 0 || index >= u.requiredItems.size() || count < 1) {
            return false;
        }
        u.requiredItems.get(index).count = count;
        saveNow();
        return true;
    }

    /** 首次启动：若文件不存在则写出默认升级树。 */
    public void writeDefaultIfMissing() {
        if (Files.exists(path)) {
            reload();
            return;
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (Exception e) {
            DeltaNexus.LOGGER.error("[DN] 创建配置目录失败: {}", e.getMessage());
            return;
        }
        UpgradeLevel l1 = new UpgradeLevel();
        l1.level = 1;
        l1.costMoney = 1000;
        l1.unlockSlots = 20;
        RequiredItem r1 = new RequiredItem();
        r1.item = "minecraft:oak_planks";
        r1.count = 20;
        l1.requiredItems.add(r1);
        UpgradeLevel l2 = new UpgradeLevel();
        l2.level = 2;
        l2.costMoney = 5000;
        l2.unlockSlots = 35;
        RequiredItem r2 = new RequiredItem();
        r2.item = "minecraft:iron_ingot";
        r2.count = 10;
        l2.requiredItems.add(r2);
        upgrades.add(l1);
        upgrades.add(l2);

        // 安全箱默认升级树（2.0.1 行列制：0 级 = 配置默认尺寸 1x1；1/2/3 级依次扩至 2x2 / 2x3 / 3x3）
        UpgradeLevel s1 = new UpgradeLevel();
        s1.level = 1;
        s1.costMoney = 500;
        s1.unlockRows = 2;
        s1.unlockCols = 2;
        s1.unlockSlots = 4;
        RequiredItem sr1 = new RequiredItem();
        sr1.item = "minecraft:oak_planks";
        sr1.count = 10;
        s1.requiredItems.add(sr1);
        UpgradeLevel s2 = new UpgradeLevel();
        s2.level = 2;
        s2.costMoney = 2000;
        s2.unlockRows = 2;
        s2.unlockCols = 3;
        s2.unlockSlots = 6;
        RequiredItem sr2 = new RequiredItem();
        sr2.item = "minecraft:iron_ingot";
        sr2.count = 5;
        s2.requiredItems.add(sr2);
        UpgradeLevel s3 = new UpgradeLevel();
        s3.level = 3;
        s3.costMoney = 5000;
        s3.unlockRows = 3;
        s3.unlockCols = 3;
        s3.unlockSlots = 9;
        RequiredItem sr3 = new RequiredItem();
        sr3.item = "minecraft:diamond";
        sr3.count = 1;
        s3.requiredItems.add(sr3);
        safeUpgrades.add(s1);
        safeUpgrades.add(s2);
        safeUpgrades.add(s3);

        JsonConfigWriter.writeJsonNow(path, toJson());
        DeltaNexus.LOGGER.info("[DN] 已生成默认升级树");
    }
}
