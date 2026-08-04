package com.chua.common.support.objects.exception;

import org.jspecify.annotations.NullUnmarked;

/**
 * Bean 未找到异常。
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public class BeanNotFoundException extends RuntimeException {

    public BeanNotFoundException(String message) {
        super(message);
    }

    public BeanNotFoundException(String beanName, String cause) {
        super(String.format("Bean 未找到: %s, 原因: %s", beanName, cause));
    }

    public BeanNotFoundException(String beanName, Class<?> type) {
        super(String.format("Bean 未找到: %s (类型: %s)", beanName, type != null ? type.getName() : "unknown"));
    }
}