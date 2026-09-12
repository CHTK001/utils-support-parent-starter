package com.chua.common.support.proxy;

import com.chua.common.support.proxy.intercept.DelegateMethodIntercept;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.InvocationHandler;

/**
 * 代理工具类，提供创建 JDK 动态代理实例的便捷静态方法。
 *
 * <p>本类封装了 {@link Proxy#newProxyInstance(ClassLoader, Class[], InvocationHandler)}
 * 的调用，提供了更简洁的 API 签名。支持两种回调方式：</p>
 * <ul>
 *   <li>{@link #newProxy(Class, ClassLoader, DelegateMethodIntercept)} — 使用 {@link DelegateMethodIntercept} 回调</li>
 *   <li>{@link #proxy(Class, ClassLoader, InvocationHandler)} — 使用标准 JDK {@link InvocationHandler} 回调</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 使用 DelegateMethodIntercept 回调
 * Service proxy = ProxyUtils.newProxy(Service.class, classLoader,
 *     new DelegateMethodIntercept<>(Service.class, proxyMethod -> {
 *         System.out.println("调用方法: " + proxyMethod.getMethodName());
 *         return proxyMethod.invoke(targetBean);
 *     }));
 *
 * // 使用标准 InvocationHandler 回调
 * Service proxy = ProxyUtils.proxy(Service.class, classLoader,
 *     (obj, method, args) -> {
 *         System.out.println("调用方法: " + method.getName());
 *         return method.invoke(targetBean, args);
 *     });
 * }</pre> -> {
   * 系统.出.println("调用方法: " + 方法.获取名称());
   * 返回 方法.invoke(TargetBean, 参数);
 *     });
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see java.lang.reflect.Proxy
 * @see DelegateMethodIntercept
 * @see InvocationHandler
 */
public class ProxyUtils {

    /**
     * 创建 JDK 动态代理实例（使用 {@link DelegateMethodIntercept} 回调）。
     *
     * <p>通过 JDK 动态代理创建指定接口的代理实例。所有方法调用将转发给
     * {@link DelegateMethodIntercept} 的 invoke 方法处理。
     * 代理对象仅实现指定的 {@code type} 接口。</p>
     *
     * @param type        目标接口类型，如 {@code Service.class}
     * @param classLoader 类加载器，用于定义代理类
     * @param handler     {@link DelegateMethodIntercept} 调用处理器，定义方法拦截逻辑
     * @param <T>         接口类型
     * @return 代理实例
     */
@SuppressWarnings("unchecked")
    public static <T> T newProxy(Class<T> type, ClassLoader classLoader, DelegateMethodIntercept<T> handler) {
        return (T) ReflectUtils.newProxy(classLoader, new Class<?>[]{type}, handler);
    }

    /**
     * 创建 JDK 动态代理实例（使用标准 {@link InvocationHandler} 回调）。
     *
     * <p>通过 JDK 动态代理创建指定接口的代理实例。所有方法调用将转发给
     * {@link InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])} 方法处理。
     * 此方法适用于需要直接使用标准 JDK 动态代理 API 的场景。</p>
     *
     * @param type        目标接口类型，如 {@code Service.class}
     * @param classLoader 类加载器，用于定义代理类
     * @param handler     标准 JDK {@link InvocationHandler} 调用处理器
     * @param <T>         接口类型
     * @return 代理实例
     */
    public static <T> T proxy(Class<T> type, ClassLoader classLoader, InvocationHandler handler) {
        return (T) ReflectUtils.newProxy(classLoader, new Class<?>[]{type}, handler);
    }
}
