package com.deltanexus.system.test;

import com.deltanexus.system.server.TradeService;
import com.deltanexus.system.trade.ItemSpec;
import com.deltanexus.system.trade.PriceEngine;
import com.deltanexus.system.trade.PricePolicy;
import com.deltanexus.system.trade.TradeGood;
import com.deltanexus.system.trade.TradeStockStore;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 交易行回归测试（0.2.0Beta）。
 *
 * <p>仅在 GameTestServer（{@code gradlew runGameTestServer}）中执行。
 * 覆盖纯逻辑：商品规格三种匹配模式（id/全NBT/指定键）、价格策略序列化、JS 求值与错误处理、
 * 运行时库存落盘与重载（重启不丢库存）。</p>
 */
public class TradeRegressionTests {

    /** 由 {@code DeltaNexus} 构造器调用。 */
    public static void register() {
        GameTestRegistry.register(TradeRegressionTests.class);
    }

    /** 库存落盘 + 重载：写入 → 重新 load → 值保持（覆盖“重启后库存归零”缺陷）。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void stockPersistAndReload(GameTestHelper helper) {
        String id = "__dn_regression_stock__";
        TradeStockStore.set(id, 7);
        if (TradeStockStore.get(id) != 7) {
            helper.fail("set 后内存库存应为 7，实际 " + TradeStockStore.get(id));
        }
        // 模拟重启：从磁盘重新加载
        TradeStockStore.load();
        if (TradeStockStore.get(id) != 7) {
            helper.fail("重载后库存应保持 7（库存未落盘），实际 " + TradeStockStore.get(id));
        }
        // 增减 + 扣除路径同样持久化
        TradeStockStore.add(id, 3);
        TradeStockStore.load();
        if (TradeStockStore.get(id) != 10) {
            helper.fail("add 后重载应为 10，实际 " + TradeStockStore.get(id));
        }
        if (!TradeStockStore.consume(id, 4) || TradeStockStore.get(id) != 6) {
            helper.fail("consume 后应为 6，实际 " + TradeStockStore.get(id));
        }
        TradeStockStore.load();
        if (TradeStockStore.get(id) != 6) {
            helper.fail("consume 后重载应为 6，实际 " + TradeStockStore.get(id));
        }
        TradeStockStore.set(id, 0); // 清理测试键
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void itemSpecMatchModes(GameTestHelper helper) {
        // 1) id：只按物品
        ItemSpec idSpec = new ItemSpec();
        idSpec.item = "minecraft:iron_ingot";
        idSpec.matchMode = ItemSpec.MatchMode.ID;
        if (!idSpec.matches(new ItemStack(Items.IRON_INGOT))) {
            helper.fail("id 模式应匹配铁锭");
        }
        ItemStack taggedIron = new ItemStack(Items.IRON_INGOT);
        taggedIron.getOrCreateTag().putInt("foo", 1);
        if (!idSpec.matches(taggedIron)) {
            helper.fail("id 模式应忽略 NBT");
        }
        if (idSpec.matches(new ItemStack(Items.DIAMOND))) {
            helper.fail("id 模式不应匹配钻石");
        }

        // 2) full_nbt：整份 NBT 精确相等
        ItemSpec fullSpec = new ItemSpec();
        fullSpec.item = "minecraft:iron_ingot";
        fullSpec.matchMode = ItemSpec.MatchMode.FULL_NBT;
        fullSpec.nbt = "{foo:1}";
        ItemStack ok = new ItemStack(Items.IRON_INGOT);
        ok.getOrCreateTag().putInt("foo", 1);
        ItemStack bad = new ItemStack(Items.IRON_INGOT);
        bad.getOrCreateTag().putInt("foo", 2);
        if (!fullSpec.matches(ok) || fullSpec.matches(bad)) {
            helper.fail("full_nbt 应精确匹配模板 NBT");
        }

        // 3) partial_nbt：只比较指定键（exact/contains）
        ItemSpec partSpec = new ItemSpec();
        partSpec.item = "minecraft:iron_sword";
        partSpec.matchMode = ItemSpec.MatchMode.PARTIAL_NBT;
        partSpec.nbt = "{Damage:0}";
        partSpec.matchKeys.put("Damage", new ItemSpec.KeyRule(ItemSpec.KeyOp.EXACT, ""));
        ItemStack sword0 = new ItemStack(Items.IRON_SWORD);
        sword0.getOrCreateTag().putInt("Damage", 0);
        ItemStack sword1 = new ItemStack(Items.IRON_SWORD);
        sword1.getOrCreateTag().putInt("Damage", 1);
        if (!partSpec.matches(sword0) || partSpec.matches(sword1)) {
            helper.fail("partial_nbt Damage exact 应只匹配 Damage=0");
        }
        // 生成栈：模板 NBT 应被写入
        ItemStack built = partSpec.buildStack(1);
        if (built.isEmpty() || built.getOrCreateTag().getInt("Damage") != 0) {
            helper.fail("buildStack 应写入模板 NBT");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void pricePolicyJsonAndEval(GameTestHelper helper) {
        try {
            pricePolicyJsonAndEvalInner(helper);
            helper.succeed();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder("[").append(t.getClass().getName()).append("] ").append(t.getMessage());
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth < 8) {
                for (StackTraceElement e : cur.getStackTrace()) {
                    if (e.getClassName().contains("deltanexus") || e.getClassName().contains("javascript")
                            || e.getClassName().contains("trade")) {
                        sb.append('\n').append(e);
                        if (sb.length() > 1200) {
                            break;
                        }
                    }
                }
                cur = cur.getCause();
                depth++;
            }
            helper.fail(sb.length() > 1500 ? sb.substring(0, 1500) : sb.toString());
        }
    }

    private void pricePolicyJsonAndEvalInner(GameTestHelper helper) throws Exception {
        // 序列化往返
        PricePolicy fixed = new PricePolicy(PricePolicy.Mode.FIXED);
        fixed.value = 500;
        JsonObject json = fixed.toJson();
        PricePolicy back = PricePolicy.fromJson(json);
        if (back.mode != PricePolicy.Mode.FIXED || back.value != 500.0) {
            helper.fail("fixed 策略序列化往返失败");
        }
        PricePolicy formula = PricePolicy.fromJson(new com.google.gson.JsonParser()
                .parse("{\"mode\":\"formula\",\"expr\":\"1+ctx.qty\"}").getAsJsonObject());
        if (!formula.isValid()) {
            helper.fail("formula 策略解析失败");
        }

        // JS 求值（Rhino）：表达式可读 ctx 变量
        PriceEngine.Input in = new PriceEngine.Input();
        in.goodId = "t";
        in.qty = 2;
        in.stock = 10;
        in.stockMin = 2;
        in.stockMax = 0;
        in.direction = "buy";
        try {
            double v = PriceEngine.eval(formula, in, null, 50);
            if (v != 3.0) { // 3.0 是期望价格，不是版本号
                helper.fail("公式 1+ctx.qty 应返回 3，实际 " + v);
            }
        } catch (PriceEngine.EvalException e) {
            helper.fail("公式求值不应抛错: " + e.getMessage());
        }
        // clamp 助手 + limits 变量
        PricePolicy clampPolicy = PricePolicy.fromJson(new com.google.gson.JsonParser()
                .parse("{\"mode\":\"formula\",\"expr\":\"clamp(ctx.limits.stockMin * 10, 0, ctx.good.stock)\"}")
                .getAsJsonObject());
        try {
            double v = PriceEngine.eval(clampPolicy, in, null, 50);
            if (v != 10.0) {
                helper.fail("clamp 公式应返回 10，实际 " + v);
            }
        } catch (PriceEngine.EvalException e) {
            helper.fail("clamp 求值不应抛错: " + e.getMessage());
        }
        // 非法结果（返回字符串）→ 定价不可用
        PricePolicy bad = PricePolicy.fromJson(new com.google.gson.JsonParser()
                .parse("{\"mode\":\"formula\",\"expr\":\"'abc'\"}").getAsJsonObject());
        boolean thrown = false;
        try {
            PriceEngine.eval(bad, in, null, 50);
        } catch (PriceEngine.EvalException e) {
            thrown = true;
        }
        if (!thrown) {
            helper.fail("非数值结果应抛 EvalException");
        }
    }

    /** 仓库卖出匹配优先级：full_nbt > partial_nbt > id；无 sell 策略的商品不参与。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void sellMatchingPriority(GameTestHelper helper) {
        TradeGood idGood = sellGood("id_good", ItemSpec.MatchMode.ID, "");
        TradeGood partGood = sellGood("part_good", ItemSpec.MatchMode.PARTIAL_NBT, "{foo:1}");
        partGood.spec.matchKeys.put("foo", new ItemSpec.KeyRule(ItemSpec.KeyOp.EXACT, ""));
        TradeGood fullGood = sellGood("full_good", ItemSpec.MatchMode.FULL_NBT, "{foo:1}");
        TradeGood noSell = sellGood("no_sell", ItemSpec.MatchMode.ID, "");
        noSell.sell = null;

        java.util.List<TradeGood> goods = java.util.List.of(idGood, partGood, fullGood, noSell);

        ItemStack tagged = new ItemStack(Items.IRON_INGOT);
        tagged.getOrCreateTag().putInt("foo", 1);
        if (TradeService.bestSellGood(goods, tagged) != fullGood) {
            helper.fail("带 foo=1 的物品应优先命中 full_nbt 商品");
        }
        ItemStack plain = new ItemStack(Items.IRON_INGOT);
        if (TradeService.bestSellGood(goods, plain) != idGood) {
            helper.fail("无 NBT 的铁锭应命中 id 商品");
        }
        // 只有 partial 命中：去掉 full 商品后
        java.util.List<TradeGood> withoutFull = java.util.List.of(idGood, partGood, noSell);
        if (TradeService.bestSellGood(withoutFull, tagged) != partGood) {
            helper.fail("无 full 商品时应命中 partial_nbt 商品");
        }
        // 无卖出策略的商品永不被匹配
        if (TradeService.bestSellGood(java.util.List.of(noSell), tagged) != null) {
            helper.fail("无 sell 策略的商品不应参与回收匹配");
        }
        helper.succeed();
    }

    /** 指定键值（specified）、旧 ignore 兼容、耐久度要求。 */
    @GameTest(template = "empty", templateNamespace = "deltanexus", timeoutTicks = 1200)
    public void specifiedKeyAndDurability(GameTestHelper helper) {
        // specified：键名 + 显式值（不依赖 NBT 模板）
        ItemSpec num = new ItemSpec();
        num.item = "minecraft:iron_sword";
        num.matchMode = ItemSpec.MatchMode.PARTIAL_NBT;
        num.matchKeys.put("Damage", new ItemSpec.KeyRule(ItemSpec.KeyOp.SPECIFIED, "5"));
        ItemStack d5 = new ItemStack(Items.IRON_SWORD);
        d5.getOrCreateTag().putInt("Damage", 5);
        ItemStack d6 = new ItemStack(Items.IRON_SWORD);
        d6.getOrCreateTag().putInt("Damage", 6);
        if (!num.matches(d5) || num.matches(d6)) {
            helper.fail("specified 应只匹配 Damage=5");
        }
        // 旧 ignore 字符串：读取时该键不参与匹配
        ItemSpec legacy = ItemSpec.fromJson(new com.google.gson.JsonParser()
                .parse("{\"id\":\"minecraft:iron_sword\",\"match_mode\":\"partial_nbt\","
                        + "\"nbt\":\"{Damage:0}\",\"match_keys\":{\"Damage\":\"ignore\"}}")
                .getAsJsonObject());
        if (!legacy.matchKeys.isEmpty() || !legacy.matches(d6)) {
            helper.fail("旧 ignore 键应被忽略（Damage 不同也应命中）");
        }
        // specified 的 JSON 往返
        ItemSpec round = ItemSpec.fromJson(new com.google.gson.JsonParser()
                .parse("{\"id\":\"minecraft:iron_sword\",\"match_mode\":\"partial_nbt\","
                        + "\"match_keys\":{\"Damage\":{\"op\":\"specified\",\"value\":\"5\"}}}")
                .getAsJsonObject());
        if (!round.matches(d5) || round.matches(d6)) {
            helper.fail("specified 规则序列化往返失败");
        }

        // 耐久度：剩余耐久 = 最大耐久 − Damage（铁剑 250）
        ItemSpec dur = new ItemSpec();
        dur.item = "minecraft:iron_sword";
        dur.matchMode = ItemSpec.MatchMode.ID;
        dur.durabilityEnabled = true;
        dur.durabilityOp = "<";
        dur.durabilityValue = 50;
        ItemStack worn = new ItemStack(Items.IRON_SWORD);
        worn.getOrCreateTag().putInt("Damage", 240); // 剩余 10
        ItemStack fresh = new ItemStack(Items.IRON_SWORD); // 剩余 250
        if (!dur.matches(worn) || dur.matches(fresh)) {
            helper.fail("耐久要求 (<50) 应只匹配磨损件");
        }
        dur.durabilityOp = ">";
        dur.durabilityValue = 100;
        if (!dur.matches(fresh) || dur.matches(worn)) {
            helper.fail("耐久要求 (>100) 应只匹配完好件");
        }
        // 不可损坏物品不满足耐久要求
        ItemSpec durIngot = new ItemSpec();
        durIngot.item = "minecraft:iron_ingot";
        durIngot.matchMode = ItemSpec.MatchMode.ID;
        durIngot.durabilityEnabled = true;
        durIngot.durabilityOp = ">";
        durIngot.durabilityValue = 0;
        if (durIngot.matches(new ItemStack(Items.IRON_INGOT))) {
            helper.fail("无耐久属性的物品不应满足耐久要求");
        }
        helper.succeed();
    }

    private static TradeGood sellGood(String id, ItemSpec.MatchMode mode, String nbt) {
        TradeGood g = new TradeGood();
        g.id = id;
        g.categoryId = "c";
        g.spec.item = "minecraft:iron_ingot";
        g.spec.matchMode = mode;
        g.spec.nbt = nbt;
        PricePolicy p = new PricePolicy(PricePolicy.Mode.FIXED);
        p.value = 10;
        g.sell = p;
        return g;
    }
}
