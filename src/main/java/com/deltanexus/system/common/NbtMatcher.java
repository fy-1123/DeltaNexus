package com.deltanexus.system.common;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * NBT 匹配工具类。
 *
 * <p>支持三种匹配模式：</p>
 * <ul>
 *   <li>{@code exact}   —— 完全匹配（物品 + 数量 + 完整 NBT 相等）</li>
 *   <li>{@code contains}—— 包含匹配（物品 + 数量 + 期望 NBT 中的每个键都存在且相等）</li>
 *   <li>{@code ignore}  —— 忽略 NBT（仅匹配物品与数量）</li>
 * </ul>
 */
public final class NbtMatcher {

    public enum MatchType {
        EXACT,
        CONTAINS,
        IGNORE;

        public static MatchType parse(@Nullable String s) {
            if (s == null || s.isBlank()) {
                return IGNORE;
            }
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return IGNORE;
            }
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private NbtMatcher() {
    }

    /**
     * 校验物品是否满足配方输入要求。
     *
     * @param actual      实际物品栈
     * @param expectedId  期望物品注册名，如 "minecraft:iron_ingot"
     * @param count       期望数量
     * @param type        NBT 匹配模式
     * @param nbtString   期望 NBT 字符串（exact/contains 时使用，可空）
     */
    public static boolean matchesItem(ItemStack actual, String expectedId, int count,
                                      MatchType type, @Nullable String nbtString) {
        Item expected = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(expectedId));
        if (expected == null || !actual.is(expected)) {
            return false;
        }
        if (actual.getCount() < count) {
            return false;
        }
        return matchesNbt(actual.getTag(), parseTag(nbtString), type);
    }

    /** 判断实际 NBT 是否满足期望（按模式）。 */
    public static boolean matchesNbt(@Nullable CompoundTag actual, @Nullable CompoundTag expected, MatchType type) {
        if (type == MatchType.IGNORE) {
            return true;
        }
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        if (type == MatchType.EXACT) {
            return Objects.equals(actual, expected);
        }
        // CONTAINS：期望的每个键都存在且值相等
        for (String key : expected.getAllKeys()) {
            if (!actual.contains(key) || !Objects.equals(actual.get(key), expected.get(key))) {
                return false;
            }
        }
        return true;
    }

    /** 解析 NBT 字符串；空串 / 非法返回 null（不抛异常）。 */
    @Nullable
    public static CompoundTag parseTag(@Nullable String nbtString) {
        if (nbtString == null || nbtString.isBlank()) {
            return null;
        }
        try {
            return TagParser.parseTag(nbtString.trim());
        } catch (Exception e) {
            DeltaNexus.LOGGER.warn("[DN] 非法 NBT 字符串 '{}': {}", nbtString, e.getMessage());
            return null;
        }
    }
}
