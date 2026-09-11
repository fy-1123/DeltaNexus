package com.deltanexus.system.trade;

import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * 交易行分类（参考制作台工作台注册表：管理员配置，每个商品必属一个分类）。
 */
public final class TradeCategory {

    /** 分类 id（唯一、小写习惯）。 */
    public String id = "";
    /** 分类显示名。 */
    public String name = "";

    public TradeCategory() {
    }

    public TradeCategory(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : id;
    }

    public boolean isValid() {
        return id != null && !id.isBlank();
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name == null ? "" : name);
        return obj;
    }

    public static TradeCategory fromJson(@Nullable JsonObject obj) {
        TradeCategory c = new TradeCategory();
        if (obj == null) {
            return c;
        }
        c.id = obj.has("id") ? obj.get("id").getAsString() : "";
        c.name = obj.has("name") ? obj.get("name").getAsString() : "";
        return c;
    }
}
