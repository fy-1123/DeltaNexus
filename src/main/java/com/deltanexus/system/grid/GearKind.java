package com.deltanexus.system.grid;

/**
 * 装备种类（0.5.0Beta）：格子背包体系里的两类「可装备容器」。
 *
 * <p>与 sakura-equipment 的 {@code RIG/BACKPACK} 对齐：</p>
 * <ul>
 *   <li>{@link #RIG} 胸挂——小格数、贴身、<b>允许在受限状态下继续提供存储</b>；</li>
 *   <li>{@link #BACKPACK} 背包——大格数、容量随档位增长。</li>
 * </ul>
 *
 * <p>尺寸不写死在物品上，而是记在物品 NBT（见 {@link GearData}）——与本模组
 * 「单件通用物品 + 配置/指令决定参数」的整体风格一致（仓库行数、安全箱尺寸皆如此），
 * 避免为每个档位注册一个物品与一套模型。</p>
 */
public enum GearKind {

    /** 胸挂。 */
    RIG("rig", new GridSize(4, 3)),
    /** 背包。 */
    BACKPACK("backpack", new GridSize(6, 4));

    private final String id;
    private final GridSize defaultSize;

    GearKind(String id, GridSize defaultSize) {
        this.id = id;
        this.defaultSize = defaultSize;
    }

    /** 配置/指令/语言键用的短名（小写下划线）。 */
    public String id() {
        return id;
    }

    /** 未指定尺寸时的默认网格。 */
    public GridSize defaultSize() {
        return defaultSize;
    }

    /** 尺寸上限（防止管理员写出巨大网格导致渲染/性能问题）。 */
    public GridSize maxSize() {
        return new GridSize(9, 6);
    }

    /** 语言键（{@code item.deltanexus.rig} / {@code item.deltanexus.backpack}）。 */
    public String translationKey() {
        return "item.deltanexus." + id;
    }

    /** 按 id 解析（null = 未匹配）。 */
    public static GearKind byId(String id) {
        if (id == null) {
            return null;
        }
        for (GearKind kind : values()) {
            if (kind.id.equalsIgnoreCase(id.trim())) {
                return kind;
            }
        }
        return null;
    }
}