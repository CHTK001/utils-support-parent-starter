package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 树形视图解析器，将嵌套数据渲染为终端树。
 * <p>支持 {@link Map} 和 POJO 的嵌套结构。</p>
 *
 * <pre>{@code
 * /
 * ├── home
 * │   ├── user
 * │   └── logs
 * └── etc
 *     └── config
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tree")
public class TreeViewParser implements ViewParser {

    @Override
    /** Support */
    public boolean support(Object data) {
        if (data == null) {
            return false;
        }
        Class<?> type = data.getClass();
        if (type.getName().startsWith("java.") && !Map.class.isAssignableFrom(type)) {
            return false;
        }
        return data instanceof Map || data instanceof Iterable || type.isArray() || isNestedPojo(data);
    }

    @Override
@SuppressWarnings("unchecked")
    /** Render */
    public String render(Object data) {
        StringBuilder sb = new StringBuilder();
        if (data instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) data;
            List<Object> keys = new ArrayList<>(map.keySet());
            for (int i = 0; i < keys.size(); i++) {
                Object key = keys.get(i);
                Object val = map.get(key);
                boolean last = i == keys.size() - 1;
                renderNode(sb, String.valueOf(key), val, "", last);
            }
        } else if (data instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            ((Iterable<Object>) data).forEach(list::add);
            for (int i = 0; i < list.size(); i++) {
                renderNode(sb, String.valueOf(list.get(i)), null, "", i == list.size() - 1);
            }
        } else if (data.getClass().isArray()) {
            Object[] arr = (Object[]) data;
            for (int i = 0; i < arr.length; i++) {
                renderNode(sb, String.valueOf(arr[i]), null, "", i == arr.length - 1);
            }
        } else {
            renderNode(sb, data.toString(), null, "", true);
        }
        return sb.toString();
    }

    /**
     * 递归渲染树节点。
     */
    private void renderNode(StringBuilder sb, String name, Object value, String prefix, boolean last) {
        sb.append(prefix);
        sb.append(last ? "└── " : "├── ");
        sb.append(name);

        if (value == null) {
            sb.append('\n');
            return;
        }

        List<Map.Entry<Object, Object>> childList = toChildren(value);
        if (childList == null) {
            // 叶子节点，显示值
            sb.append(": ").append(value).append('\n');
            return;
        }

        sb.append('\n');
        String childPrefix = prefix + (last ? "    " : "│   ");
        for (int i = 0; i < childList.size(); i++) {
            Map.Entry<Object, Object> child = childList.get(i);
            renderNode(sb, String.valueOf(child.getKey()), child.getValue(), childPrefix, i == childList.size() - 1);
        }
    }

    /**
     * 将值转为子节点列表。Map 的每个条目编码为 {@link Map.Entry} 以携带 key+value。
     */
    private static List<Map.Entry<Object, Object>> toChildren(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            return new ArrayList<>(map.entrySet());
        }
        if (value instanceof Iterable) {
            List<Map.Entry<Object, Object>> result = new ArrayList<>();
            for (Object item : (Iterable<Object>) value) {
                result.add(new AbstractMap.SimpleEntry<>(item, null));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Map.Entry<Object, Object>> result = new ArrayList<>();
            for (Object item : (Object[]) value) {
                result.add(new AbstractMap.SimpleEntry<>(item, null));
            }
            return result;
        }
        return null;
    }

    /**
     * 判断是否为嵌套 POJO。
     */
    private static boolean isNestedPojo(Object data) {
        Class<?> type = data.getClass();
        if (type.getName().startsWith("java.")) {
            return false;
        }
        java.lang.reflect.Field[] fields = type.getDeclaredFields();
        for (java.lang.reflect.Field f : fields) {
            Class<?> ft = f.getType();
            if (!ft.getName().startsWith("java.") || Map.class.isAssignableFrom(ft)
                    || Iterable.class.isAssignableFrom(ft) || ft.isArray()) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 20;
    }
}
