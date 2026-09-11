package com.deltanexus.system.client.gui;

/**
 * UI 设计规范（UI.html）三列布局（2.0.8Alpha）：
 *
 * <ul>
 *   <li>左列（窄）—— 盔甲 4 格 / 快捷栏 1-4 号格（竖排）/ 副手 1 格</li>
 *   <li>中列（宽）—— 口袋（快捷栏 5-9 号格）/ 背包 3x9 / 安全箱 3x3</li>
 *   <li>右列（宽）—— 容器/仓库视口（仅仓库界面使用）</li>
 * </ul>
 *
 * <p>背包界面 = 左列 + 中列（水平居中）；仓库界面 = 左列 + 中列 + 右列
 * （左侧与中部为玩家背包界面，右侧为容器/仓库 GUI，符合网页布局规范）。</p>
 *
 * <p>纵向节奏：每组 = 标题条({@link #TITLE_H}) + 槽位行，组间隔 {@link #SECTION_GAP}。
 * 坐标为 GUI 逻辑像素（槽位 18px），不依赖任何客户端类，服务端构造菜单同样可用。</p>
 */
public final class PlayerLayout {

    /** 槽位尺寸（原版 18px 槽）。 */
    public static final int SLOT = 18;
    /** 组标题条高度（含上下留白）。 */
    public static final int TITLE_H = 22;
    /** 组与组的垂直间隔。 */
    public static final int SECTION_GAP = 4;
    /** 左列与中列、中列与右列的水平间隔。 */
    public static final int COL_GAP = 16;

    /** 左列/中列内容整体顶部 y（首个槽位行；标题条在其上方 22px）。 */
    public final int baseY;
    /** 左列 x（盔甲/快捷栏列/副手共用）。 */
    public final int leftX;
    public final int armorY;
    public final int hotbarColY;
    public final int offhandY;
    /** 中列 x（口袋/背包/安全箱共用）。 */
    public final int midX;
    public final int pocketY;
    public final int invY;
    public final int safeY;
    /** 右列仓库视口（withWarehouse=false 时为 -1）。 */
    public final int whX;
    public final int whY;
    /** 仓库视口行数（与 WarehouseMenu.WAREHOUSE_ROWS 一致，避免循环依赖硬编码 12）。 */
    public final int whRows;

    private PlayerLayout(int baseY, int leftX, int armorY, int hotbarColY, int offhandY,
                         int midX, int pocketY, int invY, int safeY,
                         int whX, int whY, int whRows) {
        this.baseY = baseY;
        this.leftX = leftX;
        this.armorY = armorY;
        this.hotbarColY = hotbarColY;
        this.offhandY = offhandY;
        this.midX = midX;
        this.pocketY = pocketY;
        this.invY = invY;
        this.safeY = safeY;
        this.whX = whX;
        this.whY = whY;
        this.whRows = whRows;
    }

    /**
     * 计算布局。
     *
     * @param screenW      屏幕 GUI 宽度
     * @param screenH      屏幕 GUI 高度
     * @param withWarehouse true = 仓库界面（三列，仓库靠右）；false = 背包界面（左中两列水平居中）
     */
    public static PlayerLayout compute(int screenW, int screenH, boolean withWarehouse) {
        // 纵向节奏：baseY 起为槽位行，下一组槽位行 = 上一组槽位底部 + 组间隔 + 标题条
        int step = SECTION_GAP + TITLE_H;
        int leftContentH = 4 * SLOT + step + 4 * SLOT + step + SLOT;   // 盔甲 + 快捷栏列 + 副手 = 192
        int midContentH = SLOT + step + 3 * SLOT + step + 3 * SLOT;    // 口袋 + 背包 + 安全箱 = 156
        int whRows = 12;
        int whInfoH = 18;                                              // 仓库底部行信息行
        int whContentH = whRows * SLOT + whInfoH;                      // 234
        int contentH = Math.max(leftContentH, Math.max(midContentH, withWarehouse ? whContentH : 0));
        // 顶部至少留出标题条空间（baseY - TITLE_H >= 4）
        int baseY = Math.max(TITLE_H + 4, (screenH - contentH) / 2);

        int leftX;
        int midX;
        int whX = -1;
        int whY = -1;
        if (withWarehouse) {
            // 三列：玩家区靠左，仓库贴右侧（符合「左中背包、右容器」布局规范）
            leftX = 16;
            midX = leftX + SLOT + COL_GAP;
            whX = Math.max(midX + 9 * SLOT + COL_GAP, screenW - 9 * SLOT - 16);
            whY = baseY;
        } else {
            // 背包界面：左中两列水平居中
            int groupW = SLOT + COL_GAP + 9 * SLOT;
            leftX = Math.max(8, (screenW - groupW) / 2);
            midX = leftX + SLOT + COL_GAP;
        }
        int armorY = baseY;
        int hotbarColY = armorY + 4 * SLOT + step;
        int offhandY = hotbarColY + 4 * SLOT + step;
        int pocketY = baseY;
        int invY = pocketY + SLOT + step;
        int safeY = invY + 3 * SLOT + step;
        return new PlayerLayout(baseY, leftX, armorY, hotbarColY, offhandY,
                midX, pocketY, invY, safeY, whX, whY, whRows);
    }

    /**
     * 通用容器界面布局（2.0.10Alpha）：左列 = 快捷栏 1-4 竖排（容器菜单无盔甲/副手槽，
     * 不凭空造槽位）；中列 = 口袋/背包/安全箱（与背包界面一致）；右列 = 容器槽位
     * （cols 列 x rows 行，列数由原版槽位坐标推断）。
     */
    public static PlayerLayout computeContainer(int screenW, int screenH, int cols, int rows) {
        int step = SECTION_GAP + TITLE_H;
        int leftContentH = 4 * SLOT;                                   // 仅快捷栏 1-4 竖排
        int midContentH = SLOT + step + 3 * SLOT + step + 3 * SLOT;    // 口袋 + 背包 + 安全箱
        int ctnContentH = rows * SLOT + 18;                            // 容器区（+ 底部留白）
        int contentH = Math.max(leftContentH, Math.max(midContentH, ctnContentH));
        int baseY = Math.max(TITLE_H + 4, (screenH - contentH) / 2);

        // 三列：玩家区靠左，容器贴右（与仓库界面一致的方位规范）
        int leftX = 16;
        int midX = leftX + SLOT + COL_GAP;
        int ctnX = Math.max(midX + 9 * SLOT + COL_GAP, screenW - cols * SLOT - 16);
        int ctnY = baseY;

        // 容器界面无盔甲/副手槽：左列快捷栏与口袋行对齐（armorY/hotbarColY 同值避免误用歧义）
        int hotbarColY = baseY;
        int pocketY = baseY;
        int invY = pocketY + SLOT + step;
        int safeY = invY + 3 * SLOT + step;
        return new PlayerLayout(baseY, leftX, baseY, hotbarColY, hotbarColY + 4 * SLOT,
                midX, pocketY, invY, safeY, ctnX, ctnY, rows);
    }
}
