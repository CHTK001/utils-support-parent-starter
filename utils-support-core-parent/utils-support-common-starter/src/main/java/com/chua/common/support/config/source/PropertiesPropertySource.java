package com.chua.common.support.config.source;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 基于 Java Properties 对象实现的属性源类。
 * <p>
 * 支持多种格式的键名解析：
 * <ul>
 *     <li>点分格式 (dot notation): 例如 xx.xx.xx</li>
 *     <li>数组索引格式: 例如 xx.xx[0]</li>
 *     <li>常量格式: 例如 SERVER_PORT</li>
 *     <li>短横线格式 (kebab-case): 例如 server-port</li>
 *     <li>驼峰格式 (camelCase): 例如 serverPort</li>
 * </ul>
 *
 * @author CH
 * @since 2023-09-07
 */
@Getter
@SuppressWarnings("unchecked")
public class PropertiesPropertySource extends AbstractPropertySource {

    /**
     * 存储实际配置属性的 Properties 对象。
     * <p>
     * 示例用法：
     * <pre>
     * Properties props = new Properties();
     * props.setProperty("key1", "value1");
     * props.setProperty("key2", "value2");
     * </pre>
     */
    private final Properties properties;

    /**
     * 构造一个新的属性源实例。
     *
     * @param name         属性源的名称，用于标识该源。
     * @param properties   包含配置属性的 Properties 对象。
     */
    public PropertiesPropertySource(String name, Properties properties) {
        super(name);
        this.properties = properties;
    }

    @Override
    /** 获取RawProperty */
    protected Object getRawProperty(String key) {
        if (properties == null) {
            return null;
        }
        return properties.getProperty(key);
    }

    @Override
    /** 获取Source */
    protected Object getSource() {
        if (properties == null) {
            return null;
        }
        // 将 Properties 转换为 Map 返回
        return new HashMap<String, Object>((Map) properties);
    }
}