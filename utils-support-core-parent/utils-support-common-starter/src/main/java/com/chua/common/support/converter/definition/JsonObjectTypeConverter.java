package com.chua.common.support.converter.definition;


import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* JsonObject 类型转换器（以 Map 作为底层存储类型）。
* <p>将各种类型的值转换为 {@link Map}，支持以下输入类型：</p>
* <ul>
*   <li>{@link String} — 解析 JSON 对象格式的字符串（{key:value, ...}），自动去除键值引号</li>
*   <li>{@code byte[]} — 先转为 String 再解析</li>
*   <li>{@link Map} — 转为新的 HashMap</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class JsonObjectTypeConverter implements TypeConverter<Map> {
    @Override
    /** 获取Type */
    public Class<Map> getType() {
        return Map.class;
    }

    /**
    * 将给定值转换为 Map（JSON 对象）。
    *
    * @param value 源值
    * @return Map 值，如果无法转换则返回 null
    */
    @Override
    public Map convert(Object value) {
        if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("{") && str.endsWith("}")) {
                String inner = str.substring(1, str.length() - 1);
                Map<String, String> result = new HashMap<>();
                if (inner.trim().isEmpty()) {
                    return result;
                }
                String[] pairs = inner.split(",");
                for (String pair : pairs) {
                    int colonIdx = pair.indexOf(':');
                    if (colonIdx > -1) {
                        String k = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
                        String v = pair.substring(colonIdx + 1).trim().replaceAll("^\"|\"$", "");
                        result.put(k, v);
                    }
                }
                return result;
            }
        }

        if (value instanceof byte[]) {
            return convert(new String((byte[]) value));
        }

        if (value instanceof Map) {
            return new HashMap((Map) value);
        }

        return null;
    }
}
