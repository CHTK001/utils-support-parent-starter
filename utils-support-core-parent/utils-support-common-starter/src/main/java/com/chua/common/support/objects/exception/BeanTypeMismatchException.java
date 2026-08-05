package com.chua.common.support.objects.exception;


/**
 * Bean 类型不匹配异常。
 *
 * <p>当请求的 Bean 类型与实际获取到的 Bean 类型不一致时抛出。
 * 例如：按类型 A 查找 Bean，但容器返回的实例类型与 A 不兼容。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
public class BeanTypeMismatchException extends RuntimeException {

    public BeanTypeMismatchException(String msg) {
        super(msg);
    }
}
