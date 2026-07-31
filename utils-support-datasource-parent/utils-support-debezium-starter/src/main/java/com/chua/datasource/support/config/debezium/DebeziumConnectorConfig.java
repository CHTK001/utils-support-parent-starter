package com.chua.datasource.support.config.debezium;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.Properties;
/**
 * @author CH
 */

public interface DebeziumConnectorConfig {

    String connectorClass();

    void configure(Properties props, DirectoryPollerEnvironment environment);
}
