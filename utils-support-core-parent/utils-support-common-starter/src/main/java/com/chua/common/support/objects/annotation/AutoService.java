package com.chua.common.support.objects.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自动服务注解。
 *
 * <p>标记一个类为可被容器管理的 Bean（服务组件）。
   * 当类上标注了 @auto服务 注解时，容器在扫描阶段会自动发现该类，
   * 并创建对应的 Beandefinition 进行管理。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   &#64;AutoService
 *   public class UserService implements InitializingAware {
 *       &#64;Override
 *       public void afterPropertiesSet() {
 *           System.out.println("UserService 初始化完成");
 *       }
 *   }
 * </pre>
 *
 * @author CH
 * @since 2024/12/20
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoService {
}