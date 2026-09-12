package com.chua.common.support.config.parser;

import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.annotations.Spi;

import java.io.InputStream;

/**
* 配置解析器 SPI 接口。
* <p>
* 该接口负责将原始配置数据（例如文件内容、环境变量或系统属性）
* 解析并转换为标准化的键值对 Map 结构。
* </p>
* <p>
* 每种配置格式都对应一个具体的 ConfigParser 实现类：
* <ul>
*   <li>{@code properties}：Properties 文件解析器</li>
*   <li>{@code xml}：XML 配置文件解析器</li>
*   <li>{@code system-env}：系统环境变量解析器</li>
*   <li>{@code system-properties}：JVM 系统属性解析器</li>
*   <li>{@code yaml}：YAML 文件解析器（由 filesystem-starter 模块提供）</li>
* </ul>
* </p>
*
* @author CH
* @since 2026/07/16
 */
@Spi
public interface ConfigParser {

    /**
    * 解析配置数据。
    * <p>
    * 该方法将原始配置数据转换为标准化的键值映射表。
    * </p>
    * <p>
    * 返回的 Map 中，key 为配置键（通常是拼接后的全路径，例如 "server.port"），
    * value 为对应的配置值（可以是字符串或基本类型）。
    * </p>
    *
    * @param urlPath 原始配置数据的来源路径或标识符（如文件路径）
    * @param is      原始配置数据的输入流
    * @return 解析后的配置映射表，绝不为 null
     */
    PropertySource parse(String urlPath, InputStream is);

}