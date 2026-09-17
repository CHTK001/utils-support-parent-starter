package com.chua.common.support.objects.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 属性注解。
 *
 * <p>标记在字段上，用于指定属性的键名。
 * 容器在注入配置值时，会使用此注解的 值 值作为配置 键 进行查找。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;Attribute("server.port")
 *   private int port;
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
*/
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Attribute {

    /**
    * 属性键名。
    *
    * <p>对应配置中的 key，如 "server.port"、"app.name" 等。
    * 支持点分隔的路径格式。</p>
    *
    * @return 属性键名，默认为空字符串
    */
    String value() default "";
}
