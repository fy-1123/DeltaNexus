package com.deltanexus.system.client;

import com.deltanexus.system.DeltaNexus;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 按键绑定（键名全部汉化，见语言文件 key.dn.*）。
 *
 * <ul>
 *   <li>B —— 打开仓库（key.dn.open_warehouse）</li>
 *   <li>G —— 打开工作台总览（key.dn.open_workbench）</li>
 *   <li>V —— 打开特勤处（key.dn.open_special，2.0.3Alpha）</li>
 * </ul>
 *
 * <p>2.0.8Alpha：移除安全箱快捷键（N）——安全箱不再提供单独界面，
 * 仅保留背包界面与仓库界面的内嵌面板（UI 重构）。</p>
 */
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.deltanexus";

    /** 打开仓库。 */
    public static final KeyMapping OPEN_WAREHOUSE = new KeyMapping(
            "key.dn.open_warehouse", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** 打开工作台总览。 */
    public static final KeyMapping OPEN_WORKBENCH = new KeyMapping(
            "key.dn.open_workbench", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** 打开特勤处。 */
    public static final KeyMapping OPEN_SPECIAL = new KeyMapping(
            "key.dn.open_special", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** 旋转光标物品（R 键，2.0.0Alpha 格式背包）。 */
    public static final KeyMapping ROTATE_ITEM = new KeyMapping(
            "key.dn.rotate_item", GLFW.GLFW_KEY_R, CATEGORY);

    /** 打开交易行（0.2.0Beta：默认不绑定）。 */
    public static final KeyMapping OPEN_TRADE = new KeyMapping(
            "key.dn.open_trade", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    private KeyBindings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_WAREHOUSE);
        event.register(OPEN_WORKBENCH);
        event.register(OPEN_SPECIAL);
        event.register(ROTATE_ITEM);
        event.register(OPEN_TRADE);
    }

    /**
     * 按键冲突检测：扫描 Options 中已注册的全部键位。
     * 需在客户端启动后（FMLClientSetupEvent）调用。
     */
    public static void checkConflicts() {
        try {
            Minecraft mc = Minecraft.getInstance();
            for (KeyMapping ours : new KeyMapping[]{OPEN_WAREHOUSE, OPEN_WORKBENCH,
                    OPEN_SPECIAL, ROTATE_ITEM, OPEN_TRADE}) {
                InputConstants.Key ourKey = ours.getKey();
                for (KeyMapping mapping : mc.options.keyMappings) {
                    if (mapping != ours && mapping.getKey().equals(ourKey)) {
                        DeltaNexus.LOGGER.warn(
                                "[DN] 按键 {} 与 '{}' 冲突，请在 选项 -> 控制 -> 三角联结 中修改",
                                ours.getName(), mapping.getName());
                    }
                }
            }
        } catch (Exception e) {
            DeltaNexus.LOGGER.debug("[DN] 按键冲突检测跳过: {}", e.getMessage());
        }
    }
}
