package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Field;
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

    private static final int MIN_WIDTH = 40;
    private static final int PADDING = 2;

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

    @Override
    public String render(Object data) {
        Map<String, String> kv = toKeyValue(data);
        if (kv.isEmpty()) {
            return "(empty)";
        }

        int maxKeyLen = kv.keySet().stream().mapToInt(String::length).max().orElse(0);
        int maxValLen = kv.values().stream().mapToInt(String::length).max().orElse(0);
        int titleLen = data instanceof Map ? "Map".length() : data.getClass().getSimpleName().length();
        int innerWidth = Math.max(maxKeyLen + 3 + maxValLen, titleLen + 2);
        int width = Math.max(innerWidth + 2, MIN_WIDTH);

        StringBuilder sb = new StringBuilder();
        // 顶线
        sb.append('┌').append("─".repeat(width - 2)).append('┐').append('\n');
        // 标题
        String title = data instanceof Map ? "Map" : data.getClass().getSimpleName();
        int titleStart = (width - 2 - title.length()) / 2;
        sb.append('│').append(" ".repeat(titleStart)).append(title);
        sb.append(" ".repeat(width - 2 - titleStart - title.length())).append('│').append('\n');
        // 分隔线
        sb.append('├').append("─".repeat(width - 2)).append('┤').append('\n');
        // 内容
        for (var entry : kv.entrySet()) {
            sb.append('│');
            String key = entry.getKey();
            String val = entry.getValue();
            sb.append(' ').append(key);
            sb.append(" ".repeat(maxKeyLen - key.length() + 1));
            sb.append(val);
            int remain = width - 3 - maxKeyLen - 1 - val.length();
            if (remain > 0) {
                sb.append(" ".repeat(remain));
            }
            sb.append('│').append('\n');
        }
        // 底线
        sb.append('└').append("─".repeat(width - 2)).append('┘');
        return sb.toString();
    }

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
            for (Field f : type.getDeclaredFields()) {
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

    @Override
    public int getOrder() {
        return 15;
    }
}
