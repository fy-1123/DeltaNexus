package com.deltanexus.system.grid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 装备网格数据（0.5.0Beta）——背包/胸挂「自带存储」的读写门面。
 *
 * <p><b>尺寸与种类来自配置</b>（{@link GearConfig}），物品 NBT 只保存「装了什么」：</p>
 * <pre>
 * { DeltaNexus: { gear: { format_version: 1, entries: [ { cell, item, w, h, rotated } … ] } } }
 * </pre>
 *
 * <p>与 sakura-equipment 的 {@code SakuraStorage} 思路一致：内容存在<b>物品自己的 NBT</b> 里，
 * 因此背包被丢弃、被交易、被管道搬走时内容随物品走，不会出现「容器与内容分离」的失配；
 * 区别是这里存<b>锚点条目</b>（多格占用）而不是平铺槽位，且容量由配置而非物品字段决定。</p>
 *
 * <p><b>配置改小容量时不丢物品</b>：{@link #read} 走 {@link GridNbt} 的容错安置
 * （原位 → 首个空位 → 强制原位 → 退化 1x1），只移动不丢弃，实在放不下才 ERROR 上报。</p>
 */
public final class GearData {

    /** 装备块键（位于 {@link GridMarker#TAG_ROOT} 内）。 */
    public static final String KEY_GEAR = "gear";

    private GearData() {
    }

    /** 是否为被登记为装备的物品（背包/胸挂）。 */
    public static boolean isGear(ItemStack stack) {
        return GearConfig.isGear(stack);
    }

    /** 装备种类（未登记返回 {@code null}）。 */
    @Nullable
    public static GearKind kindOf(ItemStack stack) {
        return GearConfig.kindOf(stack.getItem());
    }

    /** 网格尺寸（未登记返回 {@code null}）。 */
    @Nullable
    public static GridSize sizeOf(ItemStack stack) {
        return GearConfig.sizeOf(stack.getItem());
    }

    /** 装备内容根标签（不存在返回 {@code null}）。 */
    @Nullable
    public static CompoundTag tag(ItemStack stack) {
        CompoundTag ns = GridMarker.namespace(stack);
        return ns != null && ns.contains(KEY_GEAR, 10) ? ns.getCompound(KEY_GEAR) : null;
    }

    /** 读取内容（只读快照；与物品 NBT 解耦，需经 {@link #write} 落回）。非装备返回 null。 */
    @Nullable
    public static GridStore read(ItemStack stack) {
        GridSize size = sizeOf(stack);
        if (size == null) {
            return null;
        }
        CompoundTag gear = tag(stack);
        if (gear == null) {
            return new GridStore(size.w(), size.h());
        }
        GridStore store = GridNbt.read(gear, size.w(), size.h(), null);
        if (store != null) {
            return store;
        }
        // 容错（数据安全）：内容块存在但格式标记缺失/不认识时，绝不能当作空容器返回——
        // 那会让下一次写回把真实内容抹掉。这里补上格式标记按锚点格式再读一次，仍然失败才返回空容器。
        if (gear.contains("entries")) {
            CompoundTag repaired = gear.copy();
            repaired.putInt("format_version", GridNbt.FORMAT_VERSION);
            GridStore rescued = GridNbt.read(repaired, size.w(), size.h(), null);
            if (rescued != null) {
                com.deltanexus.system.DeltaNexus.LOGGER.warn(
                        "[DN] 装备内容缺少格式标记，已按锚点格式容错读取（{} 件）: {}",
                        rescued.entryCount(), stack.getItem());
                return rescued;
            }
        }
        return new GridStore(size.w(), size.h());
    }

    /** 内容指纹（判断是否需要重写 NBT）；非装备返回 0。 */
    public static long contentHash(ItemStack stack) {
        GridStore store = read(stack);
        return store == null ? 0L : store.contentHash();
    }

    /**
     * 写回内容。
     *
     * <p>0.5.0Beta 修复：不再因为「物品当前未登记为装备」而静默丢弃写入——写回只需要
     * 物品 NBT 与内核锚点表（尺寸来自容器本身），与物品是否在 {@code gear.json} 里无关。
     * 旧实现遇到登记表缺失/被改动时直接 return，内容改动就此消失（表现为「东西全没了」）。</p>
     */
    public static void write(ItemStack stack, GridStore store) {
        if (stack == null || stack.isEmpty() || store == null) {
            return;
        }
        CompoundTag gear = gearTagOrCreate(stack);
        CompoundTag grid = GridNbt.write(store);
        // 先清空旧内容，避免容器变小后残留旧条目
        for (String key : gear.getAllKeys().toArray(new String[0])) {
            gear.remove(key);
        }
        for (String key : grid.getAllKeys()) {
            gear.put(key, grid.get(key));
        }
    }

    /** 清空装备内容（管理员重置/取出全部物品后调用）。 */
    public static void clear(ItemStack stack) {
        CompoundTag ns = GridMarker.namespace(stack);
        CompoundTag gear = ns == null ? null : (ns.contains(KEY_GEAR, 10) ? ns.getCompound(KEY_GEAR) : null);
        if (gear != null) {
            gear.remove("format_version");
            gear.remove("entries");
            ns.remove(KEY_GEAR);
            GridMarker.pruneNamespace(stack);
        }
    }

    /** 取得（或创建）命名空间下的 {@code gear} 块。 */
    private static CompoundTag gearTagOrCreate(ItemStack stack) {
        CompoundTag ns = GridMarker.namespaceOrCreate(stack);
        if (!ns.contains(KEY_GEAR, 10)) {
            ns.put(KEY_GEAR, new CompoundTag());
        }
        return ns.getCompound(KEY_GEAR);
    }
}