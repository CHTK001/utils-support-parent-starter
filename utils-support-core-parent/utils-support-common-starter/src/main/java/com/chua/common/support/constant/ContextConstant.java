package com.chua.common.support.constant;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.annotation.Annotation;

/**
 * 上下文常量接口，定义了与 Spring 框架相关的常量。
 *
 * <p>该接口主要用于在不直接依赖 Spring 框架的情况下，通过反射方式加载和使用 Spring 注解。
 * 这种设计使得工具类可以在非 Spring 环境中使用，同时保持与 Spring 的兼容性。</p>
 *
 * <p>主要用途：</p>
 * <ul>
 *   <li>判断类是否被 Spring 管理</li>
 *   <li>在非 Spring 环境中检测 Spring Bean</li>
 *   <li>支持条件装配和自动配置</li>
 * </ul>
 *
 * @author CH
 * @since 1.0
 * @see org.springframework.stereotype.Component
 */
public final class ContextConstant {
    private ContextConstant() {}

    /**
     * Spring Component 注解的完整类名。
     * 用于通过反射方式加载 Component 注解类。
     */
    public static final String COMPONENT_CLASS_NAME = "org.springframework.stereotype.Component";

    /**
     * Spring Component 注解的 Class 对象。
     * 如果 Spring 框架不在类路径中，则为 null。
     */
    public static final Class<? extends Annotation> COMPONENT = loadComponentAnnotation();

    /**
     * 尝试加载 Spring Component 注解类。
     *
     * @return Component 注解的 Class 对象，如果加载失败则返回 null
     */
    private static Class<? extends Annotation> loadComponentAnnotation() {
        return (Class<? extends Annotation>) ReflectUtils.forName(COMPONENT_CLASS_NAME);
    }
}