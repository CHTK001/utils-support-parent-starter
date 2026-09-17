package com.chua.common.support.objects.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 启用可配置注解。
 *
 * <p>标记在类上，表示该类需要启用配置注入功能。
 * 容器识别到此注解后，会扫描该类中标注了 {@link ConfigValue} 的字段，
 * 并将环境配置中的值注入到对应字段中。</p>
 *
 * @author CH
 * @since 2024/12/20
*/
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableConfigurable {
}
