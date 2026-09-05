package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
 * MySQL 5.7 方言。
 * <p>与 MysqlDialect 共享 mysql.env，唯一区别是 driver 类名。</p>
 */
public class Mysql57Dialect extends SqlDialect {
    public static final String VERSION = "MySQL 5.7";

    public Mysql57Dialect() {
        super("mysql", defaultProps("com.mysql.jdbc.Driver", " ENGINE=InnoDB DEFAULT CHARSET=utf8"));
    }

    public Mysql57Dialect(Properties properties) {
        super("mysql", merge(defaultProps("com.mysql.jdbc.Driver", " ENGINE=InnoDB DEFAULT CHARSET=utf8"), properties));
    }

    private static Properties defaultProps(String driver, String tableType) {
        Properties p = new Properties();
        p.setProperty("driver", driver);
        p.setProperty("table-type", tableType);
        return p;
    }

    private static Properties merge(Properties defaults, Properties overrides) {
        Properties merged = new Properties(defaults);
        if (overrides != null) merged.putAll(overrides);
        return merged;
    }
}
