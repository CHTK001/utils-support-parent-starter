package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.*;

/**
 * 键值对视图解析器，将 {@link Map} 渲染为 {@code key: value} 格式。
 * <p>也支持 POJO — 反射提取所有字段展示。</p>
 *
 * <pre>{@code
 * name:    foo
 * size:    1024
 * isDir:   true
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kv")
public class KeyValueViewParser implements ViewParser {

    @Override
    public boolean support(Object data) {
        return data instanceof Map || isPojo(data);
    }

    @Override
@SuppressWarnings({"unchecked"})
    public String render(Object data) {
        Map<String, String> map = toKeyValue(data);
        if (map.isEmpty()) {
            return "(empty)";
        }
        int maxKeyLen = map.keySet().stream().mapToInt(String::length).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (var entry : map.entrySet()) {
            sb.append(entry.getKey());
            for (int i = entry.getKey().length(); i < maxKeyLen + 2; i++) {
                sb.append(' ');
            }
            sb.append(entry.getValue()).append('\n');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 将对象转为键值对映射。
     */
    private static Map<String, String> toKeyValue(Object data) {
        if (data instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) data;
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
        Map<String, String> result = new LinkedHashMap<>();
        Class<?> type = data.getClass();
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(data);
                    result.put(f.getName(), val != null ? val.toString() : "null");
                } catch (Exception e) {
                    result.put(f.getName(), "?");
                }
            }
            type = type.getSuperclass();
        }
        return result;
    }

    /**
     * 判断是否为 POJO（非简单类型、非集合/数组/Map）。
     */
    private static boolean isPojo(Object data) {
        if (data == null) {
            return false;
        }
        Class<?> type = data.getClass();
        if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
            return false;
        }
        if (Map.class.isAssignableFrom(type)) {
            return false;
        }
        if (type.getName().startsWith("java.")) {
            return false;
        }
        return true;
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
