package com.deltanexus.system.network.packet;

import com.deltanexus.system.client.gui.WarehouseScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：仓库轻量同步。
 *
 * <p>仅同步「容量 + 解锁槽位位图 + 等级 + 货币 + 下一级费用/材料」等轻量字段，
 * 仓库物品由 Menu 标准同步机制逐槽位增量传输，打开/升级时只发一次，绝无每 tick 推送。</p>
 *
 * <p>体验优化：下一级费用以物品形式呈现（货币图标 + 材料图标），
 * 服务端一次性统计持有数（0/1000、0/128），客户端零计算。</p>
 */
public class SyncWarehousePacket {

    /** 下一级材料条目（物品 ID + 需要数量 + NBT 匹配模式 + 当前持有）。 */
    public static class Material {
        public final String itemId;
        public final int needed;
        public final int held;
        public final String nbt;
        /** NBT 匹配模式 key（ignore/exact/contains）。 */
        public final String matchType;

        public Material(String itemId, int needed, String nbt, int held, String matchType) {
            this.itemId = itemId;
            this.needed = needed;
            this.held = held;
            this.nbt = nbt;
            this.matchType = matchType;
        }
    }

    public final int capacity;
    public final long[] unlocked;
    public final int warehouseLevel;
    public final int maxLevel;
    public final int currencyCount;
    public final String currencyItem;
    public final boolean isAdmin;
    public final int nextLevel;
    public final int nextCostMoney;
    public final int nextUnlockSlots;
    /** 下一级材料明细（不含货币本身）。 */
    public final List<Material> nextMaterials;
    /** 当前视口起始行（2.0.1 滚动渲染，替代翻页；视口固定 6 行）。 */
    public final int scrollRow;
    /** 仓库总行数（= 容量 / 9）。 */
    public final int totalRows;
    /** 货币类型（item/scoreboard/vault）。 */
    public final String currencyType;
    /** 货币是否可用（vault 未安装时为 false，客户端不显示货币行）。 */
    public final boolean currencyUsable;
    // ---- 安全箱（1.1.0）----
    /** 安全箱当前等级（0 = 配置默认尺寸）。 */
    public final int safeLevel;
    /** 安全箱升级树最大等级。 */
    public final int safeMaxLevel;
    /** 安全箱当前解锁格数（1 ~ 9）。 */
    public final int safeUnlockedSlots;
    /** 安全箱显示宽度（1 ~ 3）。 */
    public final int safeWidth;
    /** 安全箱显示高度（1 ~ 3）。 */
    public final int safeHeight;
    /** 安全箱下一级等级（无则 0）。 */
    public final int safeNextLevel;
    /** 安全箱下一级费用。 */
    public final int safeNextCostMoney;
    /** 安全箱下一级解锁格数。 */
    public final int safeNextUnlockSlots;
    /** 安全箱下一级材料明细。 */
    public final List<Material> safeNextMaterials;

    public SyncWarehousePacket(int capacity, long[] unlocked, int warehouseLevel, int maxLevel,
                               int currencyCount, String currencyItem, boolean isAdmin,
                               int nextLevel, int nextCostMoney, int nextUnlockSlots,
                               List<Material> nextMaterials, int scrollRow, int totalRows,
                               String currencyType, boolean currencyUsable,
                               int safeLevel, int safeMaxLevel, int safeUnlockedSlots,
                               int safeWidth, int safeHeight, int safeNextLevel,
                               int safeNextCostMoney, int safeNextUnlockSlots,
                               List<Material> safeNextMaterials) {
        this.capacity = capacity;
        this.unlocked = unlocked;
        this.warehouseLevel = warehouseLevel;
        this.maxLevel = maxLevel;
        this.currencyCount = currencyCount;
        this.currencyItem = currencyItem;
        this.isAdmin = isAdmin;
        this.nextLevel = nextLevel;
        this.nextCostMoney = nextCostMoney;
        this.nextUnlockSlots = nextUnlockSlots;
        this.nextMaterials = nextMaterials;
        this.scrollRow = scrollRow;
        this.totalRows = totalRows;
        this.currencyType = currencyType;
        this.currencyUsable = currencyUsable;
        this.safeLevel = safeLevel;
        this.safeMaxLevel = safeMaxLevel;
        this.safeUnlockedSlots = safeUnlockedSlots;
        this.safeWidth = safeWidth;
        this.safeHeight = safeHeight;
        this.safeNextLevel = safeNextLevel;
        this.safeNextCostMoney = safeNextCostMoney;
        this.safeNextUnlockSlots = safeNextUnlockSlots;
        this.safeNextMaterials = safeNextMaterials;
    }

    public static void encode(SyncWarehousePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.capacity);
        buf.writeVarInt(msg.unlocked.length);
        for (long l : msg.unlocked) {
            buf.writeLong(l);
        }
        buf.writeVarInt(msg.warehouseLevel);
        buf.writeVarInt(msg.maxLevel);
        buf.writeVarInt(msg.currencyCount);
        buf.writeUtf(msg.currencyItem);
        buf.writeBoolean(msg.isAdmin);
        buf.writeVarInt(msg.nextLevel);
        buf.writeVarInt(msg.nextCostMoney);
        buf.writeVarInt(msg.nextUnlockSlots);
        buf.writeVarInt(msg.nextMaterials.size());
        for (Material m : msg.nextMaterials) {
            buf.writeUtf(m.itemId);
            buf.writeVarInt(m.needed);
            buf.writeUtf(m.nbt);
            buf.writeVarInt(m.held);
            buf.writeUtf(m.matchType);
        }
        buf.writeVarInt(msg.scrollRow);
        buf.writeVarInt(msg.totalRows);
        buf.writeUtf(msg.currencyType);
        buf.writeBoolean(msg.currencyUsable);
        // 安全箱（1.1.0）
        buf.writeVarInt(msg.safeLevel);
        buf.writeVarInt(msg.safeMaxLevel);
        buf.writeVarInt(msg.safeUnlockedSlots);
        buf.writeVarInt(msg.safeWidth);
        buf.writeVarInt(msg.safeHeight);
        buf.writeVarInt(msg.safeNextLevel);
        buf.writeVarInt(msg.safeNextCostMoney);
        buf.writeVarInt(msg.safeNextUnlockSlots);
        buf.writeVarInt(msg.safeNextMaterials.size());
        for (Material m : msg.safeNextMaterials) {
            buf.writeUtf(m.itemId);
            buf.writeVarInt(m.needed);
            buf.writeUtf(m.nbt);
            buf.writeVarInt(m.held);
            buf.writeUtf(m.matchType);
        }
    }

    public static SyncWarehousePacket decode(FriendlyByteBuf buf) {
        int capacity = buf.readVarInt();
        int len = buf.readVarInt();
        long[] unlocked = new long[len];
        for (int i = 0; i < len; i++) {
            unlocked[i] = buf.readLong();
        }
        int warehouseLevel = buf.readVarInt();
        int maxLevel = buf.readVarInt();
        int currencyCount = buf.readVarInt();
        String currencyItem = buf.readUtf();
        boolean isAdmin = buf.readBoolean();
        int nextLevel = buf.readVarInt();
        int nextCostMoney = buf.readVarInt();
        int nextUnlockSlots = buf.readVarInt();
        int matCount = buf.readVarInt();
        List<Material> materials = new ArrayList<>(matCount);
        for (int i = 0; i < matCount; i++) {
            materials.add(new Material(buf.readUtf(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readUtf()));
        }
        int scrollRow = buf.readVarInt();
        int totalRows = buf.readVarInt();
        String currencyType = buf.readUtf();
        boolean currencyUsable = buf.readBoolean();
        // 安全箱（1.1.0）
        int safeLevel = buf.readVarInt();
        int safeMaxLevel = buf.readVarInt();
        int safeUnlockedSlots = buf.readVarInt();
        int safeWidth = buf.readVarInt();
        int safeHeight = buf.readVarInt();
        int safeNextLevel = buf.readVarInt();
        int safeNextCostMoney = buf.readVarInt();
        int safeNextUnlockSlots = buf.readVarInt();
        int safeMatCount = buf.readVarInt();
        List<Material> safeMaterials = new ArrayList<>(safeMatCount);
        for (int i = 0; i < safeMatCount; i++) {
            safeMaterials.add(new Material(buf.readUtf(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readUtf()));
        }
        return new SyncWarehousePacket(capacity, unlocked, warehouseLevel, maxLevel,
                currencyCount, currencyItem, isAdmin, nextLevel, nextCostMoney, nextUnlockSlots,
                materials, scrollRow, totalRows, currencyType, currencyUsable,
                safeLevel, safeMaxLevel, safeUnlockedSlots, safeWidth, safeHeight,
                safeNextLevel, safeNextCostMoney, safeNextUnlockSlots, safeMaterials);
    }

    public static void handle(SyncWarehousePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /**
     * 客户端处理（2.0.7 拆分：专用服务器不加载本类，修复客户端类型引用导致的启动崩溃）。
     */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncWarehousePacket msg) {
            WarehouseScreen.lastSync = msg;
            // 已打开的仓库屏幕实时刷新（升级后等级/解锁/货币立即更新，无需重开）
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof WarehouseScreen s) {
                s.onSync(msg);
            }
            // 特勤处（2.0.2）：升级界面同样订阅仓库同步包实时刷新
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof com.deltanexus.system.client.gui.SpecialOpsScreen s) {
                s.onSync(msg);
            }
        }
    }
}
