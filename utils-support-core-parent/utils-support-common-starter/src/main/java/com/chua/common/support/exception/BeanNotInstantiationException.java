package com.chua.common.support.exception;

import org.jspecify.annotations.NullUnmarked;

/**
 * Bean实例化异常
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
public class BeanNotInstantiationException extends RuntimeException{
    /**
     * 构造方法
     * @param message 异常信息
     */
    public BeanNotInstantiationException(String message) {
        super(message);
    }
}
