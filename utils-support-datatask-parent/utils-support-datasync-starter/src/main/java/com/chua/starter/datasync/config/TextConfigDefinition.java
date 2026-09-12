package com.chua.starter.datasync.config;

import java.util.List;
import java.util.Map;

/**
* 文本字符串配置定义。
*
* @author CH
* @since 4.0.0.42
 */
public interface TextConfigDefinition extends DataSyncConfigDefinition {

    /**
    * 获取文本配置内容。
    *
    * @return 文本配置
     */
    String text();
}
