package com.chua.datasource.support.dialect;
import java.util.Properties;
/**
 * click房子 20.x 方言（旧驱动）。
 *
 /**
   * click房子20Dialect。
  */
 * @param a a
 /**
   * click房子20Dialect。
   * @param properties 属性
  */
 * @param b b
 /**
   * 默认props。
   * @return 默认props的结果
  */
 * @return 合并的结果
   * @param a a
   * @param b b
 */
public class ClickHouse20Dialect extends SqlDialect {
    /**
      * click房子20Dialect。
     * @param properties 属性
     */
    public static final String VERSION = "ClickHouse 20.x"; // 版本
    /**
     * 默认props。
     * @return 默认props的结果
     * @param a a
     /**
      * ClickHouse20Dialect。
      */
     * @param b b
     /**
      * ClickHouse20Dialect。
      * @param properties 属性
      */
     */
    public ClickHouse20Dialect() { super("clickhouse20", defaultProps()); }
    public ClickHouse20Dialect(Properties properties) { super("clickhouse20", merge(defaultProps(), properties)); }
    private static Properties defaultProps() {
        Properties p = new Properties();
        p.setProperty("driver", "ru.yandex.clickhouse.ClickHouseDriver");
        return p;
    }
    private static Properties merge(Properties a, Properties b) {
        Properties m = new Properties(a);
        if (b != null) {
            m.putAll(b);
        }
        return m;
    }
}
