package com.deltanexus.system.grid;

import com.deltanexus.system.grid.InventoryGridHandler.ItemDim;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品尺寸配置（格式背包，2.0.0 集成自 expansionpack）。
 *
 * <p>配置文件：{@code config/deltanexus-sizes.json}（"item_id": {"w": n, "h": m}），
 * 首次启动自动生成内置默认尺寸（盾牌/弓弩/三叉戟/方块/食物/矿物等）。</p>
 *
 * <p>2.0.2：支持指令/Web 运行时修改（{@link #setSize}/{@link #removeSize}），
 * 修改后服务端广播 {@code SyncGridSizesPacket}，客户端以运行时覆盖（不写客户端文件）保持一致。</p>
 */
public class ItemSizeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, SizeDef> ITEM_SIZES = new HashMap<>();
    /** 客户端运行时覆盖（来自服务端同步包，会话内有效）。 */
    private static final Map<String, SizeDef> RUNTIME = new HashMap<>();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("deltanexus-sizes.json");

    private static class SizeDef {
        int w; int h;
        public SizeDef(int w, int h) { this.w = w; this.h = h; }
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                Type type = new TypeToken<Map<String, SizeDef>>(){}.getType();
                Map<String, SizeDef> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    ITEM_SIZES.clear();
                    ITEM_SIZES.putAll(loaded);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            generateDefaults();
            save();
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(ITEM_SIZES, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 2.0.2：设置物品自定义尺寸（写入配置并落盘）；返回是否成功。 */
    public static synchronized boolean setSize(String itemId, int w, int h) {
        if (itemId == null || itemId.isBlank() || w < 1 || h < 1) {
            return false;
        }
        ITEM_SIZES.put(itemId.trim(), new SizeDef(Math.min(9, w), Math.min(9, h)));
        save();
        return true;
    }

    /** 2.0.2：移除物品自定义尺寸（恢复内置规则）；返回是否成功。 */
    public static synchronized boolean removeSize(String itemId) {
        if (itemId == null || itemId.isBlank() || ITEM_SIZES.remove(itemId.trim()) == null) {
            return false;
        }
        save();
        return true;
    }

    /** 2.0.2：全部自定义尺寸（指令/Web 展示用）。 */
    public static synchronized java.util.LinkedHashMap<String, int[]> allCustom() {
        java.util.LinkedHashMap<String, int[]> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SizeDef> e : ITEM_SIZES.entrySet()) {
            out.put(e.getKey(), new int[]{e.getValue().w, e.getValue().h});
        }
        return out;
    }

    /** 2.0.2：应用客户端运行时覆盖（服务端同步包；清空后回退本地配置）。 */
    public static synchronized void applyRuntime(java.util.Map<String, int[]> sizes) {
        RUNTIME.clear();
        if (sizes != null) {
            for (Map.Entry<String, int[]> e : sizes.entrySet()) {
                if (e.getValue() != null && e.getValue().length >= 2) {
                    RUNTIME.put(e.getKey(), new SizeDef(Math.max(1, e.getValue()[0]), Math.max(1, e.getValue()[1])));
                }
            }
        }
    }

    public static ItemDim getSize(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key == null) {
            return null;
        }
        SizeDef def = RUNTIME.get(key.toString());
        if (def == null) {
            def = ITEM_SIZES.get(key.toString());
        }
        if (def != null) {
            return new ItemDim(def.w, def.h);
        }
        return null;
    }

    private static void generateDefaults() {
        add(Items.IRON_INGOT, 1, 1);
    }

    private static void add(Item item, int w, int h) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key != null) ITEM_SIZES.put(key.toString(), new SizeDef(w, h));
    }
}
