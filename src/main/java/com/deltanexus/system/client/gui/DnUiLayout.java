package com.deltanexus.system.client.gui;

/**
 * 类塔克夫界面几何（0.5.0Beta）：照抄 {@code sakura-inventory-ui} 的双面板几何。
 *
 * <p>面板与排列完全对齐 sakura 的「战术面板」布局：</p>
 * <ul>
 *   <li><b>左面板（128 宽）</b>：装备标题 + 盔甲（头盔 / 胸甲）+ 胸挂 / 背包本体槽
 *       + 生命 / 饥饿 / 护甲读数；</li>
 *   <li><b>主面板（188 宽）</b>：口袋 5 → 胸挂网格 → 背包网格 → 安全箱网格（可滚动），
 *       底部固定快捷栏 9；</li>
 *   <li><b>容器面板（188 宽）</b>：容器 / 仓库网格（{@link DnInventoryScreen} 无此列）。</li>
 * </ul>
 *
 * <p>槽位几何照抄 sakura：格框 18px（{@link #SLOT_SIZE}）、步距 17px（{@link #SLOT_PITCH}），
 * 相邻格框叠边 1px。本模组的网格引擎（NxN 跨格物品、类色墙体、足迹判定）
 * 一并迁移到 17px 步距，与面板网格严格对齐。</p>
 *
 * <p>纵向位置一律由 {@link DnInventoryScreen} 顺序累加得出（分组多高就往下推多少），
 * 不使用固定坐标，避免分组高度变化时互相重叠。</p>
 */
public final class DnUiLayout {

    /** 屏幕四周最小留白。 */
    public static final int OUTER_MARGIN = 6;

    /** 槽位边长（照抄 sakura：格框 18px）。 */
    public static final int SLOT_SIZE = 18;
    /** 槽位步距（照抄 sakura：17px，相邻格框叠边 1px）。 */
    public static final int SLOT_PITCH = 17;

    /** 网格引擎 / 主题使用的槽位边长（= {@link #SLOT_SIZE}）。 */
    public static final int CELL = SLOT_SIZE;
    /** 网格引擎 / 主题使用的步距（= {@link #SLOT_PITCH}）。 */
    public static final int PITCH = SLOT_PITCH;

    /** 左面板宽（照抄 sakura TACTICAL_LEFT_W）。 */
    public static final int LEFT_PANEL_W = 128;
    /** 主面板宽（照抄 sakura TACTICAL_RIGHT_W）。 */
    public static final int MAIN_PANEL_W = 188;
    /** 容器面板宽。 */
    public static final int CONTAINER_PANEL_W = 188;
    /** 面板间距（照抄 sakura PANEL_GAP）。 */
    public static final int PANEL_GAP = 12;
    /** 主面板固定高（照抄 sakura MAIN_PANEL_H）。 */
    public static final int MAIN_PANEL_H = 300;

    /** 面板内边距。 */
    public static final int PANEL_PAD = 8;
    /** 内容相对面板左的内缩（照抄 sakura getMainSlotX：+6）。 */
    public static final int CONTENT_INSET = 6;

    /** 分组标签条高度（标签底边紧贴格子顶边）。 */
    public static final int LABEL_H = 18;
    /** 分组之间的间隔。 */
    public static final int GROUP_GAP = 12;

    // ---- 左面板：装备标题 ----
    /** 装备标题 x（相对面板左）。 */
    public static final int GEAR_TITLE_X = 6;
    /** 装备标题 y（相对面板顶）。 */
    public static final int GEAR_TITLE_Y = 8;
    /** 装备标题最小宽。 */
    public static final int GEAR_TITLE_W = 62;

    // ---- 左面板：盔甲列（头盔 / 胸甲）----
    /** 盔甲列 x（相对面板左）。 */
    public static final int ARMOR_X = 10;
    /** 盔甲首行 y（相对面板顶）。 */
    public static final int ARMOR_TOP_Y = 116;
    /** 盔甲行步距（照抄 sakura：32px）。 */
    public static final int ARMOR_STEP = 32;

    // ---- 左面板：装备（胸挂 / 背包）列 ----
    /** 装备列 x（相对面板左）。 */
    public static final int GEAR_X = 104;
    /** 装备首行 y（相对面板顶）。 */
    public static final int GEAR_TOP_Y = 116;
    /** 装备行步距（照抄 sakura：32px）。 */
    public static final int GEAR_STEP = 32;

    /** 左面板：状态读数 y（相对面板顶）。 */
    public static final int STATUS_Y = 222;

    // ---- 主面板：口袋 ----
    /** 口袋标签 y（相对面板顶）。 */
    public static final int POCKET_LABEL_Y = 8;
    /** 口袋格子 y（相对面板顶）。 */
    public static final int POCKET_SLOTS_Y = 22;

    // ---- 主面板：胸挂 ----
    /** 胸挂标签 y（相对面板顶）。 */
    public static final int RIG_LABEL_Y = 50;
    /** 胸挂格子 y（相对面板顶）。 */
    public static final int RIG_SLOTS_Y = 64;

    // ---- 主面板：快捷栏（钉在面板底）----
    /** 快捷栏标签距面板底的距离。 */
    public static final int HOTBAR_LABEL_FROM_BOTTOM = 34;
    /** 快捷栏格子距面板底的距离。 */
    public static final int HOTBAR_SLOTS_FROM_BOTTOM = 20;

    /** 滚动条宽（照抄 sakura SCROLLBAR_WIDTH）。 */
    public static final int SCROLLBAR_WIDTH = 4;

    /** 左面板槽数：盔甲（头盔 / 胸甲）。 */
    public static final int ARMOR_COUNT = 2;
    /** 主面板槽数：口袋。 */
    public static final int POCKET_COUNT = 5;
    /** 主面板槽数：快捷栏。 */
    public static final int HOTBAR_COUNT = 9;

    private DnUiLayout() {
    }

    /** 一组「标签 + n 行格子」的内容高度。 */
    public static int groupHeight(int rows) {
        return LABEL_H + rows * SLOT_PITCH;
    }
}
