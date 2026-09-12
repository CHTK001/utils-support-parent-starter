package com.chua.datasource.support.config.debezium;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

/**
 * Debezium 环境就绪判断与初始化 SPI。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DebeziumEnvironmentSetup {

    /**
     * 判断当前环境是否满足 Debezium 运行前置条件。
     *
     * @param environment 目录poller 环境
     * @return true 表示已就绪
     */
    boolean isReady(DirectoryPollerEnvironment environment);

    /**
     * 初始化 Debezium 运行环境。
     *
     * @param environment 目录poller 环境
     */
    void setup(DirectoryPollerEnvironment environment);
}
