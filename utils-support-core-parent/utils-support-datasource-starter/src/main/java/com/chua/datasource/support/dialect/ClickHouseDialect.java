package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * click房子 22.x+ 方言。
 * <p>配置从 {@code META-INF/dialect-env/clickhouse.env} 加载。</p>
 * @author CH
 * @since 4.0.0
 */
public class ClickHouseDialect extends SqlDialect {
    public static final String VERSION = "ClickHouse 22.x+"; // 版本

    /**
     * click房子dialect。
     */
    public ClickHouseDialect() { super("clickhouse"); }
    /**
     * click房子dialect。
     * @param properties 属性
     */
    public ClickHouseDialect(Properties properties) { super("clickhouse", properties); }
}
