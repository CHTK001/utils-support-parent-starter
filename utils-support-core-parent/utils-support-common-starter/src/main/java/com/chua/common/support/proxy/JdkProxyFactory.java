package com.chua.common.support.proxy;

import com.chua.common.support.proxy.intercept.MethodIntercept;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ArrayUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;


/**
* JDK 动态代理工厂，基于 {@link java.lang.reflect.Proxy} 创建代理对象。
*
* <p>本类是 {@link ProxyFactory} 的 JDK 实现，使用 Java 标准库的反射机制创建代理对象。
* 与 CGLIB 或 Javassist 等字节码增强技术相比，JDK 动态代理的特点是：</p>
* <ul>
*   <li><b>零依赖</b> — 仅使用 JDK 标准库，无需引入第三方 jar 包</li>
*   <li><b>性能良好</b> — 调用性能稳定，JDK 8+ 有原生优化</li>
*   <li><b>限制</b> — 只能代理接口类型，不能代理类</li>
* </ul>
*
* <p>本类以单例模式提供，通过 {@link #INSTANCE} 访问。</p>
*
* <p><b>执行流程：</b></p>
* <pre>{@code
* Proxy.newProxyInstance(classLoader, interfaces, handler)
*   └── JdkInvocationHandler.invoke(proxy, method, args)
*         ├── intercept.before(obj, method, args, proxy)    ← 前置处理
*         ├── intercept.invoke(obj, method, args, proxy)    ← 方法调用
*         ├── intercept.handleException(...)                 ← 异常处理
*         └── intercept.after(obj, method, args, proxy)     ← 后置处理
* }</pre>n(...)                 ← 异常处理
*         └── intercept.after(obj, method, args, proxy)     ← 后置处理
* }</pre>
*
* @param <T> 代理接口类型
* @author CH
* @since 2025/7/20
* @see java.lang.reflect.Proxy
* @see java.lang.reflect.InvocationHandler
 */
@SuppressWarnings("all")
@Spi("jdk")
public class JdkProxyFactory<T> implements com.chua.common.support.proxy.ProxyFactory<T> {

    /**
    * 单例实例，全局共享。
    *
    * <p>由于 JDK 动态代理工厂是无状态的，使用单例模式避免重复创建实例。</p>
     */
    public static final com.chua.common.support.proxy.ProxyFactory INSTANCE = new JdkProxyFactory();

    /**
    * 使用 JDK 动态代理创建代理对象。
    *
    * <p>将指定的目标接口和额外接口合并去重后，通过
    * {@link Proxy#newProxyInstance(ClassLoader, Class[], InvocationHandler)}
    * 创建代理实例。所有方法调用都会转发给 {@link JdkInvocationHandler}。</p>
    *
    * @param target      目标接口类型
    * @param interfaces  要代理的额外接口数组
    * @param classLoader 类加载器
    * @param intercept   方法拦截器
    * @return 代理对象实例
     */
    @Override
    public T createProxy(Class<T> target, Class<?>[] interfaces, ClassLoader classLoader,
            MethodIntercept<T> intercept) {
        return (T) ReflectUtils.newProxy(classLoader,
                ArrayUtils.mergeOfDistince(interfaces, target),
                new JdkInvocationHandler<>(intercept));
    }

    /**
    * JDK 动态代理调用处理器。
    *
    * <p>实现了 {@link InvocationHandler} 接口，在代理方法被调用时执行完整的拦截生命周期：
    * 前置处理 → 方法调用 → 异常处理（可选） → 后置处理。
    * 如果 {@link MethodIntercept#handleException(Object, Method, Object[], Object, Throwable)}
    * 返回非 空 值，则该值作为方法调用结果返回，异常不再传播。</p>
    *
    * @param <T> 代理接口类型
    * @author CH
    * @since 4.0.0
     */
    public static class JdkInvocationHandler<T> implements InvocationHandler {

        /**
        * 方法拦截器。
        *
        * <p>定义了代理方法调用的拦截逻辑，包括 before/invoke/after/handleException 四个扩展点。</p>
         */
        final MethodIntercept<T> intercept;

        /**
        * 创建 JDK 调用处理器。
        *
        * @param intercept 方法拦截器，定义拦截逻辑
         */
        public JdkInvocationHandler(MethodIntercept<T> intercept) {
            this.intercept = intercept;
        }

        /**
        * 处理代理方法调用。
        *
        * <p>当代理对象的任何方法被调用时，此方法被触发。它执行完整的拦截链：</p>
        * <ol>
        *   <li>{@link MethodIntercept#before(Object, Method, Object[], Object)} — 前置处理</li>
        *   <li>{@link MethodIntercept#invoke(Object, Method, Object[], Object)} — 拦截调用</li>
        *   <li>{@link MethodIntercept#handleException(Object, Method, Object[], Object, Throwable)} — 异常处理</li>
        *   <li>{@link MethodIntercept#after(Object, Method, Object[], Object)} — 后置处理（finally 中保证执行）</li>
        * </ol>
        *
        * @param proxy  代理实例
        * @param method 被调用的方法
        * @param args   方法参数
        * @return 方法调用结果
        * @throws Throwable 如果执行过程中发生异常
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 执行前置处理
            intercept.before(proxy, method, args, (T) proxy);

            try {
                // 执行方法调用
                return intercept.invoke(proxy, method, args, (T) proxy);
            } catch (Exception e) {
 // 执行异常处理，如果返回非 空 值则作为结果返回
                Object result = intercept.handleException(proxy, method, args, (T) proxy, e);
                if (result != null) {
                    return result;
                }
                throw e;
            } finally {
                // 执行后置处理（保证执行）
                intercept.after(proxy, method, args, (T) proxy);
            }
        }
    }
}
