package com.deltanexus.system.grid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 装备登记表（0.5.0Beta）——<b>用配置声明哪些物品是背包/胸挂，以及各自多大</b>。
 *
 * <p>本模组不注册任何「背包物品」「胸挂物品」：管理员把服务器上<b>已有的任意物品</b>
 * （原版、其他模组、自制）登记成装备即可。这样图标就是那个物品本身的图标，
 * 档位由配置决定（加档位不发版），与本模组「仓库行数 / 安全箱尺寸皆由配置决定」的风格一致。</p>
 *
 * <p>配置文件 {@code config/deltanexus/gear.json}（热加载，挂现有 {@code /dn reload}）：</p>
 * <pre>
 * { "minecraft:bundle":   { "kind": "backpack", "w": 6, "h": 4 },
 *   "minecraft:leather_horse_armor": { "kind": "rig", "w": 4, "h": 3 } }
 * </pre>
 *
 * <ul>
 *   <li>{@code kind} —— {@code backpack} 背包 / {@code rig} 胸挂（见 {@link GearKind}）；</li>
 *   <li>{@code w}/{@code h} —— 网格宽高（决定容量）；超出 {@link GearKind#maxSize()} 会被夹取，非法值回退默认。</li>
 * </ul>
 *
 * <p>物品 NBT 只保存「装了什么」（见 {@link GearData}）；尺寸与种类<b>只存在于本表</b>，
 * 因此改配置即可整体调整该物品的容量，不需要迁移任何存档。</p>
 */
public final class GearConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, SpecDef>>() {
    }.getType();

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus/gear.json");

    /** 物品 id → 装备规格。 */
    private static final Map<String, GearSpec> GEAR = new LinkedHashMap<>();

    private GearConfig() {
    }

    /** 一条登记记录的磁盘形态。 */
    private static final class SpecDef {
        String kind;
        Integer w;
        Integer h;
    }

    /** 对外只读规格：种类 + 网格尺寸。 */
    public record GearSpec(GearKind kind, GridSize size) {

        public GridSize size() {
            return size;
        }
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /** 读取配置（热加载入口）；文件不存在时写出空表。 */
    public static synchronized void load() {
        if (!Files.exists(CONFIG_PATH)) {
            writeDefaultIfMissing();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Map<String, SpecDef> loaded = GSON.fromJson(reader, TYPE);
            GEAR.clear();
            if (loaded != null) {
                for (Map.Entry<String, SpecDef> e : loaded.entrySet()) {
                    GearSpec spec = toSpec(e.getValue());
                    if (spec != null && e.getKey() != null && !e.getKey().isBlank()) {
                        GEAR.put(e.getKey().trim(), spec);
                    }
                }
            }
        } catch (IOException e) {
            com.deltanexus.system.DeltaNexus.LOGGER.error("[DN] 读取 gear.json 失败", e);
        }
    }

    /** 落盘。 */
    public static synchronized void save() {
        Map<String, SpecDef> out = new TreeMap<>();
        for (Map.Entry<String, GearSpec> e : GEAR.entrySet()) {
            SpecDef def = new SpecDef();
            def.kind = e.getValue().kind().id();
            def.w = e.getValue().size().w();
            def.h = e.getValue().size().h();
            out.put(e.getKey(), def);
        }
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(out, writer);
            }
        } catch (IOException e) {
            com.deltanexus.system.DeltaNexus.LOGGER.error("[DN] 写入 gear.json 失败", e);
        }
    }

    /** 首次启动生成空表（不擅自把任何物品登记成装备）。 */
    public static synchronized void writeDefaultIfMissing() {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        save();
        com.deltanexus.system.DeltaNexus.LOGGER.info(
                "[DN] 已生成 config/deltanexus/gear.json（空表）：用 /dn gear set <backpack|rig> <宽> <高> 手持物品登记，"
                        + "或直接编辑该文件后 /dn reload");
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 该物品是否被登记为装备。 */
    public static synchronized boolean isGear(Item item) {
        return item != null && GEAR.containsKey(idOf(item));
    }

    /** 该物品是否被登记为装备。 */
    public static boolean isGear(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isGear(stack.getItem());
    }

    /** 装备种类（未登记返回 {@code null}）。 */
    @Nullable
    public static synchronized GearKind kindOf(Item item) {
        GearSpec spec = specOf(item);
        return spec == null ? null : spec.kind();
    }

    /** 网格尺寸（未登记返回 {@code null}）。 */
    @Nullable
    public static synchronized GridSize sizeOf(Item item) {
        GearSpec spec = specOf(item);
        return spec == null ? null : spec.size();
    }

    /** 规格（未登记返回 {@code null}）。 */
    @Nullable
    public static synchronized GearSpec specOf(Item item) {
        return item == null ? null : GEAR.get(idOf(item));
    }

    /** 规格（未登记返回 {@code null}）。 */
    @Nullable
    public static GearSpec specOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : specOf(stack.getItem());
    }

    /** 全部登记项（指令/Web 展示用，按物品 id 排序）。 */
    public static synchronized Map<String, GearSpec> all() {
        return new TreeMap<>(GEAR);
    }

    public static synchronized int size() {
        return GEAR.size();
    }

    // ------------------------------------------------------------------
    // 修改（指令 / Web）
    // ------------------------------------------------------------------

    /** 登记或改写某物品的装备规格；返回是否成功（false = 尺寸非法或种类未知）。 */
    public static synchronized boolean set(Item item, GearKind kind, GridSize size) {
        if (item == null || kind == null) {
            return false;
        }
        String id = idOf(item);
        if (id.isEmpty()) {
            return false;
        }
        GEAR.put(id, new GearSpec(kind, clamp(kind, size)));
        save();
        return true;
    }

    /** 按物品 id 字符串登记（Web 用；物品必须已存在于注册表）。返回是否成功。 */
    public static boolean set(String itemId, String kindId, int w, int h) {
        ResourceLocation key = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        GearKind kind = GearKind.byId(kindId);
        if (key == null || kind == null) {
            return false;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        if (item == null) {
            return false;
        }
        return set(item, kind, new GridSize(w, h));
    }

    /** 取消登记；返回是否确有该项。 */
    public static synchronized boolean remove(Item item) {
        if (item == null || GEAR.remove(idOf(item)) == null) {
            return false;
        }
        save();
        return true;
    }

    /** 按物品 id 字符串取消登记。 */
    public static boolean remove(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        if (key == null) {
            return false;
        }
        String id = key.toString();
        if (GEAR.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String idOf(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "" : key.toString();
    }

    @Nullable
    private static GearSpec toSpec(SpecDef def) {
        if (def == null) {
            return null;
        }
        GearKind kind = GearKind.byId(def.kind);
        if (kind == null) {
            return null;
        }
        int w = def.w == null ? kind.defaultSize().w() : def.w;
        int h = def.h == null ? kind.defaultSize().h() : def.h;
        return new GearSpec(kind, clamp(kind, new GridSize(w, h)));
    }

    private static GridSize clamp(GearKind kind, GridSize size) {
        GridSize base = size == null ? kind.defaultSize() : size;
        GridSize max = kind.maxSize();
        return new GridSize(Math.min(max.w(), base.w()), Math.min(max.h(), base.h()));
    }
}