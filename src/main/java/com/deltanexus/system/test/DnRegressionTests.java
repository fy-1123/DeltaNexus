package com.deltanexus.system.test;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.capability.CapabilityAttacher;
import com.deltanexus.system.grid.GridClassConfig;
import com.deltanexus.system.server.ManufacturingService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三角联结回归测试（2.0.7Alpha）。
 *
 * <p>仅在 GameTestServer（{@code gradlew runGameTestServer}）中执行，不影响正常服务器/客户端。
 * 注册方式：{@link #register()} 由 {@code DeltaNexus} 构造器调用
 * （{@link GameTestRegistry#register(Class)}，Forge 1.20.1 无自动扫描注解）。
 * 覆盖两个历史缺陷：</p>
 * <ol>
 *   <li><b>物品「类」背景数据链路</b>：类配置运行时覆盖 → isClassed 判定 → bgOf 取色；</li>
 *   <li><b>死亡后安全箱数据清空</b>：死亡备份（内存+磁盘）→ 新实体空数据（解锁位保留，
 *       旧 isEmptyData 会误判「有数据」跳过恢复）→ 重生恢复完整数据。</li>
 * </ol>
 */
public class DnRegressionTests {

    /** 由 {@code DeltaNexus} 构造器调用（普通服务器注册无害，仅 GameTestServer 执行）。 */
    public static void register() {
        GameTestRegistry.register(DnRegressionTests.class);
    }

    /** 类配置数据链路：运行时覆盖 → isClassed → bgOf。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void classColorChain(GameTestHelper helper) {
        // 服务端广播的类配置（SyncGridSizesPacket 载荷等价物）
        Map<String, int[]> classes = new LinkedHashMap<>();
        classes.put("rare", new int[]{52, 110, 190});
        Map<String, String> items = new LinkedHashMap<>();
        items.put("minecraft:diamond", "rare");
        GridClassConfig.applyRuntime(classes, items);

        ItemStack diamond = new ItemStack(Items.DIAMOND);
        if (!GridClassConfig.isClassed(diamond)) {
            helper.fail("minecraft:diamond 应命中类配置 rare");
        }
        int[] bg = GridClassConfig.bgOf(diamond);
        int expectLight = 0xFF000000 | (52 << 16) | (110 << 8) | 190;
        if (bg[1] != expectLight) {
            helper.fail("类背景亮色错误: " + Integer.toHexString(bg[1]) + " != " + Integer.toHexString(expectLight));
        }
        // 未配置类的物品 → 不命中、回退默认灰
        ItemStack stick = new ItemStack(Items.STICK);
        if (GridClassConfig.isClassed(stick)) {
            helper.fail("minecraft:stick 不应命中类配置");
        }
        if (GridClassConfig.bgOf(stick) != GridClassConfig.DEFAULT_BG) {
            helper.fail("未配置类物品应返回 DEFAULT_BG");
        }
        // 清空运行时覆盖后：回退服务端/本地配置（若已配置仍命中，未配置不命中）
        GridClassConfig.applyRuntime(new LinkedHashMap<>(), new LinkedHashMap<>());
        ItemStack netherite = new ItemStack(Items.NETHERITE_INGOT);
        if (GridClassConfig.isClassed(netherite)) {
            helper.fail("minecraft:netherite_ingot 不应命中类配置");
        }
        helper.succeed();
    }

    /**
     * 死亡 → 重生数据恢复链路（安全箱/仓库/等级一体）。
     *
     * <p>关键回归点：新实体（重生的空能力）构造时按 base_slots 预解锁 108 格，
     * 旧 isEmptyData 把「解锁位非空」误判为「有数据」，导致死亡备份恢复永不触发；
     * 本测试在清空数据后保留解锁位，验证恢复仍能正确执行。</p>
     */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void deathBackupRestore(GameTestHelper helper) {
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        IPlayerData data = ManufacturingService.data(player);
        if (data == null) {
            helper.fail("FakePlayer 未挂载玩家数据能力");
            return;
        }
        // 1. 准备数据：安全箱物品+等级、仓库物品+等级
        data.getSafeBoxHandler().setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        data.getSafeBoxHandler().setStackInSlot(1, new ItemStack(Items.EMERALD, 5));
        data.setSafeBoxLevel(2);
        data.setWarehouseLevel(1);
        data.getWarehouseHandler().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 9));
        // 2. 模拟死亡：LivingDeathEvent → 备份（内存 + config/deltanexus/backup/<uuid>.dat）
        DamageSource src = player.damageSources().genericKill();
        MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(player, src));
        // 3. 模拟重生新实体：数据清空，但解锁位保留（构造器/登录补齐，恒非空）
        data.setWarehouseLevel(0);
        data.setSafeBoxLevel(0);
        data.getWarehouseHandler().setStackInSlot(0, ItemStack.EMPTY);
        data.getSafeBoxHandler().setStackInSlot(0, ItemStack.EMPTY);
        data.getSafeBoxHandler().setStackInSlot(1, ItemStack.EMPTY);
        for (Deque<?> q : data.getAllTasks().values()) {
            q.clear();
        }
        // 4. 触发重生恢复：PlayerRespawnEvent
        MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(player, false));
        // 5. 断言完整恢复
        if (data.getSafeBoxLevel() != 2) {
            helper.fail("安全箱等级未恢复: " + data.getSafeBoxLevel());
        }
        ItemStack s0 = data.getSafeBoxHandler().getStackInSlot(0);
        if (!s0.is(Items.DIAMOND) || s0.getCount() != 3) {
            helper.fail("安全箱物品未恢复: " + s0);
        }
        ItemStack s1 = data.getSafeBoxHandler().getStackInSlot(1);
        if (!s1.is(Items.EMERALD) || s1.getCount() != 5) {
            helper.fail("安全箱物品2未恢复: " + s1);
        }
        if (data.getWarehouseLevel() != 1 || !data.getWarehouseHandler().getStackInSlot(0).is(Items.IRON_INGOT)) {
            helper.fail("仓库等级/物品未恢复");
        }
        helper.succeed();
    }
}
