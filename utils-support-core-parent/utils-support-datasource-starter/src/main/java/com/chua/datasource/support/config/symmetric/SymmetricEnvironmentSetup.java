package com.chua.datasource.support.config.symmetric;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

/**
 * SymmetricDS 数据库环境自动配置 SPI 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SymmetricEnvironmentSetup {

    /**
     * 自动配置数据库环境（建表、初始化等）。
     *
     * @param environment 环境配置
     */
    void setup(DirectoryPollerEnvironment environment);
}
