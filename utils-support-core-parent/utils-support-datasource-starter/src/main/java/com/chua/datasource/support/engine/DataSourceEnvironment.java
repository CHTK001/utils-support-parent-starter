package com.chua.datasource.support.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
@NoArgsConstructor
@AllArgsConstructor
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
    private Map<String, Object> properties;
}
