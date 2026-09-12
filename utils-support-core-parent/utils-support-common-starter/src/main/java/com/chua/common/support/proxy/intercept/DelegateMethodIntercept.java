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
* @since 4.0.0.42
 */
public class DelegateMethodIntercept<T> implements InvocationHandler {

    /**
    * 类型
     */
    private final Class<T> type;
    /** delegate */
    private final Function<ProxyMethod, Object> delegate;

    /**
    * 创建 delegate方法intercept 实例
    * @param type 类型
    * @param delegate Function
    * @param Object 对象
    * @param delegate delegate
     */
    public DelegateMethodIntercept(Class<T> type, Function<ProxyMethod, Object> delegate) {
        this.type = type;
        this.delegate = delegate;
    }

    @Override
    /** 调用 */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return delegate.apply(ProxyMethod.builder().method(method).args(args).build());
    }
}
