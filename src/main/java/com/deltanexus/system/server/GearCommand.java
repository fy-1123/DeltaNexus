package com.deltanexus.system.server;

import com.deltanexus.system.api.IPlayerData;
import com.deltanexus.system.grid.GearConfig;
import com.deltanexus.system.grid.GearKind;
import com.deltanexus.system.grid.GridSize;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * {@code /dn gear} 指令树（0.5.0Beta 格子背包装备）。
 *
 * <p>与 {@code /dn spawner}、{@code /dn trade} 同级，整棵子树权限等级 2（OP）。</p>
 *
 * <pre>
 * /dn gear set &lt;backpack|rig&gt; &lt;宽&gt; &lt;高&gt;   手持物品登记为该类装备（档位由尺寸决定）
 * /dn gear remove                          取消手持物品的装备登记
 * /dn gear list                            列出全部登记项
 * /dn gear equip                           把手持装备穿上
 * /dn gear unequip &lt;backpack|rig&gt;          卸下并放回背包
 * /dn gear open &lt;backpack|rig&gt;             打开已装备的容器
 * </pre>
 */
public final class GearCommand {

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> KIND_SUG =
            (ctx, b) -> {
                for (GearKind kind : GearKind.values()) {
                    b.suggest(kind.id());
                }
                return b.buildFuture();
            };

    private GearCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> gearNode() {
        return Commands.literal("gear")
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(KIND_SUG)
                                .then(Commands.argument("width", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(1))
                                                .executes(ctx -> set(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "kind"),
                                                        IntegerArgumentType.getInteger(ctx, "width"),
                                                        IntegerArgumentType.getInteger(ctx, "height")))))))
                .then(Commands.literal("remove").executes(ctx -> remove(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("equip").executes(ctx -> equip(ctx.getSource())))
                .then(Commands.literal("unequip")
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(KIND_SUG)
                                .executes(ctx -> unequip(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "kind")))))
                .then(Commands.literal("open")
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(KIND_SUG)
                                .executes(ctx -> open(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "kind")))));
    }

    // ------------------------------------------------------------------
    // 登记
    // ------------------------------------------------------------------

    private static int set(CommandSourceStack source, String kindId, int w, int h) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("§c请手持要登记为装备的物品"));
            return 0;
        }
        GearKind kind = GearKind.byId(kindId);
        if (kind == null) {
            source.sendFailure(Component.literal("§c未知装备种类：" + kindId + "（可用：backpack / rig）"));
            return 0;
        }
        GridSize size = new GridSize(w, h);
        GridSize clamped = new GridSize(Math.min(kind.maxSize().w(), w), Math.min(kind.maxSize().h(), h));
        if (!GearConfig.set(held.getItem(), kind, size)) {
            source.sendFailure(Component.literal("§c登记失败：物品 id 无效"));
            return 0;
        }
        String id = idOf(held.getItem());
        source.sendSuccess(() -> Component.literal("§a已登记 " + id + " 为 " + kindId
                + " " + clamped.w() + "×" + clamped.h()
                + (clamped.w() != w || clamped.h() != h ? "（超出上限已夹取）" : "")), true);
        return 1;
    }

    private static int remove(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("§c请手持要取消登记的物品"));
            return 0;
        }
        if (!GearConfig.remove(held.getItem())) {
            source.sendFailure(Component.literal("§c该物品未被登记为装备"));
            return 0;
        }
        String id = idOf(held.getItem());
        source.sendSuccess(() -> Component.literal("§a已取消登记 " + id), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        Map<String, GearConfig.GearSpec> all = GearConfig.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7当前没有任何装备登记项"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("§7装备登记表（共 " + all.size() + " 项）"), false);
        for (Map.Entry<String, GearConfig.GearSpec> e : all.entrySet()) {
            GearConfig.GearSpec spec = e.getValue();
            source.sendSuccess(() -> Component.literal("§f" + e.getKey() + " §7→ §b" + spec.kind().id()
                    + " §7" + spec.size().w() + "×" + spec.size().h()), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 穿戴
    // ------------------------------------------------------------------

    private static int equip(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || GearConfig.kindOf(held.getItem()) == null) {
            source.sendFailure(Component.literal("§c手持物品不是已登记的背包/胸挂"));
            return 0;
        }
        return GearService.equip(player, held, InteractionHand.MAIN_HAND) ? 1 : 0;
    }

    private static int unequip(CommandSourceStack source, String kindId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GearKind kind = GearKind.byId(kindId);
        if (kind == null) {
            source.sendFailure(Component.literal("§c未知装备种类：" + kindId));
            return 0;
        }
        IPlayerData data = GearService.data(player);
        if (data == null || data.getEquipped(kind).isEmpty()) {
            source.sendFailure(Component.literal("§c未装备" + GearService.displayName(kind)));
            return 0;
        }
        // 先拷贝再清空：getEquipped 返回活引用，清空后原栈可能被复用
        ItemStack gear = data.getEquipped(kind).copy();
        data.setEquipped(kind, ItemStack.EMPTY);
        if (!player.getInventory().add(gear)) {
            player.drop(gear, false);
        }
        // 客户端装备缓存同步清空（否则界面仍画着已卸下的图标）
        GearService.sendSync(player, kind);
        source.sendSuccess(() -> Component.literal("§a已卸下" + GearService.displayName(kind)), true);
        return 1;
    }

    private static int open(CommandSourceStack source, String kindId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GearKind kind = GearKind.byId(kindId);
        if (kind == null) {
            source.sendFailure(Component.literal("§c未知装备种类：" + kindId));
            return 0;
        }
        GearService.openGear(player, kind);
        return 1;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("""
                §b/dn gear §7格子背包装备
                §f/dn gear set <backpack|rig> <宽> <高> §7手持物品登记（上限 9×6）
                §f/dn gear remove §7取消手持物品的登记
                §f/dn gear list §7列出全部登记项
                §f/dn gear equip §7手持装备穿上
                §f/dn gear unequip <backpack|rig> §7卸下并放回背包
                §f/dn gear open <backpack|rig> §7打开已装备的容器"""), false);
        return 1;
    }

    private static String idOf(Item item) {
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "" : key.toString();
    }
}
