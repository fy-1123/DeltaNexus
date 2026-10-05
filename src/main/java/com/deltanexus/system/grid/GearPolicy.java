package com.deltanexus.system.grid;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * 格子背包装备策略（0.5.0Beta）——「原版背包哪些格被装备取代」的<b>唯一判定处</b>。
 *
 * <p>塔克夫式模型：玩家不再有一个万能的 27 格主背包，而是靠<b>胸挂 + 背包</b>提供存储。
 * 因此原版主背包的大部分格子被屏蔽，只保留：</p>
 * <ul>
 *   <li>快捷栏 9 格（{@code containerSlot 0..8}）——手感必需；</li>
 *   <li>主背包前 5 格（{@code 9..13}）——作为「口袋」，即 sakura 的 pockets；</li>
 *   <li>盔甲 4 格与副手（{@code 36..40}）<b>不屏蔽</b>——属原版装备语义，屏蔽会破坏盾牌/图腾等玩法。</li>
 * </ul>
 *
 * <p>被屏蔽的区间为 {@code containerSlot 14..35}（共 22 格）。</p>
 *
 * <p><b>创造/旁观者模式一律不受限</b>（与 sakura 的 {@code restricted()} 一致）：管理员在创造模式里
 * 仍可自由摆放物品，否则调试与建筑会被自己的模组挡住。</p>
 *
 * <p>本类只回答「能不能用」，不做任何物品搬运：搬运见
 * {@code com.deltanexus.system.server.GearService}。</p>
 */
public final class GearPolicy {

    /** 被保留的「口袋」槽位数（快捷栏 9 + 主背包前 5）。 */
    public static final int KEEP_SLOTS = 14;
    /** 主背包区终点（不含），原版主背包为 9..35。 */
    public static final int MAIN_END = 36;

    private GearPolicy() {
    }

    /** 是否受装备策略限制（创造/旁观者不受限）。 */
    public static boolean restricted(Player player) {
        return player != null && !player.isCreative() && !player.isSpectator();
    }

    /** 玩家背包容器槽位是否被装备取代。 */
    public static boolean blockedContainerSlot(int containerSlot) {
        return containerSlot >= KEEP_SLOTS && containerSlot < MAIN_END;
    }

    /** 菜单槽位是否被装备取代（仅对玩家背包容器生效）。 */
    public static boolean blocked(Player player, int containerSlot) {
        return restricted(player) && blockedContainerSlot(containerSlot);
    }

    /** 该菜单槽位是否被屏蔽。 */
    public static boolean isBlockedSlot(Slot slot) {
        return slot != null
                && slot.container instanceof Inventory inventory
                && blocked(inventory.player, slot.getContainerSlot());
    }
}