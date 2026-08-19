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

    /** 获取Instance */
    static BeanPath getInstance() {
        return ServiceProvider.of(BeanPath.class).getPriority();
    }

    /**
     * Value创建
     * @param source source
     */
    static BeanPath valueOf(Object source) {
        if (source instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                BeanPath jp = ServiceProvider.of(BeanPath.class).getExtension("json");
                if (jp != null) return jp;
            }
            if (trimmed.startsWith("<")) {
                BeanPath xp = ServiceProvider.of(BeanPath.class).getExtension("xml");
                if (xp != null) return xp;
            }
        }
        return new ObjectBeanPath();
    }

    <T> T getValue(Object source, String path);

    void setValue(Object source, String path, Object value);

    boolean exists(Object source, String path);

    /**
     * 设置是否忽略大小写匹配属性名。
     */
    default BeanPath ignoreCase(boolean ignoreCase) {
        return this;
    }

    /**
     * 设置匹配时的命名风格，自动转换属性名。
     *
     * <p>例如命名风格为 {@link NamingStyle#UNDERSCORE} 时，
     * 路径中的 {@code "user_name"} 会自动匹配对象的 {@code userName} 属性。</p>
     */
    default BeanPath namingStyle(NamingStyle style) {
        return this;
    }
}