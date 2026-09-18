package com.chua.spring.support.configuration;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* 当指定名称的 Bean 定义已存在时跳过注册。
*
* <p>用于 Spring Boot 自动配置（{@code LockAutoConfiguration} / {@code CollapseAutoConfiguration}）
* 与 {@link UtilsSpringConfiguration} 同名 Bean 的共存：自动配置先注册同名 Bean 定义后，
* 本条件使 {@link UtilsSpringConfiguration} 的无条件 Bean 主动退避，避免
* {@code BeanDefinitionOverrideException} 导致应用启动失败。</p>
*
* <p>纯 Spring 核心机制（{@code org.springframework.context.annotation.Condition}），
* 不依赖 Spring Boot，纯 Spring 环境同样生效。</p>
*
* @author CH
* @since 2026/09/04
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(OnMissingBeanDefinitionCondition.class)
public @interface ConditionalOnMissingBeanDefinition {

    /**
    * @return 需要检查的 Bean 定义名称；任一已存在则跳过注册
    */
    String[] value();
}
