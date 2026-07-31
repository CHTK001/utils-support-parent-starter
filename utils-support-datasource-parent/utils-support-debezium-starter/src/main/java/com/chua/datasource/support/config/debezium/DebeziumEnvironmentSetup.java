package com.chua.datasource.support.config.debezium;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
/**
 * @author CH
 */

public interface DebeziumEnvironmentSetup {

    boolean isReady(DirectoryPollerEnvironment environment);

    void setup(DirectoryPollerEnvironment environment);
}
