package com.chua.common.support.converter.definition;


import com.chua.common.support.utils.ArrayUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * JsonArray 类型转换器（以 List 作为底层存储类型）。
 * <p>将各种类型的值转换为 {@link List}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link String} — 解析 JSON 数组格式的字符串（[...]），自动去除引号</li>
 *   <li>{@code byte[]} — 先转为 String 再解析</li>
 *   <li>{@link java.util.Collection} — 转为 ArrayList</li>
 *   <li>{@link Map} — 以单元素列表返回</li>
 *   <li>数组类型 — 通过 ArrayUtils.toList 转换</li>
 * </ul>
 *
 * @author CH
 */
public class JsonArrayTypeConverter implements TypeConverter<List> {
    @Override
    public Class<List> getType() {
        return List.class;
    }

    /**
     * 将给定值转换为 List（JSON 数组）。
     *
     * @param value 源值
     * @return List 值，如果无法转换则返回 null
     */
    @Override
    public List convert(Object value) {
        if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("[") && str.endsWith("]")) {
                String inner = str.substring(1, str.length() - 1);
                if (inner.trim().isEmpty()) {
                    return Collections.emptyList();
                }
                String[] parts = inner.split(",");
                List<Object> result = new ArrayList<>();
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        result.add(trimmed);
                    } else {
                        result.add(trimmed.replaceAll("^\"|\"$", ""));
                    }
                }
                return result;
            }
            return Collections.emptyList();
        }

        if (value instanceof byte[]) {
            return convert(new String((byte[]) value));
        }

        if (value instanceof Collection) {
            return new ArrayList((Collection) value);
        }

        if (value instanceof Map) {
            return new ArrayList(Collections.singletonList(value));
        }

        if (value.getClass().isArray() && Array.getLength(value) > 0) {
            Collection<?> list = ArrayUtils.toList(value);
            return new ArrayList<>(list);
        }

        return null;
    }
}
