package com.deltanexus.system.client.gui;

/**
 * 仓库界面布局（0.5.0Beta）：<b>照抄 {@code sakura-inventory-ui} 的双面板几何</b>。
 *
 * <p>与背包/容器界面（{@link DnInventoryScreen}）共用同一套 sakura 几何：</p>
 * <ul>
 *   <li><b>左面板</b>（{@link #EQUIP_W} = 128 宽）：装备（胸挂 / 背包本体槽）+ 盔甲（仅头盔 / 胸甲，
 *       照抄 sakura 只留两格，护腿 / 靴子 / 副手隐藏）；</li>
 *   <li><b>主面板</b>（{@link #MAIN_W} = 188 宽）：口袋 5 → 安全箱（可滚动），底部钉死快捷栏 9；</li>
 *   <li><b>容器面板</b>（{@link #RIGHT_W} = 188 宽）：仓库视口（{@link #whRows} 行 x 9 列）。</li>
 * </ul>
 *
 * <p>槽位几何照抄 sakura：格框 18px（{@link #SLOT}）、步距 17px（{@link #PITCH}），相邻格框叠边 1px。
 * 面板高固定 300（{@link #MIN_PANEL_H}），左/主/容器三列顶部对齐。</p>
 *
 * <p>坐标为 GUI 逻辑像素，<b>不依赖任何客户端类</b>——服务端构造菜单时同样可用
 * （此时坐标仅占位，客户端以自己的窗口尺寸重算）。</p>
 */
public final class PlayerLayout {

    /** 槽位尺寸（照抄 sakura：格框 18px）。 */
    public static final int SLOT = DnUiLayout.SLOT_SIZE;
    /** 槽位步距（照抄 sakura：17px）。 */
    public static final int PITCH = DnUiLayout.SLOT_PITCH;
    /** 屏幕四周最小留白。 */
    public static final int OUTER_MARGIN = 6;
    /** 面板内边距。 */
    public static final int PANEL_PAD = 8;
    /** 分组标签条高度。 */
    public static final int LABEL_H = 18;
    /** 分组之间的间隔。 */
    public static final int GROUP_GAP = 12;

    /** 左面板宽（照抄 sakura TACTICAL_LEFT_W）。 */
    public static final int EQUIP_W = DnUiLayout.LEFT_PANEL_W;
    /** 主面板宽（照抄 sakura TACTICAL_RIGHT_W）。 */
    public static final int MAIN_W = DnUiLayout.MAIN_PANEL_W;
    /** 容器（仓库）面板宽。 */
    public static final int RIGHT_W = DnUiLayout.CONTAINER_PANEL_W;
    /** 面板间距（照抄 sakura PANEL_GAP）。 */
    public static final int COL_GAP = DnUiLayout.PANEL_GAP;
    /** 面板固定高（照抄 sakura MAIN_PANEL_H）。 */
    public static final int MIN_PANEL_H = DnUiLayout.MAIN_PANEL_H;

    /** 主面板内容内缩（照抄 sakura getMainSlotX：+6）。 */
    public static final int CONTENT_INSET = 6;
    /** 左面板盔甲列 x（相对面板左）。 */
    public static final int ARMOR_X = 10;
    /** 左面板装备（胸挂 / 背包本体）列 x（相对面板左）。 */
    public static final int GEAR_X = 104;
    /** 左面板盔甲 / 装备首行 y（相对面板顶）。 */
    public static final int GEAR_TOP_Y = 116;
    /** 左面板盔甲 / 装备行步距。 */
    public static final int GEAR_STEP = 32;
    /** 屏外坐标（隐藏槽位用）。 */
    public static final int OFFSCREEN = -1000;

    /** 面板顶 y（左/主/容器三列共用）。 */
    public final int topY;
    /** 面板高度。 */
    public final int panelH;
    /** 三列面板左边界（画外壳用）。 */
    public final int leftPanelX;
    public final int midPanelX;
    public final int whPanelX;

    // ---- 左面板 ----
    /** 盔甲列槽位 x。 */
    public final int leftX;
    /** 装备（胸挂 / 背包本体）列槽位 x。 */
    public final int gearX;
    /** 装备标题 y。 */
    public final int gearLabelY;
    /** 装备槽 y（胸挂 / 背包本体）。 */
    public final int gearRigY;
    public final int gearBagY;
    /** 盔甲标题 y（照抄 sakura 无独立盔甲标签，保留占位）。 */
    public final int armorLabelY;
    /** 盔甲首行 y。 */
    public final int armorY;
    /** 快捷栏标签 y / 槽位 y / 首列 x（主面板底）。 */
    public final int hotbarLabelY;
    public final int hotbarY;
    public final int hotbarX;
    /** 副手（照抄 sakura 不显示，坐标置屏外）。 */
    public final int offhandLabelY;
    public final int offhandY;

    // ---- 主面板 ----
    /** 主面板首列槽位 x。 */
    public final int midX;
    public final int pocketLabelY;
    public final int pocketY;
    public final int safeLabelY;
    public final int safeY;

    // ---- 容器面板（仓库）----
    public final int whLabelY;
    public final int whX;
    public final int whY;
    public final int whRows;

    private PlayerLayout(int topY, int panelH, int leftPanelX, int midPanelX, int whPanelX,
                         int leftX, int gearX, int gearLabelY, int gearRigY, int gearBagY,
                         int armorLabelY, int armorY,
                         int hotbarLabelY, int hotbarY, int hotbarX,
                         int offhandLabelY, int offhandY,
                         int midX, int pocketLabelY, int pocketY, int safeLabelY, int safeY,
                         int whLabelY, int whX, int whY, int whRows) {
        this.topY = topY;
        this.panelH = panelH;
        this.leftPanelX = leftPanelX;
        this.midPanelX = midPanelX;
        this.whPanelX = whPanelX;
        this.leftX = leftX;
        this.gearX = gearX;
        this.gearLabelY = gearLabelY;
        this.gearRigY = gearRigY;
        this.gearBagY = gearBagY;
        this.armorLabelY = armorLabelY;
        this.armorY = armorY;
        this.hotbarLabelY = hotbarLabelY;
        this.hotbarY = hotbarY;
        this.hotbarX = hotbarX;
        this.offhandLabelY = offhandLabelY;
        this.offhandY = offhandY;
        this.midX = midX;
        this.pocketLabelY = pocketLabelY;
        this.pocketY = pocketY;
        this.safeLabelY = safeLabelY;
        this.safeY = safeY;
        this.whLabelY = whLabelY;
        this.whX = whX;
        this.whY = whY;
        this.whRows = whRows;
    }

    /** 组内容高度（标签 + n 行槽位）。 */
    public static int groupHeight(int rows) {
        return LABEL_H + rows * PITCH;
    }

    /**
     * 计算布局（sakura 几何：左 128 / 主 188 / 容器 188，面板高固定 300）。
     *
     * @param screenW  屏幕 GUI 宽度
     * @param screenH  屏幕 GUI 高度
     * @param safeRows 安全箱行数（1~3；几何固定，仅作占位兼容）
     * @param whRows   仓库视口行数（几何固定，仅作占位兼容）
     */
    public static PlayerLayout compute(int screenW, int screenH, int safeRows, int whRows) {
        int totalW = EQUIP_W + COL_GAP + MAIN_W + COL_GAP + RIGHT_W;
        int panelH = MIN_PANEL_H;
        int leftPanelX = Math.max(OUTER_MARGIN, (screenW - totalW) / 2);
        int midPanelX = leftPanelX + EQUIP_W + COL_GAP;
        int whPanelX = midPanelX + MAIN_W + COL_GAP;
        int topY = Math.max(OUTER_MARGIN, (screenH - panelH) / 2);

        // 左面板：装备（胸挂 / 背包本体）+ 盔甲（仅头盔 / 胸甲）。
        // +2 的视觉修正：格子以装备图标为中心（与 DnInventoryScreen 一致）。
        int leftX = leftPanelX + ARMOR_X + 2;
        int gearX = leftPanelX + GEAR_X + 2;
        int gearLabelY = topY + 8;
        int gearRigY = topY + GEAR_TOP_Y + 2;
        int gearBagY = topY + GEAR_TOP_Y + 2 + GEAR_STEP;
        int armorLabelY = topY + 8;
        int armorY = topY + GEAR_TOP_Y;

        // 主面板：口袋 → 安全箱；快捷栏钉在面板底
        int midX = midPanelX + CONTENT_INSET;
        int pocketLabelY = topY + 8;
        int pocketY = topY + 22;
        int safeLabelY = topY + 50;
        int safeY = topY + 64;
        int hotbarLabelY = topY + panelH - 34;
        int hotbarY = topY + panelH - 20;
        int hotbarX = midPanelX + CONTENT_INSET;

        // 容器面板：仓库视口（顶部标题下起排）
        int whLabelY = topY + 8;
        int whX = whPanelX + CONTENT_INSET;
        int whY = topY + 26;

        return new PlayerLayout(topY, panelH, leftPanelX, midPanelX, whPanelX,
                leftX, gearX, gearLabelY, gearRigY, gearBagY, armorLabelY, armorY,
                hotbarLabelY, hotbarY, hotbarX,
                OFFSCREEN, OFFSCREEN,
                midX, pocketLabelY, pocketY, safeLabelY, safeY,
                whLabelY, whX, whY, Math.max(1, whRows));
    }

    /** 默认安全箱 3 行 / 仓库 12 行的布局（服务端占位坐标用）。 */
    public static PlayerLayout compute(int screenW, int screenH) {
        return compute(screenW, screenH, 3, 12);
    }
}
