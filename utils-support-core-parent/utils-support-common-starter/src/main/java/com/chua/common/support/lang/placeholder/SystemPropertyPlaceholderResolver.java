package com.chua.common.support.lang.placeholder;


/**
* 系统属性占位符解析器。
* <p>
* 该类实现了 {@link PlaceholderResolver} 接口，用于从 Java 系统属性或操作系统环境变量中解析占位符值。
* 其解析逻辑为：首先尝试获取系统属性（System Property），如果未找到，则回退到操作系统环境变量（Environment Variable）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class SystemPropertyPlaceholderResolver implements PlaceholderResolver {

    /**
    * 解析给定的占位符名称。
    * <p>
    * 该方法首先尝试通过 {@link System#getProperty(String)} 获取对应的系统属性值。
    * 如果系统属性不存在（返回 null），则进一步尝试通过 {@link System#getenv(String)} 获取对应的环境变量值。
    * 若两者均未找到或发生异常，则返回 null。
    * </p>
    *
    * @param placeholderName 要解析的占位符名称，通常对应于属性键或环境变量名。
    * @return 解析后的值；如果找不到对应的属性或环境变量，或者发生错误，则返回 null。
    */
    @Override
    public String resolvePlaceholder(String placeholderName) {
        try {
            // 优先尝试从系统属性中获取值
            String propVal = System.getProperty(placeholderName);
            
            // 如果系统属性为空，则回退到操作系统环境变量中查找
            if (propVal == null) {
                propVal = System.getenv(placeholderName);
            }
            return propVal;
        } catch (Throwable ex) {
            // 捕获任何可能的异常并返回 null，确保解析过程的健壮性
            return null;
        }
    }

    /**
    * 获取指定键的属性值。
    * <p>
    * 此方法是接口的便捷方法，直接委托给 {@link #resolvePlaceholder(String)} 进行实际解析。
    * </p>
    *
    * @param key 要获取的属性键。
    * @return 对应的属性值；如果不存在则返回 null。
    */
    @Override
    public String getProperty(String key) {
        return resolvePlaceholder(key);
    }
}
