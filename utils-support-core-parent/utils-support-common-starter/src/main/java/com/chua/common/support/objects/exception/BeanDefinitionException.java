package com.chua.common.support.objects.exception;


/**
 * Bean 定义异常，在构造器参数解析、Bean 定义配置错误时抛出。
 *
 * @author CH
 * @since 2024/12/20
 */
public class BeanDefinitionException extends RuntimeException {

    /**
      * 创建 Beandefinition异常 实例
     * @param message 消息
     */
    public BeanDefinitionException(String message) {
        super(message);
    }

    /**
      * 创建 Beandefinition异常 实例
     * @param message 消息
     * @param cause Throwable
     * @param cause cause
     */
    public BeanDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
