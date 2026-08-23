package com.chua.common.support.lang.bean;

import com.chua.common.support.lang.xml.XmlPath;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 基于 {@link XmlPath} 的 BeanPath 实现，将 XML 字符串视为数据源。
 *
 * <p>当目标对象为 XML 字符串时，使用 XPath 表达式进行查询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see XmlPath
 * @see BeanPath
 */
@Spi("xml")
@ConditionalOnClass("javax.xml.xpath.XPathFactory")
public class XmlPathBeanPath implements BeanPath {

    @Override
    /** 获取Value */
    public <T> T getValue(Object source, String path) {
        if (!(source instanceof String xml)) {
            return null;
        }
        XmlPath xmlPath = XmlPath.getInstance();
        if (xmlPath == null) {
            return null;
        }
        String value = xmlPath.getValue(xml, path);
        return value != null ? (T) value : null;
    }

    @Override
    /** 设置Value */
    public void setValue(Object source, String path, Object value) {
    }

    @Override
    /** 是否存在 */
    public boolean exists(Object source, String path) {
        if (!(source instanceof String xml)) {
            return false;
        }
        XmlPath xmlPath = XmlPath.getInstance();
        return xmlPath != null && xmlPath.exists(xml, path);
    }
}
