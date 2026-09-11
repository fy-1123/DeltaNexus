package com.deltanexus.system.network.packet;

import com.deltanexus.system.grid.GridConfig;
import com.deltanexus.system.grid.ItemSizeConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：格式背包配置同步（2.0.2Alpha / 2.0.3Alpha）。
 *
 * <p>携带全部自定义物品尺寸、快捷栏规则，以及物品「类」配置（类颜色 + 物品归属），
 * 客户端以运行时覆盖应用（不写客户端配置文件），保证服务端指令/Web 修改后
 * 客户端渲染与服务端求解一致。玩家登录时发送一次，配置修改后向全体在线玩家广播。</p>
 */
public class SyncGridSizesPacket {

    /** item_id -> {w, h}。 */
    public final Map<String, int[]> sizes;
    /** 快捷栏规则（可能为 null，客户端回退本地配置）。 */
    public final List<String> hotbarRules;
    /** 类名 -> RGB（2.0.3Alpha）。 */
    public final Map<String, int[]> classes;
    /** 物品 -> 类名（2.0.3Alpha）。 */
    public final Map<String, String> itemClass;

    public SyncGridSizesPacket(Map<String, int[]> sizes, List<String> hotbarRules,
                               Map<String, int[]> classes, Map<String, String> itemClass) {
        this.sizes = sizes;
        this.hotbarRules = hotbarRules;
        this.classes = classes;
        this.itemClass = itemClass;
    }

    public static void encode(SyncGridSizesPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.sizes.size());
        for (Map.Entry<String, int[]> e : msg.sizes.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue()[0]);
            buf.writeVarInt(e.getValue()[1]);
        }
        if (msg.hotbarRules == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            buf.writeVarInt(msg.hotbarRules.size());
            for (String r : msg.hotbarRules) {
                buf.writeUtf(r);
            }
        }
        // 2.0.3Alpha：类配置
        buf.writeVarInt(msg.classes.size());
        for (Map.Entry<String, int[]> e : msg.classes.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue()[0]);
            buf.writeVarInt(e.getValue()[1]);
            buf.writeVarInt(e.getValue()[2]);
        }
        buf.writeVarInt(msg.itemClass.size());
        for (Map.Entry<String, String> e : msg.itemClass.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static SyncGridSizesPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, int[]> sizes = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            sizes.put(buf.readUtf(), new int[]{buf.readVarInt(), buf.readVarInt()});
        }
        List<String> rules = null;
        if (buf.readBoolean()) {
            rules = new ArrayList<>();
            int rn = buf.readVarInt();
            for (int i = 0; i < rn; i++) {
                rules.add(buf.readUtf());
            }
        }
        Map<String, int[]> classes = new LinkedHashMap<>();
        int cn = buf.readVarInt();
        for (int i = 0; i < cn; i++) {
            classes.put(buf.readUtf(), new int[]{buf.readVarInt(), buf.readVarInt(), buf.readVarInt()});
        }
        Map<String, String> itemClass = new LinkedHashMap<>();
        int in = buf.readVarInt();
        for (int i = 0; i < in; i++) {
            itemClass.put(buf.readUtf(), buf.readUtf());
        }
        return new SyncGridSizesPacket(sizes, rules, classes, itemClass);
    }

    public static void handle(SyncGridSizesPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg)));
        context.setPacketHandled(true);
    }

    /** 客户端处理（2.0.7Alpha 拆分：专用服务器不加载本类）。 */
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static class ClientHandler {
        static void handle(SyncGridSizesPacket msg) {
            ItemSizeConfig.applyRuntime(msg.sizes);
            GridConfig.applyRuntime(msg.hotbarRules);
            com.deltanexus.system.grid.GridClassConfig.applyRuntime(msg.classes, msg.itemClass);
        }
    }
}
