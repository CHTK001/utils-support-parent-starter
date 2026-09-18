package com.chua.datasource.support.dialect;
import java.util.Properties;
/**
 * ClickHouse 20.x 方言（旧驱动）。
 */
public class ClickHouse20Dialect extends SqlDialect {
    /**
    * ClickHouse 20.x 方言（旧驱动）版本。
    */
    public static final String VERSION = "ClickHouse 20.x"; // 版本
    /**
    * 构造 ClickHouse 20.x 方言，使用默认属性。
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
