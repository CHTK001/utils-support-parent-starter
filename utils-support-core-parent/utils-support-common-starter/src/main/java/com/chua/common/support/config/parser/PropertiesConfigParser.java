package com.chua.common.support.config.parser;

import com.chua.common.support.config.source.PropertiesPropertySource;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;


/**
* 属性配置文件解析器
* <p>
* 用于将标准的 Java Properties 文件流转换为可配置的 PropertySource 对象。
* </p>
*
* @author CH
* @since 2023-09-05
 */
@Slf4j
@Spi({"properties"})
public class PropertiesConfigParser implements ConfigParser {

    /**
    * 解析指定的属性源输入流
    * <p>
    * 该方法尝试从提供的 InputStream 中加载属性数据，并将其封装为 PropertiesPropertySource。
    * 如果发生 IO 异常，则记录错误日志并返回空的 PropertySource。
    * </p>
    *
    * @param urlPath 属性文件的 URL 路径或标识符，用于描述来源位置
    * @param is      包含属性数据的输入流，例如：new FileInputStream("application.properties")
    * @return        解析后的 PropertySource 对象；若解析失败则返回空对象
     */
    @Override
    public PropertySource parse(String urlPath, InputStream is) {
        // 创建新的 Properties 实例用于存储配置项
        Properties properties = new Properties();

        try {
            // 从输入流中加载属性数据
            properties.load(is);
        } catch (IOException e) {
            // 记录加载属性文件失败的错误信息
            log.error("加载 properties 配置文件失败", e);
            // 返回空的 PropertySource 对象以处理异常情况
            return PropertySource.EMPTY;
        }

        // 使用解析后的属性和原始路径构建并返回 PropertySource
        return new PropertiesPropertySource(urlPath, properties);
    }
}
