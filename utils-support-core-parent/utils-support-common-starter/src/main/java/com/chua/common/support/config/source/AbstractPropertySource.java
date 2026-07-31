package com.chua.common.support.config.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抽象属性源类。
 * <p>
 * 该基类提供了获取属性的基本逻辑，包括直接获取、嵌套属性解析以及键名变体匹配。
 * 具体实现需要子类提供原始数据源 {@link #getRawProperty(String)} 和完整的数据结构 {@link #getSource()}。
 *
 * @author CH
 * @since 2023-08-01
 */
@Getter
@RequiredArgsConstructor
public abstract class AbstractPropertySource implements PropertySource {

    /**
     * 用于匹配数组索引的正则表达式模式。
     * 例如：key[0]
     */
    private static final Pattern ARRAY_INDEX_PATTERN = Pattern.compile("(.+?)\\[(\\d+)]");

    /**
     * 属性源的名称标识。
     */
    private final String name;

    /**
     * 根据给定的键获取原始属性值。
     *
     * @param key 属性键
     * @return 属性值，如果不存在则返回 null
     */
    protected abstract Object getRawProperty(String key);

    /**
     * 获取完整的属性源对象。
     *
     * @return 通常是一个 Map 或 Properties 对象
     */
    protected abstract Object getSource();

    @Override
    public Object getProperty(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // 1. 检查键是否包含点号，如果是则尝试解析嵌套属性
        if (key.contains(".")) {
            return getNestedProperty(key);
        }

        // 2. 直接尝试使用原始键获取属性值
        Object value = getRawProperty(key);
        if (value != null) {
            return value;
        }

        // 3. 生成键的变体列表（如 server_port, server-port, serverPort, SERVER_PORT）并依次尝试获取
        String[] variants = PropertySource.generateKeyVariants(key);
        for (String variant : variants) {
            if (variant != null && !variant.equals(key)) {
                value = getRawProperty(variant);
                if (value != null) {
                    return value;
                }
            }
        }

        return null;
    }

    /**
     * 获取嵌套属性值。
     * <p>
     * 支持以下路径格式：
     * <ul>
     *     <li>database.url - 获取嵌套对象的属性</li>
     *     <li>servers[0].host - 获取列表中元素的属性</li>
     *     <li>app.servers[0].port - 深层嵌套及列表组合</li>
     * </ul>
     * <p>
     * 在查找过程中会自动尝试键名变体（如 server_port = server-port = serverPort = SERVER_PORT）。
     *
     * @param key 嵌套属性路径
     * @return 找到的属性值，如果未找到则返回 null
     */
    @SuppressWarnings("unchecked")
    protected Object getNestedProperty(String key) {
        Object source = getSource();
        if (!(source instanceof Map)) {
            return null;
        }

        // 将路径字符串分割为部分数组
        String[] parts = splitKeyPath(key);
        Object current = source;

        for (String part : parts) {
            if (current == null) {
                return null;
            }

            // 检查当前部分是否包含数组索引语法
            Matcher matcher = ARRAY_INDEX_PATTERN.matcher(part);
            if (matcher.matches()) {
                String keyPart = matcher.group(1);
                int index = Integer.parseInt(matcher.group(2));

                // 在当前层级通过变体匹配获取子对象
                current = getFromSourceWithVariants(current, keyPart);
                if (current == null) {
                    return null;
                }

                // 从列表或数组中根据索引获取元素
                current = getFromList(current, index);
            } else {
                // 直接通过变体匹配获取下一层级的对象
                current = getFromSourceWithVariants(current, part);
            }
        }

        return current;
    }

    /**
     * 将属性路径字符串分割成部分数组。
     * <p>
     * 不会拆分方括号内的内容，仅按点号分割。
     * 例如：app.servers[0].host -> ["app", "servers[0]", "host"]
     *
     * @param key 属性路径字符串
     * @return 分割后的字符串数组
     */
    private String[] splitKeyPath(String key) {
        return key.split("\\.");
    }

    /**
     * 从源对象中根据键获取值。
     * 仅当源对象为 Map 类型时有效。
     *
     * @param source 源对象
     * @param key    键
     * @return 对应的值，如果类型不匹配则返回 null
     */
    @SuppressWarnings("unchecked")
    private Object getFromSource(Object source, String key) {
        if (source instanceof Map) {
            return ((Map<String, Object>) source).get(key);
        }
        return null;
    }

    /**
     * 从源对象中根据键获取值，并自动尝试键的变体形式。
     *
     * @param source 源对象（必须是 Map 类型）
     * @param key    原始键
     * @return 找到的值，如果未找到则返回 null
     */
    @SuppressWarnings("unchecked")
    private Object getFromSourceWithVariants(Object source, String key) {
        if (!(source instanceof Map)) {
            return null;
        }

        Map<String, Object> map = (Map<String, Object>) source;

        // 首先尝试直接使用原始键获取
        Object value = map.get(key);
        if (value != null) {
            return value;
        }

        // 尝试使用键的变体列表进行获取
        String[] variants = PropertySource.generateKeyVariants(key);
        for (String variant : variants) {
            value = map.get(variant);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    /**
     * 从列表或数组中根据索引获取元素。
     *
     * @param source 源对象（List 或数组）
     * @param index  索引位置
     * @return 对应索引的元素，如果索引越界或类型不匹配则返回 null
     */
    @SuppressWarnings("unchecked")
    private Object getFromList(Object source, int index) {
        if (source instanceof List) {
            List<Object> list = (List<Object>) source;
            if (index >= 0 && index < list.size()) {
                return list.get(index);
            }
        } else if (source.getClass().isArray()) {
            Object[] array = (Object[]) source;
            if (index >= 0 && index < array.length) {
                return array[index];
            }
        }
        return null;
    }
}
