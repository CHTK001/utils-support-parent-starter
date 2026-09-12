package com.chua.common.support.objects.exception;


/**
* Bean 未找到异常。
*
* @author CH
* @since 2024/12/20
 */
public class BeanNotFoundException extends RuntimeException {

    /**
    * 创建 Beannotfound异常 实例
    * @param message 消息
     */
    public BeanNotFoundException(String message) {
        super(message);
    }

    /**
    * 创建 Beannotfound异常 实例
    * @param beanName Bean名称
    * @param beanName 字符串
    * @param cause cause
     */
    public BeanNotFoundException(String beanName, String cause) {
        super(String.format("Bean 未找到: %s, 原因: %s", beanName, cause));
    }

    /**
    * 创建 Beannotfound异常 实例
    * @param beanName Bean名称
    * @param type 类
    * @param type 类型
     */
    public BeanNotFoundException(String beanName, Class<?> type) {
        super(String.format("Bean 未找到: %s (类型: %s)", beanName, type != null ? type.getName() : "unknown"));
    }
}