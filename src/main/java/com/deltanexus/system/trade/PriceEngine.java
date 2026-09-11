package com.deltanexus.system.trade;

import com.deltanexus.system.DeltaNexus;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * 交易行价格引擎：以受限 Rhino JS 沙箱执行 formula（单表达式）与 code（函数体）策略。
 *
 * <p>ctx 结构与变量：</p>
 * <pre>{@code
 * ctx.good        { id, displayName, stock, stockMin, stockMax, unitCount }
 * ctx.limits      { stockMin, stockMax }          // 库存上下限（0 = 不限）
 * ctx.qty         本次购买数量（unit）
 * ctx.direction   "buy"（v1 仅买入；sell/market 供展示与后续版本）
 * ctx.time        { hour, weekday(1-7), day }
 * ctx.feed(name)  -> 源对象 { rows:[...], price(key), find(cond), meta }
 * ctx.hasFeed(name) -> boolean
 * 全局 helper: clamp(x, lo, hi)
 * }</pre>
 *
 * <p>求值规则：结果必须是有限数值；交易行统一 floor 且 ≥ 1（0/负/异常 = 定价不可用）。
 * 沙箱：禁止一切 Java 类访问（ClassShutter 全拒）、解释器模式 + 指令计数超时中断。
 * 仅 OP 可写公式/脚本（信任边界），此处做的是防误伤与明显逃逸。</p>
 */
public final class PriceEngine {

    /** 求值失败（含超时/非法脚本/非数值结果）。 */
    public static final class EvalException extends Exception {
        public EvalException(String message) {
            super(message);
        }

        public EvalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 单次求值入参（由调用方填充）。 */
    public static final class Input {
        public String goodId = "";
        public String displayName = "";
        public int stock = 0;
        public int stockMin = 0;
        public int stockMax = 0;
        public int unitCount = 1;
        public int qty = 1;
        /** "buy"（v1 执行方向）。 */
        public String direction = "buy";
        public int hour = 0;
        public int weekday = 1;
        public int day = 1;
    }

    // ------------------------------------------------------------------
    // 沙箱 Context（解释器模式 + 指令计数超时 + 禁 Java）
    // ------------------------------------------------------------------

    /** 供 observeInstructionCount 判断的时间预算。 */
    private static final class Budget {
        long deadlineNanos;
    }

    private static final ThreadLocal<Budget> BUDGET = new ThreadLocal<>();

    /** 超时信号（观察回调内抛出，向上传递终止求值）。 */
    private static final class EvalTimeout extends RuntimeException {
        EvalTimeout() {
            super("script timeout");
        }
    }

    private static final ClassShutter SHUTTER = fullClassName -> false;

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setClassShutter(SHUTTER);
            // 解释器模式：指令计数精确，避免 JIT 编译产物绕过中断
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(10_000);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Budget b = BUDGET.get();
            if (b != null && System.nanoTime() > b.deadlineNanos) {
                throw new EvalTimeout();
            }
        }
    };

    static {
        try {
            ContextFactory.initGlobal(FACTORY);
        } catch (Throwable t) {
            // 其它库已初始化全局工厂时忽略（同一 JVM 仅一次）
            DeltaNexus.LOGGER.debug("[DN] Rhino ContextFactory 已由其它方初始化: {}", t.getMessage());
        }
    }

    private PriceEngine() {
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /**
     * 求值单个价格策略。
     *
     * @param policy   策略（mode=FIXED 时不进 JS）
     * @param in       运行时上下文
     * @param feedJson 预序列化的 feeds JSON 文本（服务端批量求值时复用；
     *                 null = 无外部源数据）
     * @param timeoutMs 超时毫秒
     * @return 原始价格（可能非整数；≥0）
     * @throws EvalException 定价不可用
     */
    public static double eval(PricePolicy policy, Input in, @Nullable String feedJson, long timeoutMs)
            throws EvalException {
        if (policy == null) {
            throw new EvalException("缺少价格策略");
        }
        if (policy.mode == PricePolicy.Mode.FIXED) {
            return policy.value;
        }
        String source = buildSource(policy, in, feedJson);
        Budget budget = new Budget();
        budget.deadlineNanos = System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L;
        Context cx = null;
        try {
            cx = Context.enter();
            BUDGET.set(budget);
            Scriptable scope = cx.initStandardObjects();
            // 移除脚本可触及的 Java 顶层对象（纵深防御；ClassShutter 已全拒）
            for (String name : new String[]{"Packages", "java", "javax", "org", "com", "getClass"}) {
                try {
                    scope.delete(name);
                } catch (Exception ignored) {
                    // 部分对象可能为不可删属性，忽略
                }
            }
            Object result = cx.evaluateString(scope, source, "<trade-price>", 1, null);
            return Context.toNumber(result);
        } catch (EvalTimeout t) {
            throw new EvalException("公式/脚本执行超时（" + timeoutMs + "ms）");
        } catch (Exception e) {
            throw new EvalException("公式/脚本错误: " + brief(e));
        } finally {
            BUDGET.remove();
            if (cx != null) {
                Context.exit();
            }
        }
    }

    // ------------------------------------------------------------------
    // 源码构建
    // ------------------------------------------------------------------

    private static String buildSource(PricePolicy policy, Input in, @Nullable String feedJson) throws EvalException {
        StringBuilder sb = new StringBuilder(256 + (feedJson == null ? 0 : feedJson.length()));
        sb.append("var ctx = {");
        sb.append("good:{id:").append(jsQuote(in.goodId))
                .append(",displayName:").append(jsQuote(in.displayName))
                .append(",stock:").append(in.stock)
                .append(",stockMin:").append(in.stockMin)
                .append(",stockMax:").append(in.stockMax)
                .append(",unitCount:").append(in.unitCount).append("},");
        sb.append("limits:{stockMin:").append(in.stockMin).append(",stockMax:").append(in.stockMax).append("},");
        sb.append("qty:").append(in.qty).append(',');
        sb.append("direction:").append(jsQuote(in.direction)).append(',');
        sb.append("time:{hour:").append(in.hour).append(",weekday:").append(in.weekday)
                .append(",day:").append(in.day).append("},");
        sb.append("feeds:").append(feedJson == null ? "{}" : feedJson);
        sb.append("};\n");

        // 预置函数与 feed 包装
        sb.append(PRELUDE);

        String expr = switch (policy.mode) {
            case FORMULA -> "(function(){ return (" + policy.expr + "); }).call(ctx)";
            case CODE -> "(function(){ var __f = function(ctx){\n" + policy.script + "\n}; return __f(ctx); }).call(ctx)";
            default -> throw new EvalException("不支持的模式: " + policy.mode.key);
        };
        sb.append("var __r = ").append(expr).append(";\n");
        sb.append("if (typeof __r !== 'number' || !isFinite(__r)) throw new Error('结果不是有限数字');\n");
        sb.append("__r;\n");
        return sb.toString();
    }

    /** JS 预置：clamp helper + ctx.feed() 包装（每行引用式包装，成本低）。 */
    private static final String PRELUDE = """
            function clamp(x, lo, hi){ return Math.min(Math.max(x, lo), hi); }
            (function(){
              var feeds = ctx.feeds || {};
              ctx.feeds = feeds;
              function wrap(f){
                var rows = Array.isArray(f.rows) ? f.rows : [];
                f.rows = rows;
                f.price = function(key){
                  key = String(key);
                  for (var i = 0; i < rows.length; i++){
                    var r = rows[i];
                    var k = (r.id !== undefined) ? r.id : ((r.key !== undefined) ? r.key : null);
                    if (k !== null && String(k) === key){
                      var p = (r.price !== undefined) ? r.price : r.current_price;
                      return (typeof p === 'number') ? p : null;
                    }
                  }
                  return null;
                };
                f.find = function(cond){
                  for (var i = 0; i < rows.length; i++){
                    var r = rows[i]; var ok = true;
                    for (var c in cond){ if (cond.hasOwnProperty(c) && r[c] !== cond[c]){ ok = false; break; } }
                    if (ok) return r;
                  }
                  return null;
                };
                return f;
              }
              for (var n in feeds){ if (feeds.hasOwnProperty(n)) wrap(feeds[n]); }
              ctx.feed = function(name){ return feeds.hasOwnProperty(name) ? feeds[name] : null; };
              ctx.hasFeed = function(name){ return feeds.hasOwnProperty(name); };
            })();
            """;

    /** 将若干 FeedSnapshot 预序列化为 JS 对象字面量（批量求值可复用）。 */
    public static String buildFeedsJson(List<FeedSnapshot> feeds) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (FeedSnapshot snap : feeds) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsQuote(snap.source())).append(":{meta:{")
                    .append("source:").append(jsQuote(snap.source()))
                    .append(",generatedAt:").append(snap.generatedAtEpochMs())
                    .append(",loadedAt:").append(snap.loadedAtEpochMs())
                    .append("},rows:[");
            boolean firstRow = true;
            for (FeedSnapshot.Row row : snap.rows()) {
                if (!firstRow) {
                    sb.append(',');
                }
                firstRow = false;
                sb.append('{');
                boolean firstField = true;
                for (Map.Entry<String, Object> e : row.fields().entrySet()) {
                    if (!firstField) {
                        sb.append(',');
                    }
                    firstField = false;
                    sb.append(jsQuote(e.getKey())).append(':').append(literal(e.getValue()));
                }
                sb.append('}');
            }
            sb.append("]}");
        }
        sb.append('}');
        return sb.toString();
    }

    /** 值 -> JS 字面量（String/Number/Boolean/null；其余按字符串转义）。 */
    private static String literal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d)) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    return Long.toString((long) d);
                }
                return Double.toString(d);
            }
            return "null";
        }
        return jsQuote(String.valueOf(v));
    }

    private static String jsQuote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m != null && !m.isBlank()) {
            // JavaScriptException 消息形如 "Error: xxx (EcmaError#1)"，截断噪声
            int cut = m.indexOf(" (");
            if (cut > 0) {
                m = m.substring(0, cut);
            }
            return m.length() > 200 ? m.substring(0, 200) + "…" : m;
        }
        return t.getClass().getSimpleName();
    }
}
