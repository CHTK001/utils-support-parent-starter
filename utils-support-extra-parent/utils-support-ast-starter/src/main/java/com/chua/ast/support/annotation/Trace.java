package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
* 方法链路追踪注解，编译期自动插入 追踪id 管理和耗时日志
*
* <p>特性：</p>
* <ul>
*   <li>子方法继承父方法 traceId，同一调用链共享</li>
*   <li>通过 {@code TRACE_ENABLED=false} 环境变量关闭</li>
*   <li>支持 slf4j / System.out 自动降级</li>
*   <li>树形格式输出：{@code └── UserService.getUser() 15ms}</li>
* </ul>
*
* @author CH
* @since 4.0.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Trace {

    /**
    * 是否在入口打印参数值
     */
    boolean includeArgs() default false;

    /**
    * 最大追踪层级，0 表示不限制
     */
    int depth() default 0;
}
