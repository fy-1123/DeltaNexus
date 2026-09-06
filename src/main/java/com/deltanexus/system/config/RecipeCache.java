package com.deltanexus.system.config;

import com.deltanexus.system.DeltaNexus;
import com.deltanexus.system.common.NbtMatcher;
import com.deltanexus.system.common.WorkbenchRegistry;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 配方双缓存 + 热加载引擎（阶段 1，工作台动态化后按配方目录组织）。
 *
 * <p>内部维护：</p>
 * <ul>
 *   <li>工作缓存：{@code Map<String recipeId, Recipe>}，游戏内逻辑只读此缓存；</li>
 *   <li>原始备份：磁盘 JSON 文件（config/deltanexus/recipes/），{@code /dn export} 导出到 backup/。</li>
 * </ul>
 *
 * <p>{@link #reload()} 从磁盘全量重扫（所有子目录均为一个工作台的配方目录）；
 * {@link #saveSingleRecipe(Recipe)} 先更新内存，再通过 {@link JsonConfigWriter} 异步落盘（防反跳）。</p>
 */
public class RecipeCache {

    private static RecipeCache INSTANCE;

    private final Path recipesDir;
    private final Map<String, Recipe> working = new ConcurrentHashMap<>();

    private RecipeCache(Path recipesDir) {
        this.recipesDir = recipesDir;
    }

    public static synchronized RecipeCache get() {
        if (INSTANCE == null) {
            INSTANCE = new RecipeCache(FMLPaths.CONFIGDIR.get().resolve("deltanexus/recipes"));
        }
        return INSTANCE;
    }

    public Path recipesDir() {
        return recipesDir;
    }

    /** 清空缓存并重新扫描磁盘上所有子目录（每个子目录 = 一个工作台的配方目录）。 */
    public synchronized void reload() {
        working.clear();
        if (!Files.isDirectory(recipesDir)) {
            DeltaNexus.LOGGER.info("[DN] 配方目录不存在: {}", recipesDir);
            return;
        }
        int loaded = 0;
        try (Stream<Path> dirs = Files.list(recipesDir)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                loaded += loadCategory(dir);
            }
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] 扫描配方目录失败: {}", e.getMessage());
        }
        DeltaNexus.LOGGER.info("[DN] 配方热加载完成，共 {} 条", loaded);
    }

    private int loadCategory(Path dir) {
        String dirName = dir.getFileName().toString();
        int loaded = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                JsonObject obj = JsonConfigWriter.readJson(file);
                if (obj == null) {
                    continue;
                }
                Recipe recipe = Recipe.fromJson(obj);
                if (!recipe.isValid()) {
                    DeltaNexus.LOGGER.warn("[DN] 跳过非法配方文件: {}", file);
                    continue;
                }
                // 以文件目录为准的工作台类型（容错 type 字段）
                recipe.type = dirName;
                working.put(recipe.recipeId, recipe);
                loaded++;
            }
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] 读取分类 {} 失败: {}", dir, e.getMessage());
        }
        return loaded;
    }

    public Recipe get(String recipeId) {
        return recipeId == null ? null : working.get(recipeId);
    }

    /** 指定工作台（id）的配方列表。 */
    public List<Recipe> getByWorkbench(String workbenchId) {
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getById(workbenchId);
        String dir = wb != null ? wb.recipesDir : workbenchId;
        List<Recipe> list = new ArrayList<>();
        for (Recipe r : working.values()) {
            if (dir.equals(r.type)) {
                list.add(r);
            }
        }
        list.sort(Comparator.comparing(r -> r.recipeId));
        return list;
    }

    /** 配方所属工作台 id；无匹配返回 null。 */
    public String workbenchIdOf(String recipesDirName) {
        WorkbenchRegistry.Workbench wb = WorkbenchRegistry.get().getByRecipesDir(recipesDirName);
        return wb != null ? wb.id : recipesDirName;
    }

    public List<Recipe> all() {
        List<Recipe> list = new ArrayList<>(working.values());
        list.sort(Comparator.comparing(r -> r.recipeId));
        return list;
    }

    public int size() {
        return working.size();
    }

    /**
     * 保存单个配方：先更新内存缓存（旧任务不受影响，因任务快照了 cachedDuration），
     * 再异步防反跳落盘。
     */
    public void saveSingleRecipe(Recipe recipe) {
        if (recipe == null || !recipe.isValid()) {
            DeltaNexus.LOGGER.warn("[DN] 拒绝保存非法配方");
            return;
        }
        working.put(recipe.recipeId, recipe);
        Path file = categoryDir(recipe.type).resolve(recipe.recipeId + ".json");
        JsonConfigWriter.saveJsonDebounced(file, recipe.toJson(), ModConfig.saveDebounceMs());
        DeltaNexus.LOGGER.info("[DN] 配方 {} 已更新", recipe.recipeId);
    }

    /** 将当前内存配置导出为备份 JSON 至 config/deltanexus/backup/。 */
    public synchronized void exportBackup() {
        Path backupDir = FMLPaths.CONFIGDIR.get().resolve("deltanexus/backup");
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] 创建备份目录失败: {}", e.getMessage());
            return;
        }
        for (Recipe r : working.values()) {
            Path file = backupDir.resolve(r.recipeId + ".json");
            JsonConfigWriter.writeJsonNow(file, r.toJson());
        }
        DeltaNexus.LOGGER.info("[DN] 已导出 {} 条配方到 {}", working.size(), backupDir);
    }

    private Path categoryDir(String recipesDirName) {
        return recipesDir.resolve(recipesDirName);
    }

    /** 首次启动：为默认工作台「列示」创建示例配方（1 煤炭 → 64 钻石，30s）。 */
    public void writeDefaultsIfMissing() {
        if (Files.exists(recipesDir)) {
            reload();
            return;
        }
        try {
            Files.createDirectories(recipesDir);
        } catch (IOException e) {
            DeltaNexus.LOGGER.error("[DN] 创建配方目录失败: {}", e.getMessage());
            return;
        }
        WorkbenchRegistry.Workbench lieshi = WorkbenchRegistry.get().getById("lieshi");
        if (lieshi != null) {
            // 默认配方显示名 = 工作台显示名（如「列示」），避免 G 键 UI 显示裸 id
            writeDefault(lieshi.recipesDir, "lieshi", lieshi.display, 0, 30,
                    new String[][]{{"minecraft:coal", "1"}},
                    new String[][]{{"minecraft:diamond", "64", ""}});
        }
        reload();
        DeltaNexus.LOGGER.info("[DN] 已生成默认示例配方");
    }

    private void writeDefault(String dirName, String id, String display, int level, long duration,
                              String[][] inputs, String[][] outputs) {
        Recipe r = new Recipe();
        r.type = dirName;
        r.recipeId = id;
        r.displayName = display;
        r.requiredLevel = level;
        r.baseDuration = duration;
        for (String[] in : inputs) {
            Recipe.Ingredient ing = new Recipe.Ingredient();
            ing.item = in[0];
            ing.count = Integer.parseInt(in[1]);
            ing.matchType = NbtMatcher.MatchType.IGNORE;
            r.input.add(ing);
        }
        for (String[] out : outputs) {
            Recipe.Output o = new Recipe.Output();
            o.item = out[0];
            o.count = Integer.parseInt(out[1]);
            o.nbt = out.length > 2 ? out[2] : "";
            r.output.add(o);
        }
        JsonConfigWriter.writeJsonNow(categoryDir(dirName).resolve(id + ".json"), r.toJson());
    }
}
