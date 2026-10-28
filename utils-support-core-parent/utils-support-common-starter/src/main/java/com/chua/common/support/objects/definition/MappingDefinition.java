package com.chua.common.support.objects.definition;


/**
 * 映射定义接口——描述一个路由映射的基本信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MappingDefinition {

    /**
     * 获取 Name。
     *
     * @return 映射名称/标识
     */
    String getName();

    /**
     * 获取 Method。
     *
     * @return 支持的 HTTP 方法数组
     */
    String[] getMethod();

    /**
     * 获取 Path。
     *
     * @return 路径模板（如 "/api/hello"）
     */
    String getPath();

    /**
     * 获取 BeanDefinition。
     *
     * @return 关联的 Bean 定义
     */
    BeanDefinition getBeanDefinition();
}
