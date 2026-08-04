package com.chua.common.support.objects.definition;

import com.chua.common.support.objects.describe.MethodDescribe;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * URL 映射定义——MappingDefinition 的具体实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Data
@Builder
public class UrlMappingDefinition implements MappingDefinition {

    /**
     * 映射名称
     */
    private String name;

    /**
     * 路径模板
     */
    private String path;

    /**
     * HTTP 方法
     */
    private String[] method;

    /**
     * 方法描述元数据
     */
    private MethodDescribe methodDescribe;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 关联的 Bean 定义
     */
    private BeanDefinition beanDefinition;

    /**
     * 排序优先级
     */
    private int order;
}