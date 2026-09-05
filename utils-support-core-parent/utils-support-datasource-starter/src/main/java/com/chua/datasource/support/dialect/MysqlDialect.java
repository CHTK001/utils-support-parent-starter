package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * MySQL 8.0+ 方言（兼容 5.7）。
 * <p>配置从 {@code META-INF/dialect-env/mysql.env} 加载。</p>
 */
public class MysqlDialect extends SqlDialect {
    public MysqlDialect() { super("mysql"); }
    public MysqlDialect(Properties properties) { super("mysql", properties); }
}
