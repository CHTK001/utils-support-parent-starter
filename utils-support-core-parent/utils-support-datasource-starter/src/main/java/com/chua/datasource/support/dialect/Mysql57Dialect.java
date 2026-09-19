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
        super("mysql", defaultProps("com.mysql.jdbc.Driver", " ENGINE=InnoDB DEFAULT CHARSET=utf8"));
    }

    /**
     * 构造方法，创建 Mysql57Dialect 实例。
     *
     * @param properties 属性，不允许为 null
     */
    public Mysql57Dialect(Properties properties) {
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

    /**
     * default属性。
     *
     * @param driver 方法入参 driver
     * @param tableType 表类型，不允许为 null
     * @return 属性 对象
     */
    private static Properties defaultProps(String driver, String tableType) {
        Properties p = new Properties();
        p.setProperty("driver", driver);
        p.setProperty("table-type", tableType);
        // SPI 键为 mysql57：protocol() 须与键一致（env 仍复用 mysql.env）
        p.setProperty("protocol", "mysql57");
        return p;
    }

    /**
     * 合并。
     *
     * @param defaults 方法入参 defaults
     * @param overrides 方法入参 overrides
     * @return 属性 对象
     */
    private static Properties merge(Properties defaults, Properties overrides) {
        Properties merged = new Properties(defaults);
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
    }
}
