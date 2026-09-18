package com.chua.common.support.lang.bean;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
* Bean 路径表达式接口，通过路径表达式从对象中获取/设置值。
*
* @author CH
* @since 4.0.0.42
* @see com.chua.common.support.lang.json.JsonPath
 */
@Spi
public interface BeanPath {

    /**
     * 获取Instance
     * @return Bean路径 对象
     */
    static BeanPath getInstance() {
        return ServiceProvider.of(BeanPath.class).getPriority();
    }

    /**
    * Value创建
    * @param source source
    * @return Bean路径 对象
    */
    static BeanPath valueOf(Object source) {
        if (source instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                BeanPath jp = ServiceProvider.of(BeanPath.class).getExtension("json");
                if (jp != null) {
                    return jp;
                }
            }
            if (trimmed.startsWith("<")) {
                BeanPath xp = ServiceProvider.of(BeanPath.class).getExtension("xml");
                if (xp != null) {
                    return xp;
                }
            }
        }
        return new ObjectBeanPath();
    }

    /**
     * 获取值。
     *
     * @param source 来源，不允许为 null
     * @param path 路径，不允许为 null
     * @return T 对象
     */
    <T> T getValue(Object source, String path);

    /**
     * 设置值。
     *
     * @param source 来源，不允许为 null
     * @param path 路径，不允许为 null
     * @param value 值，不允许为 null
     */
    void setValue(Object source, String path, Object value);

    /**
     * exists。
     *
     * @param source 来源，不允许为 null
     * @param path 路径，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    boolean exists(Object source, String path);

    /**
    * 设置是否忽略大小写匹配属性名。
    * @param ignoreCase ignoreCase（布尔开关）
    * @return Bean路径 对象
    */
    default BeanPath ignoreCase(boolean ignoreCase) {
        return this;
    }

    /**
    * 设置匹配时的命名风格，自动转换属性名。
    *
    * <p>例如命名风格为 {@link NamingStyle#UNDERSCORE} 时，
    * 路径中的 {@code "user_name"} 会自动匹配对象的 {@code userName} 属性。</p>
    * @param style 方法入参 style
    * @return Bean路径 对象
    */
    default BeanPath namingStyle(NamingStyle style) {
        return this;
    }
}
