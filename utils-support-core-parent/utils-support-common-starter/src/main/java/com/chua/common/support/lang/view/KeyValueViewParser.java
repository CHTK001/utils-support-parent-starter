package com.chua.common.support.lang.view;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 键值对视图解析器，将 {@link Map} 渲染为按列对齐的 {@code key   value} 格式。
* <p>也支持 POJO — 反射提取所有字段展示。</p>
*
* <pre>{@code
* name    foo
* size    1024
* isDir   true
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("kv")
public class KeyValueViewParser implements ViewParser {

    /** 键与值之间的最小间隔列数 */
    private static final int KEY_VALUE_GAP = 2;

    @Override
    public boolean support(Object data) {
        return data instanceof Map || isPojo(data);
    }

    @Override
    public String render(Object data) {
        Map<String, String> map = toKeyValue(data);
        if (map.isEmpty()) {
            return ViewFormatter.EMPTY_PLACEHOLDER;
        }
        int maxKeyLen = map.keySet().stream().mapToInt(String::length).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (var entry : map.entrySet()) {
            sb.append(entry.getKey());
            for (int i = entry.getKey().length(); i < maxKeyLen + KEY_VALUE_GAP; i++) {
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
    *
    * @param data 待转换的数据
    * @return 有序键值对映射
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
            for (var f : ViewFormatter.extractFields(type)) {
                try {
                    Object val = ReflectUtils.getField(data, f.getName());
                    result.put(f.getName(), val != null ? val.toString() : "null");
                } catch (Exception e) {
                    result.put(f.getName(), ViewFormatter.UNKNOWN_CELL);
                }
            }
            type = type.getSuperclass();
        }
        return result;
    }

    /**
    * 判断是否为 POJO（非简单类型、非集合/数组/Map）。
    *
    * @param data 待判断对象
    * @return POJO 返回 true
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
