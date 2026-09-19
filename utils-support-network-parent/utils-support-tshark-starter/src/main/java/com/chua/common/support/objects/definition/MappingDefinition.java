package com.chua.common.support.objects.definition;

/**
 * URL 路径到 Beandefinition 的映射定义接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MappingDefinition {
    /**
     * 获取BeanDefinition。
     *
     * @return BeanDefinition 对象
     */
    BeanDefinition getBeanDefinition();
    /**
     * 获取Urls。
     *
     * @return 字符串 对象
     */
    String[] getUrls();
}
