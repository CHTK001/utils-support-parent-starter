package com.chua.common.support.objects.exception;


/**
 * Bean 未找到异常。
 *
 * @author CH
 * @since 2024/12/20
 */
public class BeanNotFoundException extends RuntimeException {

    /**
     * 创建 BeanNotFoundException 实例
     * @param message message
     */
    public BeanNotFoundException(String message) {
        super(message);
    }

    /**
     * 创建 BeanNotFoundException 实例
     * @param beanName beanName
     * @param String String
     */
    public BeanNotFoundException(String beanName, String cause) {
        super(String.format("Bean 未找到: %s, 原因: %s", beanName, cause));
    }

    /**
     * 创建 BeanNotFoundException 实例
     * @param beanName beanName
     * @param Class Class
     * @param type type
     */
    public BeanNotFoundException(String beanName, Class<?> type) {
        super(String.format("Bean 未找到: %s (类型: %s)", beanName, type != null ? type.getName() : "unknown"));
    }
}