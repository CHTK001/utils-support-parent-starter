package com.chua.common.support.proxy.intercept;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 空方法拦截器，所有方法调用都返回默认值。
 *
 * <p>该类同时实现了 {@link InvocationHandler} 和 {@link MethodIntercept} 接口。
 *
 * @author CH
 * @since 1.0
*/
public class VoidMethodIntercept<T> implements MethodIntercept<T>, InvocationHandler {

    @Override
    /** 调用 */
    public Object invoke(Object proxy, Method method, Object[] args) {
        return null;
    }

    @Override
    /** 调用 */
    public Object invoke(Object obj, Method method, Object[] args, T proxy) {
        return null;
    }
}
