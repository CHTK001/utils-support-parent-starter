package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
* PostgreSQL 10+ 方言代理，配置从 {@code META-INF/dialect-env/postgresql10.env} 加载。
*
* @author CH
* @since 4.0.0.42
 */
public class Postgresql10Dialect extends SqlDialect {

    /**
    * 支持版本。
    */
    public static final String VERSION = "PostgreSQL 10+";

    /**
    * 构造 PostgreSQL 10+ 方言代理。
    */
    public Postgresql10Dialect() {
        super("postgresql10");
    }

    public Postgresql10Dialect(Properties properties) {
        super("postgresql10", properties);
    }
}
