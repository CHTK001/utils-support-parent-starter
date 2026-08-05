package com.chua.common.support.objects.definition;


/**
 * 映射定义接口——描述一个路由映射的基本信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MappingDefinition {

    /**
     * @return 映射名称/标识
     */
    String getName();

    /**
     * @return 支持的 HTTP 方法数组
     */
    String[] getMethod();

    /**
     * @return 路径模板（如 "/api/hello"）
     */
    String getPath();

    /**
     * @return 关联的 Bean 定义
     */
    BeanDefinition getBeanDefinition();
}