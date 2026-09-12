package com.chua.datasource.support.dialect;

import java.util.Properties;

/**
* MySQL 5.7 方言。
* <p>与 MysqlDialect 共享 mysql.env，唯一区别是 driver 类名。</p>
* @author CH
* @since 4.0.0
* @param defaults 默认
* @param overrides overrides
* @return 合并的结果
 */
public class Mysql57Dialect extends SqlDialect {
    public static final String VERSION = "MySQL 5.7"; // 版本

    /**
    * MySQL57Dialect。
     */
    public Mysql57Dialect() {
        /**
        * MySQL57Dialect。
        * @param properties 属性
         */
        super("mysql", defaultProps("com.mysql.jdbc.Driver", " ENGINE=InnoDB DEFAULT CHARSET=utf8"));
    }

    public Mysql57Dialect(Properties properties) {
        /**
        * 默认props。
        * @param driver driver
        * @param tableType table类型
        * @return 默认props的结果
        * @param defaults 默认
        * @param overrides overrides
         */
        super("mysql", merge(defaultProps("com.mysql.jdbc.Driver", " ENGINE=InnoDB DEFAULT CHARSET=utf8"), properties));
    /**
    * 默认props。
    * @param driver driver
    * @param tableType table类型
    * @return 默认props的结果
    * @param defaults 默认
    * @param overrides overrides
     */
    }

    private static Properties defaultProps(String driver, String tableType) {
        Properties p = new Properties();
        p.setProperty("driver", driver);
        p.setProperty("table-type", tableType);
        return p;
    }

    private static Properties merge(Properties defaults, Properties overrides) {
        Properties merged = new Properties(defaults);
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
    }
}
