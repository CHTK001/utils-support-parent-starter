package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.MapUtils;

import java.util.*;

import static com.chua.common.support.constant.CommonConstant.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Map 类型转换器。
 * <p>将各种类型的值转换为 {@link Map}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link java.util.Dictionary} — 遍历键值对构造 HashMap</li>
 *   <li>{@link Map} — 直接返回</li>
 *   <li>{@link String} — 支持以下格式：
 *     <ul>
 *       <li>{key=value, key2=value2} — 大括号包裹的键值对</li>
 *       <li>key=value 或 key:value — 逗号/分号分隔的键值对</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
public class MapTypeConverter implements TypeConverter<Map> {


    public static final MapTypeConverter INSTANCE = new MapTypeConverter();

    /**
     * 将给定值转换为 Map。
     *
     * @param value 源值
     * @return Map 值，如果为 null 则返回空 Map
     */
    @Override
    @SuppressWarnings("ALL")
    public Map convert(Object value) {
        if (null == value) {
            return Collections.emptyMap();
        }

        if (isAssignableFrom(value, Dictionary.class)) {
            Dictionary dictionary = (Dictionary) value;
            Map item = new HashMap<>(1 << 4);
            Enumeration enumeration = dictionary.keys();
            while (enumeration.hasMoreElements()) {
                Object element = enumeration.nextElement();
                item.put(element, dictionary.get(element));
            }

            return item;
        }

        if (isAssignableFrom(value, Map.class)) {
            return (Map) value;
        }

        if (isAssignableFrom(value, String.class)) {
            String string = value.toString().trim();
            if (string.startsWith(SYMBOL_LEFT_BIG_PARENTHESES) && string.endsWith(SYMBOL_RIGHT_BIG_PARENTHESES)) {
                String substring = string.substring(1, string.length() - 1);
                String[] parts = substring.split(SYMBOL_COMMA);
                Map<String, Object> rs = new LinkedHashMap<>();
                for (String s : parts) {
                    s = s.trim();
                    int index = s.indexOf(SYMBOL_EQUALS);
                    if (index > -1) {
                        rs.put(s.substring(0, index).trim(), createValue(s, index));
                        continue;
                    }

                    index = s.indexOf(SYMBOL_COLON);
                    if (index > -1) {
                        rs.put(s.substring(0, index).trim(), createValue(s, index));
                    }
                }

                return rs;
            }
            if (string.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && string.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                // Treat as a JSON array-like string, try to parse as key-value pairs
                return Collections.emptyMap();
            }
            // Try simple key=value or key:value format
            Map<String, String> map = parseKeyValue(string, ",", "=");
            if (null != map && (map.size() > 1 || (map.size() == 1 && null != MapUtils.getFirst(map).getValue()))) {
                return map;
            }

            map = parseKeyValue(string, ";", ":");
            if (null != map && (map.size() > 1 || (map.size() == 1 && null != MapUtils.getFirst(map).getValue()))) {
                return map;
            }

            map = parseKeyValue(string, ",", ":");
            if (null != map && (map.size() > 1 || (map.size() == 1 && null != MapUtils.getFirst(map).getValue()))){
                return map;
            }
        }
        return convertIfNecessary(value);
    }

    /**
     * 解析键值对格式的字符串为 Map。
     *
     * @param str      字符串
     * @param entrySep 条目分隔符
     * @param kvSep    键值分隔符
     * @return 解析后的 Map，如果无有效条目则返回 null
     */
    private Map<String, String> parseKeyValue(String str, String entrySep, String kvSep) {
        String[] entries = str.split(entrySep);
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : entries) {
            int idx = entry.indexOf(kvSep);
            if (idx > -1) {
                result.put(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim());
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 解析键值对中的值部分，支持嵌套 Map/List。
     *
     * @param s     完整字符串
     * @param index 值部分的起始索引（分隔符之后）
     * @return 解析后的值对象
     */
    private Object createValue(String s, int index) {
        String keyValue = s.substring(index + 1).trim();

        if (keyValue.startsWith(SYMBOL_LEFT_BIG_PARENTHESES)) {
            return this.convert(keyValue);
        }
        if (keyValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET)) {
            return ListTypeConverter.INSTANCE.convert(keyValue);
        }
        return keyValue;
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Map.class
     */
    @Override
    public Class<Map> getType() {
        return Map.class;
    }
}
