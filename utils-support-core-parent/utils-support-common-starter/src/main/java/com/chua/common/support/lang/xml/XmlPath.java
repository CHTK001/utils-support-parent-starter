package com.chua.common.support.lang.xml;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
 * XML 路径表达式接口，通过 XPath 表达式从 XML 文档中获取值。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi
public interface XmlPath {

    /**
     * 获取默认 XmlPath 实例。
     *
     * @return XmlPath 实例
     */
    static XmlPath getInstance() {
        return ServiceProvider.of(XmlPath.class).getPriority();
    }

    /**
     * 从 XML 字符串中按路径表达式获取值。
     *
     * @param xml  XML 字符串
     * @param path XPath 表达式
     * @return 路径对应的值，不存在返回 null
     */
    String getValue(String xml, String path);

    /**
     * 判断路径表达式在 XML 中是否存在。
     *
     * @param xml  XML 字符串
     * @param path XPath 表达式
     * @return 存在返回 true
     */
    boolean exists(String xml, String path);
}