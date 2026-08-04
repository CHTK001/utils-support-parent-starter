package com.chua.common.support.objects.exception;

import org.jspecify.annotations.NullUnmarked;

/**
 * Bean 定义异常，在构造器参数解析、Bean 定义配置错误时抛出。
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public class BeanDefinitionException extends RuntimeException {

    public BeanDefinitionException(String message) {
        super(message);
    }

    public BeanDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
