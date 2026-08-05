package com.chua.common.support.config.source;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 系统属性源类。
 * <p>
 * 该类用于从 JVM 的系统属性中获取配置信息。
 * 它支持通过指定的 key 来获取对应的属性值。
 *
 * @author CH
 * @since 2023-01-01
 */
public class SystemPropertySource extends AbstractPropertySource {

    /**
     * 无参构造函数。
     * 初始化时设置源名称为 "system-property"。
     */
    public SystemPropertySource() {
        super("system-property");
    }

    /**
     * 获取原始属性值。
     * 该方法通过 JVM 的 System.getProperty 方法获取指定 key 的属性值。
     *
     * @param key 要获取的属性键
     * @return 对应的属性值，如果不存在则返回 null
     */
    @Override
    protected Object getRawProperty(String key) {
        return System.getProperty(key);
    }

    /**
     * 获取整个属性源对象。
     * 该方法将当前 JVM 的所有系统属性转换为 HashMap 形式并返回。
     *
     * @return 包含所有系统属性的 HashMap 对象
     */
    @SuppressWarnings("all")
    @Override
    protected Object getSource() {
        Properties properties = System.getProperties();
        return new HashMap<String, Object>((Map) properties);
    }
}
