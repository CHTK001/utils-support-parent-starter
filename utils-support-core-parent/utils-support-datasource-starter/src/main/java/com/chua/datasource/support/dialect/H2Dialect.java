package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * H2 2.x 方言。
 * <p>配置从 {@code META-INF/dialect-env/h2.env} 加载。</p>
 * @author CH
 * @since 4.0.0
 */
public class H2Dialect extends SqlDialect {
    public static final String VERSION = "H2 2.x"; // 版本

    /**
     * H2Dialect。
     */
    public H2Dialect() { super("h2"); }
    /**
     * H2Dialect。
     * @param properties 属性
     */
    public H2Dialect(Properties properties) { super("h2", properties); }
}
