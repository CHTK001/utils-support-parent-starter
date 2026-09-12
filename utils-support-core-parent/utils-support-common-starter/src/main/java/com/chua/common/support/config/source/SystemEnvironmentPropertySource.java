package com.chua.common.support.config.source;


/**
* 系统环境变量属性源类。
* <p>
* 该类用于从系统环境变量中获取配置属性值。
* 支持多种键名转换策略，以兼容不同格式的属性键。
* 示例转换规则如下：
* <ul>
*     <li>server.port -> SERVER_PORT</li>
*     <li>server-port -> SERVER_PORT</li>
* </ul>
*
* @author CH
* @since 2023-01-01
 */
public class SystemEnvironmentPropertySource extends AbstractPropertySource {

    /**
    * 构造函数。
    * 初始化属性源名称为 "system-env"。
     */
    public SystemEnvironmentPropertySource() {
        super("system-env");
    }

    /**
    * 获取原始属性值。
    * 直接调用系统方法获取环境变量值。
    *
    * @param key 属性键
    * @return 对应的环境变量值，如果不存在则返回 null
     */
    @Override
    protected Object getRawProperty(String key) {
        return System.getenv(key);
    }

    /**
    * 获取属性源对象。
    * 返回当前系统的所有环境变量映射。
    *
    * @return 环境变量映射对象
     */
    @Override
    protected Object getSource() {
        return System.getenv();
    }

    /**
    * 根据给定的键获取属性值。
    * 该方法实现了多层级的键名查找策略：
    * 1. 首先尝试直接使用传入的键名查找。
    * 2. 其次尝试通过生成的变体键名查找（如替换分隔符等）。
    * 3. 最后将键名标准化为大写下划线格式进行查找。
    *
    * @param key 属性键
    * @return 找到的属性值，如果未找到则返回 null
     */
    @Override
    public Object getProperty(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // 1. 尝试直接使用原始键名获取属性值
        Object value = getRawProperty(key);
        if (value != null) {
            return value;
        }

        // 2. 尝试使用生成的键变体列表获取属性值
        String[] variants = PropertySource.generateKeyVariants(key);
        for (String variant : variants) {
            if (variant != null && !variant.equals(key)) {
                value = getRawProperty(variant);
                if (value != null) {
                    return value;
                }
            }
        }

        // 3. 尝试使用标准化后的键名（全大写、下划线分隔）获取属性值
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        return getRawProperty(envKey);
    }
}