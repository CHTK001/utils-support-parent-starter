package com.chua.common.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * 委托方法拦截器
 *
 * @param <T> 接口类型
 * @param type 类型
 * @param delegate Function
 * @author CH
 * @since 4.0.0.42
 */
public record DelegateMethodIntercept<T>(Class<T> type, Function<ProxyMethod, Object> delegate) implements InvocationHandler {

    /**
     * 调用
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return delegate.apply(ProxyMethod.builder().method(method).args(args).build());
    }
}
