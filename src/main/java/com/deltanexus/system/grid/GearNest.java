package com.deltanexus.system.grid;

import net.minecraft.world.item.ItemStack;

/**
 * 装备嵌套规则（0.5.0Beta 嵌套补丁）——「胸挂/背包能不能塞进另一件胸挂/背包」的唯一判定处。
 *
 * <p>规则（用户需求）：</p>
 * <ul>
 *   <li>装备物品<b>自身内部必须为空</b>，才允许被放进另一件装备；</li>
 *   <li>最多嵌套 {@link #MAX_DEPTH} 层（含最外层装备本身）。</li>
 * </ul>
 *
 * <p>深度定义：已装备 / 独立放置的装备为第 1 层；它内部直接装的装备为第 2 层，以此类推。
 * 因此把物品放进「自身深度为 {@code containerDepth}」的装备时，新装备深度为
 * {@code containerDepth + 1}，须不超过 {@link #MAX_DEPTH}。</p>
 *
 * <p><b>「空」是递归的（0.5.0Beta 修复）</b>：装备里只装着<b>同样空的</b>装备时，它也算空。
 * 旧的判定只看「条目数是否为 0」，于是「装着一个空背包的背包」被判成有东西、再也放不进
 * 别的装备——而它其实没有任何真实物品，取出来也不会卡住任何人。现在递归到叶子：
 * 一件装备算空，当且仅当它里面的每一件都不是装备，或本身也是空的（层级上限内）。</p>
 *
 * <p>本规则<b>只约束装备与装备之间的嵌套</b>：装备放进仓库 / 安全箱 / 箱子等非装备容器不受限。</p>
 */
public final class GearNest {

    /** 最大嵌套层数（含最外层）。 */
    public static final int MAX_DEPTH = 7;

    private GearNest() {
    }

    /** 装备内容是否为「空」（未登记为装备一律视为空；内部只装空装备同样算空）。 */
    public static boolean isEmpty(ItemStack gear) {
        return isEmpty(gear, 0);
    }

    /**
     * 递归判定「空」。
     *
     * @param depth 已经下沉的层数（防御性上限：超过 {@link #MAX_DEPTH} 视为非空，
     *              既避免病态深 NBT 造成无界递归，也与「最多 7 层」的口径一致）
     */
    private static boolean isEmpty(ItemStack gear, int depth) {
        if (gear == null || gear.isEmpty() || !GearConfig.isGear(gear)) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        GridStore store = GearData.read(gear);
        if (store == null) {
            return true;
        }
        for (GridEntry entry : store.entries().values()) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            // 里面是一件装备 → 递归看它是否也空；是普通物品 → 立刻判为非空
            if (!GearConfig.isGear(entry.stack()) || !isEmpty(entry.stack(), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 能否把 {@code stack} 放进「自身深度为 {@code containerDepth}」的装备容器。
     *
     * <p>非装备一律允许；是装备则要求它（递归地）内部为空，且嵌套后深度不超过
     * {@link #MAX_DEPTH}。</p>
     */
    public static boolean allows(ItemStack stack, int containerDepth) {
        if (!GearConfig.isGear(stack)) {
            return true;
        }
        if (!isEmpty(stack)) {
            return false;
        }
        return containerDepth + 1 <= MAX_DEPTH;
    }
}
