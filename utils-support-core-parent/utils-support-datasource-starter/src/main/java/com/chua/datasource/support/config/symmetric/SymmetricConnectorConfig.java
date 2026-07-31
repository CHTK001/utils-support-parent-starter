package com.chua.datasource.support.config.symmetric;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.Properties;

/**
 * SymmetricDS 数据库连接器配置 SPI 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SymmetricConnectorConfig {

    /**
     * 配置 SymmetricDS 连接属性。
     *
     * @param props       SymmetricDS 配置属性
     * @param environment 环境配置
     */
    void configure(Properties props, DirectoryPollerEnvironment environment);
}
