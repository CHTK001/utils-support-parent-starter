package com.chua.common.support.converter.definition;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.chua.common.support.constant.CommonConstant.*;


/**
 * Set 类型转换器。
 * <p>将各种类型的值转换为 {@link Set}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link Set} — 直接返回</li>
 *   <li>{@link java.util.Collection} — 转为 HashSet</li>
 *   <li>{@link String} — 支持 JSON 数组格式（[a,b,c]）和逗号分隔字符串</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
public class SetTypeConverter implements TypeConverter<Set> {
    /** 单例实例 */
    public static final SetTypeConverter INSTANCE = new SetTypeConverter();

    /**
     * 将给定值转换为 Set。
     *
     * @param value 源值
     * @return Set 值，如果为 null 则返回 null
     */
    @Override
    public Set convert(Object value) {
        if (null == value) {
            return null;
        }

        if (isAssignableFrom(value, Set.class)) {
            return (Set) value;
        }

        if (isAssignableFrom(value, Collection.class)) {
            return new HashSet((Collection) value);
        }

        if (isAssignableFrom(value, String.class)) {
            String string = value.toString().trim();
            if (string.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && string.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                String inner = string.substring(1, string.length() - 1);
                String[] parts = inner.split(SYMBOL_COMMA);
                Set<String> result = new LinkedHashSet<>();
                for (String part : parts) {
                    result.add(part.trim());
                }
                return result;
            }
            String[] parts = string.split(SYMBOL_COMMA);
            Set<String> result = new LinkedHashSet<>();
            for (String part : parts) {
                result.add(part.trim());
            }
            if (result.size() > 0) {
                return result;
            }
        }

        return convertIfNecessary(value);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Set.class
     */
    @Override
    public Class<Set> getType() {
        return Set.class;
    }
}
