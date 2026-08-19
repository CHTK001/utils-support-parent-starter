package com.chua.common.support.lang.view;

import com.chua.common.support.spi.annotations.Spi;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(empty)";

    /**
     * 分支节点前缀（└──）
     */
    private static final String LAST_BRANCH = "└── ";

    /**
     * 分支节点前缀（├──）
     */
    private static final String MIDDLE_BRANCH = "├── ";

    /**
     * 父节点为末位时的子节点缩进
     */
    private static final String INDENT_LAST = "    ";

    /**
     * 父节点为非末位时的子节点缩进
     */
    private static final String INDENT_MIDDLE = "│   ";

    /**
     * 叶子节点键值分隔符
     */
    private static final String KEY_VALUE_SEPARATOR = ": ";

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return {@link Map} / {@link Iterable} / 数组 / 嵌套 POJO 返回 true
     */
    @Override
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

    /**
     * 将数据渲染为终端树。
     *
     * @param data 待渲染的数据
     * @return 树形文本；空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @SuppressWarnings("unchecked")
    @Override
    public String render(Object data) {
        if (data == null) {
            return EMPTY_PLACEHOLDER;
        }
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
     *
     * @param sb     输出缓冲区
     * @param name   节点名称
     * @param value  节点值
     * @param prefix 当前缩进前缀
     * @param last   是否为兄弟节点的末位
     */
    private void renderNode(StringBuilder sb, String name, Object value, String prefix, boolean last) {
        sb.append(prefix);
        sb.append(last ? LAST_BRANCH : MIDDLE_BRANCH);
        sb.append(name);

        if (value == null) {
            sb.append('\n');
            return;
        }

        List<Map.Entry<Object, Object>> childList = toChildren(value);
        if (childList == null) {
            // 叶子节点，显示值
            sb.append(KEY_VALUE_SEPARATOR).append(value).append('\n');
            return;
        }

        sb.append('\n');
        String childPrefix = prefix + (last ? INDENT_LAST : INDENT_MIDDLE);
        for (int i = 0; i < childList.size(); i++) {
            Map.Entry<Object, Object> child = childList.get(i);
            renderNode(sb, String.valueOf(child.getKey()), child.getValue(), childPrefix, i == childList.size() - 1);
        }
    }

    /**
     * 将值转为子节点列表。Map 的每个条目编码为 {@link Map.Entry} 以携带 key+value。
     *
     * @param value 待转换的值
     * @return 子节点列表；值为空或非嵌套结构返回 null
     */
    private static List<Map.Entry<Object, Object>> toChildren(Object value) {
        if (value == null) {
            return null;
        }
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
     *
     * @param data 待判断对象
     * @return 存在非 JDK 类型字段返回 true
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

    /**
     * 获取解析器顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 20;
    }
}