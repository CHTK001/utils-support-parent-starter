package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * ClickHouse 22.x+ 方言。
 * <p>配置从 {@code META-INF/dialect-env/clickhouse.env} 加载。</p>
 */
public class ClickHouseDialect extends SqlDialect {
    public static final String VERSION = "ClickHouse 22.x+";

    public ClickHouseDialect() { super("clickhouse"); }
    public ClickHouseDialect(Properties properties) { super("clickhouse", properties); }
}
