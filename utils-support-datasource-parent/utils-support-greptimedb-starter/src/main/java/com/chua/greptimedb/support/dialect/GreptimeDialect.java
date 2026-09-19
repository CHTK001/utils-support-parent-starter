package com.chua.greptimedb.support.dialect;

import com.chua.datasource.support.dialect.SqlDialect;

/**
 * GreptimeDB 方言（配置驱动）。
 * <p>读取 {@code META-INF/dialect-env/greptime.env}：SQL 语法 MySQL 兼容，
 * 原生支持 {@code LIMIT n OFFSET m}，不支持 upsert 与自增主键。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class GreptimeDialect extends SqlDialect {

    /**
     * 构造方法。
     */
    public GreptimeDialect() {
        super("greptime");
    }
}
