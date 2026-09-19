package com.chua.common.support.lang.view;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡片视图解析器，将单对象渲染为带标题的分隔卡片。
 * <pre>{@code
 * ┌─────────────────────────────┐
 * │  User Profile               │
 * ├─────────────────────────────┤
 * │ name:     alice             │
 * │ role:     admin             │
 * │ created:  2024-01-15        │
 * └─────────────────────────────┘
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("card")
public class CardViewParser implements ViewParser {

    /** 卡片最小宽度 */
    private static final int MIN_WIDTH = 40;

    /** 单元格左右内边距 */
    private static final int PADDING = 2;

    /** 键与值之间的最小间隔列数 */
    private static final int KEY_VALUE_GAP = 1;

    /** Map 类型专用标题 */
    private static final String MAP_TITLE = "Map";

    /**
     * 判断是否支持卡片格式渲染。
     * 不支持数组、Iterable 和 java.* 内置类型；Map 仅在非空时支持。
     *
     * @param data 待渲染的数据
     * @return 支持时返回 true
     */
    @Override
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        Class<?> type = data.getClass();
        if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
            return false;
        }
        if (Map.class.isAssignableFrom(type)) {
            return data instanceof Map && !((Map<?, ?>) data).isEmpty();
        }
        if (type.getName().startsWith("java.")) {
            return false;
        }
        return true;
    }

    /**
     * 将数据渲染为卡片格式（带边框的键值对展示）。
     *
     * @param data 待渲染的数据
     * @return 卡片字符串；空数据返回 {@link ViewFormatter#EMPTY_PLACEHOLDER}
     */
    @Override
    public String render(Object data) {
        Map<String, String> kv = toKeyValue(data);
        if (kv.isEmpty()) {
            return ViewFormatter.EMPTY_PLACEHOLDER;
        }

        int maxKeyLen = kv.keySet().stream().mapToInt(String::length).max().orElse(0);
        int maxValLen = kv.values().stream().mapToInt(String::length).max().orElse(0);
        String title = data instanceof Map ? MAP_TITLE : data.getClass().getSimpleName();
        int innerWidth = Math.max(maxKeyLen + KEY_VALUE_GAP + PADDING + maxValLen, title.length() + PADDING);
        int width = Math.max(innerWidth + PADDING, MIN_WIDTH);

        StringBuilder sb = new StringBuilder();
        // 顶线
        sb.append('┌').append("─".repeat(width - 2)).append('┐').append('\n');
        // 标题（居中）
        int titleStart = (width - 2 - title.length()) / 2;
        sb.append('│').append(" ".repeat(titleStart)).append(title);
        sb.append(" ".repeat(width - 2 - titleStart - title.length())).append('│').append('\n');
        // 分隔线
        sb.append('├').append("─".repeat(width - 2)).append('┤').append('\n');
        // 内容行
        for (var entry : kv.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            sb.append('│').append(' ').append(key);
            sb.append(" ".repeat(maxKeyLen - key.length() + KEY_VALUE_GAP));
            sb.append(val);
            int remain = width - 4 - maxKeyLen - val.length();
            if (remain > 0) {
                sb.append(" ".repeat(remain));
            }
            sb.append('│').append('\n');
        }
        // 底线
        sb.append('└').append("─".repeat(width - 2)).append('┘');
        return sb.toString();
    }

    /**
     * 将数据转为键值对映射。
     *
     * @param data 待转换的数据
     * @return 有序键值对映射
     */
    private static Map<String, String> toKeyValue(Object data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data instanceof Map) {
            for (var entry : ((Map<Object, Object>) data).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
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
     * 获取解析器顺序，值越大优先级越高。
     *
     * @return 顺序值 15
     */
    @Override
    public int getOrder() {
        return 15;
    }
}
