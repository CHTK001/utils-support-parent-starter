package com.chua.starter.datasync.config;

import java.util.List;
import java.util.Map;

/**
* 文件配置定义。
*
* @author CH
* @since 4.0.0.42
 */
public interface FileConfigDefinition extends DataSyncConfigDefinition {

    /**
    * 获取文件路径。
    *
    * @return 文件路径
    */
    String filePath();
}
