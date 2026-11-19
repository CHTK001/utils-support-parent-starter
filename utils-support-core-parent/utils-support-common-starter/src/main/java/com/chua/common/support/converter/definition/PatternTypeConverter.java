package com.chua.common.support.converter.definition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Pattern 类型转换器。
 * <p>将字符串转换为 {@link Pattern} 正则表达式对象。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/19
 */
public class PatternTypeConverter implements TypeConverter<Pattern> {

    /**
     * 已编译正则缓存：Pattern 不可变且线程安全，可全局复用
     * [P3C C1 修复] 动态正则无法提升为类级常量，改为缓存复用，避免方法内重复编译
     */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Pattern.class
     */
    @Override
    public Class<Pattern> getType() {
        return Pattern.class;
    }

    /**
     * 将给定值转换为 Pattern。
     *
     * @param value 源值（正则表达式字符串）
     * @return Pattern 值，如果无法转换则返回 null
     */
    @Override
    public Pattern convert(Object value) {
        if (value instanceof String) {
            // [P3C C1 修复] 编译结果进缓存复用，同一正则只编译一次
            return PATTERN_CACHE.computeIfAbsent((String) value, Pattern::compile);
        }
        return null;
    }
}
