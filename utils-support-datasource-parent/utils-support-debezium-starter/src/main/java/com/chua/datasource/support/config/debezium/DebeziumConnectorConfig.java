package com.chua.datasource.support.config.debezium;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.Properties;

/**
 * Debezium 连接器配置 SPI。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DebeziumConnectorConfig {

    /**
     * 返回连接器实现类全限定名。
     *
     * @return 连接器类名
     */
    String connectorClass();

    /**
     * 将外部配置写入 Debezium Properties。
     *
     * @param props       Debezium 属性容器
     * @param environment DirectoryPoller 环境
     */
    void configure(Properties props, DirectoryPollerEnvironment environment);
}
