package com.chua.common.support.objects.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自动注入注解。
 *
 * <p>标记需要容器自动装配的字段。当容器创建 Bean 实例后，
   * 会扫描带有 @autoinject 注解的字段，并尝试从容器中查找匹配的 Bean 进行注入。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;AutoService
 *   public class OrderService {
 *       &#64;AutoInject
 *       private UserDao userDao;
 *
 *       &#64;AutoInject("orderRepository")
 *       private OrderRepository orderRepo;
 *
 *       &#64;AutoInject(required = false)
 *       private LoggerService loggerService;
 *   }
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoInject {

    /**
     * Bean 名称。
     *
     * <p>指定要注入的 Bean 名称，用于精确匹配。
     * 为空时，容器将按字段类型自动匹配第一个可用的 Bean。</p>
     *
     * @return Bean 名称，默认为空字符串（按类型匹配）
     */
    String value() default "";

    /**
     * 是否必须注入。
     *
     * <p>当设置为 true（默认）时，如果容器中找不到匹配的 Bean，将抛出异常。
      * 设置为 false 时，找不到 Bean 则字段保持 空，不会报错。</p>
     *
     * @return 是否必须注入，默认 true
     */
    boolean required() default true;
}