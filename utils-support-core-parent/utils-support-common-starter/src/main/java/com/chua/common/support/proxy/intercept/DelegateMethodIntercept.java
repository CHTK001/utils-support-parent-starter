package com.chua.common.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * 委托方法拦截器
 *
 * @param <T> 接口类型
 * @author CH
 */
public class DelegateMethodIntercept<T> implements InvocationHandler {

    /**
     * 类型
     */
    private final Class<T> type;
    private final Function<ProxyMethod, Object> delegate;

    public DelegateMethodIntercept(Class<T> type, Function<ProxyMethod, Object> delegate) {
        this.type = type;
        this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return delegate.apply(ProxyMethod.builder().method(method).args(args).build());
    }
}
