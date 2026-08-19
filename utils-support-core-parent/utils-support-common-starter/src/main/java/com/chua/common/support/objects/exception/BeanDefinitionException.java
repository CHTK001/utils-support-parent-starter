package com.chua.common.support.objects.exception;


/**
 * Bean 定义异常，在构造器参数解析、Bean 定义配置错误时抛出。
 *
 * @author CH
 * @since 2024/12/20
 */
public class BeanDefinitionException extends RuntimeException {

    /**
     * 创建 BeanDefinitionException 实例
     * @param message message
     */
    public BeanDefinitionException(String message) {
        super(message);
    }

    /**
     * 创建 BeanDefinitionException 实例
     * @param message message
     * @param Throwable Throwable
     */
    public BeanDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
