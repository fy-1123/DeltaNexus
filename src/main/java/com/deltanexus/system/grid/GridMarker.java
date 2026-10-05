package com.deltanexus.system.grid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 网格 NBT 约定（0.5.0Beta 统一格式）——格子背包写进物品 NBT 的**唯一**权威定义。
 *
 * <p>统一为<b>单命名空间复合标签</b>，不再散落一堆点号键：</p>
 * <pre>
 * { DeltaNexus: { rot: 1b, gear: { … } } }
 *   rot  —— 该物品当前的网格姿态（仅玩家背包这类「无侧表」容器需要；仓库/安全箱/装备自带网格
 *           把姿态存在容器存档里，不污染物品）
 *   gear —— 装备物品（背包/胸挂）自带的网格存储，见 {@code GearStorage}
 * </pre>
 *
 * <p>历史键（{@code deltanexus.grid.rotated}、{@code deltanexus.is_rotated}、
 * {@code deltanexus.is_slave}、{@code deltanexus.master_slot}）<b>只读兼容 + 剥离</b>，绝不新写。</p>
 *
 * <p>{@link #stripped(ItemStack)} 供对外比较使用（交易匹配 / 管道过滤 / 等价判断）——
 * 网格私有标记不应影响「这两个物品是不是同一种东西」的判定。</p>
 */
public final class GridMarker {

    /** 统一命名空间根键。 */
    public static final String TAG_ROOT = "DeltaNexus";
    /** 网格姿态键（位于 {@link #TAG_ROOT} 内）。 */
    public static final String KEY_ROT = "rot";

    private static final String LEGACY_ROT_A = "deltanexus.grid.rotated";
    private static final String LEGACY_ROT_B = "deltanexus.is_rotated";
    private static final String LEGACY_SLAVE = "deltanexus.is_slave";
    private static final String LEGACY_MASTER = "deltanexus.master_slot";

    private GridMarker() {
    }

    /** 命名空间复合标签（不存在时不创建）。 */
    public static CompoundTag namespace(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return null;
        }
        CompoundTag root = stack.getTag();
        return root.contains(TAG_ROOT, 10) ? root.getCompound(TAG_ROOT) : null;
    }

    /** 是否处于旋转姿态（新键优先，历史键兼容）。 */
    public static boolean isRotated(ItemStack stack) {
        CompoundTag ns = namespace(stack);
        if (ns != null && ns.getBoolean(KEY_ROT)) {
            return true;
        }
        if (stack.isEmpty() || !stack.hasTag()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag.getBoolean(LEGACY_ROT_A) || tag.getBoolean(LEGACY_ROT_B);
    }

    /** 写入/清除旋转姿态（只写新键，并顺手清掉历史键，保证只有一个真相）。 */
    public static void setRotated(ItemStack stack, boolean rotated) {
        if (stack.isEmpty()) {
            return;
        }
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            tag.remove(LEGACY_ROT_A);
            tag.remove(LEGACY_ROT_B);
        }
        if (rotated) {
            namespaceOrCreate(stack).putBoolean(KEY_ROT, true);
        } else {
            CompoundTag ns = namespace(stack);
            if (ns != null) {
                ns.remove(KEY_ROT);
                pruneNamespace(stack);
            }
        }
    }

    /** 命名空间复合标签（不存在则创建）。 */
    public static CompoundTag namespaceOrCreate(ItemStack stack) {
        CompoundTag root = stack.getOrCreateTag();
        if (!root.contains(TAG_ROOT, 10)) {
            root.put(TAG_ROOT, new CompoundTag());
        }
        return root.getCompound(TAG_ROOT);
    }

    /** 命名空间复合标签为空时移除，避免给物品留下无意义的空标签。 */
    public static void pruneNamespace(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return;
        }
        CompoundTag root = stack.getTag();
        if (root.contains(TAG_ROOT, 10) && root.getCompound(TAG_ROOT).isEmpty()) {
            root.remove(TAG_ROOT);
        }
        if (root.isEmpty()) {
            stack.setTag(null);
        }
    }

    /** 是否为旧版占位物（历史派生态，一律视为残留并清除）。 */
    public static boolean isLegacyPlaceholder(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(LEGACY_SLAVE);
    }

    /** 剥离网格私有键的副本（对外比较用）；不含网格键时同样返回拷贝，绝不修改入参。 */
    public static ItemStack stripped(ItemStack stack) {
        ItemStack copy = stack.copy();
        strip(copy.getTag());
        return copy;
    }

    /** 就地剥离网格私有键（含历史键）。{@code tag} 可为 null。
     *
     *  <p>只剥「姿态」这类纯网格标记：装备自带网格的 {@code gear} 内容属于物品真实数据，
     *  一个装满的背包与空背包不应被判定为同一种物品，因此不剥离。</p> */
    public static void strip(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        tag.remove(LEGACY_ROT_A);
        tag.remove(LEGACY_ROT_B);
        tag.remove(LEGACY_SLAVE);
        tag.remove(LEGACY_MASTER);
        if (tag.contains(TAG_ROOT, 10)) {
            CompoundTag ns = tag.getCompound(TAG_ROOT);
            ns.remove(KEY_ROT);
            if (ns.isEmpty()) {
                tag.remove(TAG_ROOT);
            }
        }
    }
}