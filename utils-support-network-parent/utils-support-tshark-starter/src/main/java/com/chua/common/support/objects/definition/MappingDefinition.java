package com.chua.common.support.objects.definition;

/**
 * URL 路径到 BeanDefinition 的映射定义接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MappingDefinition {
    BeanDefinition getBeanDefinition();
    String[] getUrls();
}
