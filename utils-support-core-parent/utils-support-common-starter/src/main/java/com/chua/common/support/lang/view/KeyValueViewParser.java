package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 键与值之间的最小间隔列数
     */
    private static final int KEY_VALUE_GAP = 2;

    /**
     * 字段读取失败时的占位字符
     */
    private static final String UNKNOWN_CELL = "?";

    /**
     * 空值占位文本
     */
    private static final String NULL_VALUE = "null";

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@link Map} 或 POJO 返回 true
     */
    @Override
    public boolean support(Object data) {
        return data instanceof Map || isPojo(data);
    }

    /**
     * 将数据渲染为 {@code key: value} 格式。
     *
     * @param data 待渲染的数据
     * @return 键值对文本；空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @SuppressWarnings("unchecked")
    @Override
    public String render(Object data) {
        Map<String, String> map = toKeyValue(data);
        if (map.isEmpty()) {
            return EMPTY_PLACEHOLDER;
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
        // 移除末尾换行
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
            for (Field f : type.getDeclaredFields()) {
                // 跳过静态字段，仅展示实例字段
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(data);
                    result.put(f.getName(), val != null ? val.toString() : NULL_VALUE);
                } catch (Exception e) {
                    result.put(f.getName(), UNKNOWN_CELL);
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

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 10;
    }
}