package com.chua.common.support.objects.definition;

import java.lang.reflect.Method;

/**
* 映射解析器接口。
*
* <p>负责将业务对象解析为 {@link MappingDefinition} 列表，
* 各框架/模块通过实现此接口提供自定义路由映射能力。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface MappingParser {

    /**
    * 解析对象中的所有映射。
    *
    * @param mappingObject 待解析对象（通常是 控制器 / 处理器 实例）
    * @return 映射定义列表
     */
    MappingDefinition[] parse(Object mappingObject);

    /**
    * 解析单个方法。
    *
    * @param method        方法对象
    * @param mappingObject 所属对象实例
    * @return 映射定义，不适用则返回 空
     */
    MappingDefinition parseMethod(Method method, Object mappingObject);

    /**
    * 判断是否支持解析指定方法。
    *
    * @param method 待检查方法
    * @return 支持则返回 true
     */
    boolean supports(Method method);

    /**
    * @return 解析器优先级（数值越大优先级越高）
     */
    int getPriority();

    /**
    * @return 解析器名称
     */
    String getName();
}