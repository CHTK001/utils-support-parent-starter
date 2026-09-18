package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
* MySQL 8.0+ 方言（兼容 5.7）。
* <p>配置从 {@code META-INF/dialect-env/mysql.env} 加载。</p>
* @author CH
* @since 4.0.0
 */
public class MysqlDialect extends SqlDialect {
    /**
    * mysqldialect。
    */
    public MysqlDialect() { super("mysql"); }
    /**
    * mysqldialect。
    * @param properties 属性
    */
    public MysqlDialect(Properties properties) { super("mysql", properties); }
}
