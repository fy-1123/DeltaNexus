package com.deltanexus.system.common;

import com.deltanexus.system.DeltaNexus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import javax.annotation.Nullable;

/**
 * NBT 工具类。
 *
 * <p>NBT 匹配口径统一由 {@link NbtSpec} 承担（{@code id / full_nbt / partial_nbt} + 键规则），
 * 本类只保留 SNBT 文本解析能力。</p>
 */
public final class NbtMatcher {

    private NbtMatcher() {
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
