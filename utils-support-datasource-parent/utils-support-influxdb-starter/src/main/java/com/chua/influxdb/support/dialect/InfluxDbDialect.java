package com.chua.influxdb.support.dialect;

import com.chua.datasource.support.dialect.SqlDialect;

/**
 * InfluxDB InfluxQL 方言（配置驱动）。
 * <p>读取 {@code META-INF/dialect-env/influxdb.env}：查询语言 influxql，
 * 支持 LIMIT、不支持 OFFSET（深分页由引擎侧内存截取兜底）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InfluxDbDialect extends SqlDialect {

    /**
     * 构造方法。
     */
    public InfluxDbDialect() {
        super("influxdb");
    }
}
