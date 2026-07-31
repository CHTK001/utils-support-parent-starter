package com.chua.common.support.config.source;

import com.chua.common.support.converter.Converter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;


/**
 * 属性源接口定义
 * <p>
 * 支持多种格式的键名解析
 * <ul>
 *     <li>dotted.key.name: xx.xx.xx</li>
 *     <li>indexed.key[0]: xx.xx[0]</li>
 *     <li>UPPER_SNAKE_CASE: SERVER_PORT</li>
 *     <li>kebab-case: server-port</li>
 *     <li>camelCase: serverPort</li>
 * </ul>
 *
 * @author CH
 * @since 2023-08-01
 */
public interface PropertySource {

    /**
     * 空属性源实例
     */
    PropertySource EMPTY = new EmptyPropertySource();

    /**
     * 驼峰命名正则匹配模式
     */
    Pattern CAMEL_CASE_PATTERN = Pattern.compile("([a-z])([A-Z])");

    /**
     * 根据指定键获取属性值
     *
     * @param key 属性键，例如 "database.url" 或 "server.port"
     * @return 属性值，如果不存在则返回 null
     */
    Object getProperty(String key);

    /**
     * 获取属性源名称
     *
     * @return 属性源名称
     */
    String getName();

    /**
     * 根据指定键获取属性值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值或默认值
     */
    default Object getProperty(String key, Object defaultValue) {
        Object value = getProperty(key);
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    /**
     * 获取字符串类型的属性值
     *
     * @param key 属性键
     * @return 字符串类型的属性值
     */
    default String getString(String key) {
        return getString(key, null);
    }

    /**
     * 获取字符串类型的属性值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 字符串类型的属性值或默认值
     */
    default String getString(String key, String defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, String.class, defaultValue);
    }

    /**
     * 获取整数类型的属性值
     *
     * @param key 属性键
     * @return 整数类型的属性值
     */
    default Integer getInteger(String key) {
        return getInteger(key, null);
    }

    /**
     * 获取整数类型的属性值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 整数类型的属性值或默认值
     */
    default Integer getInteger(String key, Integer defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, Integer.class, defaultValue);
    }

    /**
     * 获取长整型属性的值
     *
     * @param key 属性键
     * @return 长整型属性的值
     */
    default Long getLong(String key) {
        return getLong(key, null);
    }

    /**
     * 获取长整型属性的值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 长整型属性的值或默认值
     */
    default Long getLong(String key, Long defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, Long.class, defaultValue);
    }

    /**
     * 获取双精度浮点型属性的值
     *
     * @param key 属性键
     * @return 双精度浮点型属性的值
     */
    default Double getDouble(String key) {
        return getDouble(key, null);
    }

    /**
     * 获取双精度浮点型属性的值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 双精度浮点型属性的值或默认值
     */
    default Double getDouble(String key, Double defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, Double.class, defaultValue);
    }

    /**
     * 获取布尔型属性的值
     *
     * @param key 属性键
     * @return 布尔型属性的值
     */
    default Boolean getBoolean(String key) {
        return getBoolean(key, null);
    }

    /**
     * 获取布尔型属性的值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 布尔型属性的值或默认值
     */
    default Boolean getBoolean(String key, Boolean defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, Boolean.class, defaultValue);
    }

    /**
     * 获取 BigDecimal 类型的属性值
     *
     * @param key 属性键
     * @return BigDecimal 类型的属性值
     */
    default BigDecimal getBigDecimal(String key) {
        return getBigDecimal(key, null);
    }

    /**
     * 获取 BigDecimal 类型的属性值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return BigDecimal 类型的属性值或默认值
     */
    default BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, BigDecimal.class, defaultValue);
    }

    /**
     * 获取 BigInteger 类型的属性值
     *
     * @param key 属性键
     * @return BigInteger 类型的属性值
     */
    default BigInteger getBigInteger(String key) {
        return getBigInteger(key, null);
    }

    /**
     * 获取 BigInteger 类型的属性值，若不存在则返回默认值
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return BigInteger 类型的属性值或默认值
     */
    default BigInteger getBigInteger(String key, BigInteger defaultValue) {
        Object value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Converter.convertIfNecessary(value, BigInteger.class, defaultValue);
    }

    /**
     * 获取字符串列表类型的属性值
     *
     * @param key 属性键
     * @return 字符串列表类型的属性值
     */
    @SuppressWarnings("unchecked")
    default List<String> getList(String key) {
        Object value = getProperty(key);
        if (value == null) {
            return null;
        }
        return Converter.convertIfNecessary(value, List.class);
    }

    /**
     * 将当前属性源转换为 Map 格式
     * <p>
     * 支持多种实现类型转换：
     * - MapPropertySource 直接提取底层 Map
     * - PropertiesPropertySource 提取 Properties 内容
     * - PropertiesMutiPropertySource 遍历多个 Map 合并
     * - SystemPropertySource 提取系统属性
     * - SystemEnvironmentPropertySource 提取环境变量
     *
     * @return 转换后的 Map，如果为空则返回空 Map
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    default Map<String, Object> toMap() {
        if (this == EMPTY) { return Map.of(); }

        // MapPropertySource: 直接返回内部 LiteRawMap（无拷贝）
        if (this instanceof MapPropertySource mps) {
            return mps.getProperties();
        }

        // PropertiesPropertySource
        if (this instanceof PropertiesPropertySource pps) {
            Properties props = pps.getProperties();
            if (props == null || props.isEmpty()) return Map.of();
            Map<String, Object> result = new HashMap<>(props.size());
            for (String name : props.stringPropertyNames()) {
                result.put(name, props.getProperty(name));
            }
            return result;
        }

        // PropertiesMutiPropertySource
        if (this instanceof PropertiesMutiPropertySource mps) {
            Iterable<?> iterable = mps.getProperties();
            if (iterable == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : iterable) {
                if (item instanceof Map map) {
                    for (Object entryObj : map.entrySet()) {
                        Map.Entry entry = (Map.Entry) entryObj;
                        if (entry.getKey() != null) {
                            result.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                }
            }
            return result;
        }

        // SystemPropertySource
        if (this instanceof SystemPropertySource) {
            Properties props = System.getProperties();
            if (props == null || props.isEmpty()) return Map.of();
            Map<String, Object> result = new HashMap<>(props.size());
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }

        // SystemEnvironmentPropertySource
        if (this instanceof SystemEnvironmentPropertySource) {
            Map<String, String> env = System.getenv();
            return env != null ? new HashMap<>(env) : Map.of();
        }

        return Map.of();
    }

    /**
     * 规范化键名
     * <p>
     * 将不同格式的键名统一转换为下划线分隔的小写形式
     * <ul>
     *     <li>SERVER_PORT -> server_port</li>
     *     <li>server-port -> server_port</li>
     *     <li>serverPort -> server_port</li>
     * </ul>
     *
     * @param key 原始键名
     * @return 规范化后的键名
     */
    static String normalizeKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        // 1. 处理驼峰命名：serverPort -> server_Port
        String normalized = CAMEL_CASE_PATTERN.matcher(key).replaceAll("$1_$2");
        // 2. 转换为小写并将连字符替换为下划线
        normalized = normalized.toLowerCase().replace('-', '_');
        // 3. 去除连续的下划线
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized;
    }

    /**
     * 生成键名的所有变体
     * <p>
     * 基于规范化后的键名生成多种格式：
     * - 原始键名
     * - snake_case
     * - kebab-case
     * - UPPER_SNAKE_CASE
     * - camelCase
     *
     * @param key 原始键名
     * @return 包含所有变体的字符串数组
     */
    static String[] generateKeyVariants(String key) {
        if (key == null || key.isEmpty()) {
            return new String[]{key};
        }

        // 首先规范化键名
        String normalized = normalizeKey(key);
        String[] parts = normalized.split("_");

        // 初始化用于构建不同格式 StringBuilder
        StringBuilder kebabCase = new StringBuilder();
        StringBuilder snakeCase = new StringBuilder();
        StringBuilder camelCase = new StringBuilder();
        StringBuilder upperSnakeCase = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i > 0 || !kebabCase.isEmpty()) {
                kebabCase.append('-');
                snakeCase.append('_');
                upperSnakeCase.append('_');
            }
            kebabCase.append(part);
            snakeCase.append(part);
            upperSnakeCase.append(part.toUpperCase());

            if (camelCase.isEmpty()) {
                camelCase.append(part);
            } else {
                camelCase.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    camelCase.append(part.substring(1));
                }
            }
        }

        return new String[]{
                key,
                snakeCase.toString(),
                kebabCase.toString(),
                upperSnakeCase.toString(),
                camelCase.toString()
        };
    }

    /**
     * 获取属性源的优先级
     *
     * @return 优先级数值
     */
    default int getPriority(){
        return 0;
    }

    /**
     * 刷新属性源
     */
    default void refresh() {

    }
}
