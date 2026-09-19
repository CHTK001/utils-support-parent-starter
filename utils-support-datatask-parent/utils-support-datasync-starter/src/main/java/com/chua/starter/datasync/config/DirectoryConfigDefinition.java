package com.chua.starter.datasync.config;

import java.util.List;
import java.util.Map;

/**
 * 目录配置定义。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DirectoryConfigDefinition extends DataSyncConfigDefinition {

    /**
     * 获取目录路径。
     *
     * @return 目录路径
     */
    String directoryPath();
}
