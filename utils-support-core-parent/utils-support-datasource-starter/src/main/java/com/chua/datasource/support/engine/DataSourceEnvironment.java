package com.chua.datasource.support.engine;

import lombok.Data;

/**
 * 数据源环境配置。
 * <p>
 * 为 {@link DataSourceConversion} 提供运行时环境参数。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class DataSourceEnvironment {

    /**
     * 连接池名称
     */
    private String poolName;

    /**
     * 数据库类型
     */
    private String databaseType;

    /**
     * 命名空间 / Schema
     */
    private String schema;

    /**
     * 额外属性
     */
    private java.util.Map<String, Object> properties;
}
