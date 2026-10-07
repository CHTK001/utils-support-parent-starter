package com.chua.common.support.content;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.reflection.ReflectUtils;

import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 通用内容上下文的 Map 底座实现。
 *
 * <p>{@link ObjectContent} 开箱即用的实现：变量落在内部有序表中，
 * 另可带一份输入对象（{@link Map} 或任意业务对象）参与路径下探。
 * 读取顺序：先整串精确匹配内部表，再看输入，最后按 {@code .} 逐段下探。
 * 类型转换走接口默认方法（内部用 Converter），本类只管存取。</p>
 *
 * <p>线程不安全：单次流程内单线程使用，用完即弃。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class DefaultObjectContent implements ObjectContent {

    /**
     * 路径分段符
     */
    public static final char SEPARATOR = '.';

    /**
     * 内部变量表
     */
    private final Map<String, Object> values = Wraps.linkedMap();

    /**
     * 输入对象，可为 Map 或任意业务对象，可为 null
     */
    private final Object input;

    /**
     * 空上下文。
     */
    public DefaultObjectContent() {
        this(null);
    }

    /**
     * 用初始变量构造。
     *
     * @param values 初始变量，可为 null
     */
    public DefaultObjectContent(Map<String, Object> values) {
        this(values, null);
    }

    /**
     * 用初始变量与输入对象构造。
     *
     * @param values 初始变量，可为 null
     * @param input  输入对象，可为 null
     */
    public DefaultObjectContent(Map<String, Object> values, Object input) {
        if (values != null) {
            this.values.putAll(values);
        }
        this.input = input;
    }

    /**
     * 用初始变量构造。
     *
     * @param values 初始变量，可为 null
     * @return 上下文
     */
    public static DefaultObjectContent of(Map<String, Object> values) {
        return new DefaultObjectContent(values, null);
    }

    /**
     * 用初始变量与输入对象构造。
     *
     * @param values 初始变量，可为 null
     * @param input  输入对象，可为 null
     * @return 上下文
     */
    public static DefaultObjectContent of(Map<String, Object> values, Object input) {
        return new DefaultObjectContent(values, input);
    }

    @Override
    public Object get(String name) {
        Objects.requireNonNull(name, "name 不能为 null");
        if (values.containsKey(name)) {
            return values.get(name);
        }
        if (input instanceof Map<?, ?> map && map.containsKey(name)) {
            return map.get(name);
        }
        return resolvePath(name);
    }

    @Override
    public boolean contains(String name) {
        Objects.requireNonNull(name, "name 不能为 null");
        if (values.containsKey(name)
                || (input instanceof Map<?, ?> map && map.containsKey(name))) {
            return true;
        }
        return resolvePath(name) != null;
    }

    @Override
    public Object require(String name) {
        if (!contains(name)) {
            throw new NoSuchElementException("必需变量不存在: " + name);
        }
        return get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String name, T defaultValue) {
        Object value = get(name);
        return value == null ? defaultValue : (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ObjectContent> T set(String name, Object value) {
        values.put(Objects.requireNonNull(name, "name 不能为 null"), value);
        return (T) this;
    }

    /**
     * 内部变量表的只读快照。
     *
     * @return 不可变变量表
     */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(Wraps.linkedMap(values));
    }

    /**
     * 逐段下探读取。
     *
     * @param path 路径
     * @return 变量值；无分隔符或任一段缺失时返回 null
     */
    private Object resolvePath(String path) {
        int index = path.indexOf(SEPARATOR);
        if (index < 0) {
            return null;
        }
        Object node = firstSegment(path.substring(0, index));
        int from = index + 1;
        while (node != null && from <= path.length()) {
            int next = path.indexOf(SEPARATOR, from);
            String segment = next < 0 ? path.substring(from) : path.substring(from, next);
            node = segment(node, segment);
            from = next < 0 ? path.length() + 1 : next + 1;
        }
        return node;
    }

    /**
     * 路径首段：内部表优先，其次输入对象。
     *
     * @param segment 首段名
     * @return 首段值；缺失时返回 null
     */
    private Object firstSegment(String segment) {
        if (values.containsKey(segment)) {
            return values.get(segment);
        }
        if (input instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        if (input != null) {
            try {
                return ReflectUtils.getField(input, segment);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 逐段下探一层。
     *
     * @param node    当前节点
     * @param segment 段名
     * @return 下一层节点；不支持下探时返回 null
     */
    private static Object segment(Object node, String segment) {
        if (node instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        try {
            return ReflectUtils.getField(node, segment);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
