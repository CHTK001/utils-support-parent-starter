package com.chua.starter.datasync.scanner;

import java.nio.file.Path;

/**
* 配置文件解析器，将文件解析为 数据同步配置definition。
*
* @author CH
* @since 4.0.0.42
 */
public interface ConfigFileParser {

    /**
    * 判断是否支持该文件。
    *
    * @param file 文件路径
    * @return true 表示支持解析
    */
    boolean supports(Path file);

    /**
    * 解析文件为 数据同步配置definition。
    *
    * @param file 文件路径
    * @return 配置定义
    * @throws Exception 解析异常
    */
    com.chua.starter.datasync.config.DataSyncConfigDefinition parse(Path file) throws Exception;
}
