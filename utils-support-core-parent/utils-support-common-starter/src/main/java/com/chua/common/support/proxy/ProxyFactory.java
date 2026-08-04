package com.chua.common.support.proxy;

import com.chua.common.support.proxy.intercept.MethodIntercept;
import org.jspecify.annotations.NullUnmarked;

/**
 * 代理工厂接口，定义了代理对象的统一创建方法。
 *
 * <p>本接口采用<b>工厂方法模式（Factory Method Pattern）</b>，提供了统一的代理对象创建标准。
 * 不同的代理技术（JDK 动态代理、Javassist 代理等）通过实现此接口来接入代理框架。</p>
 *
 * <p><b>已支持的实现：</b></p>
 * <ul>
 *   <li>{@link JdkProxyFactory} — JDK 动态代理，支持接口类型代理（SPI 名称：{@code "jdk"}）</li>
 *   <li>JavassistProxyFactory — Javassist 代理，支持类类型代理（SPI 名称：{@code "javassist"}，需 javassist 依赖）</li>
 * </ul>
 *
 * <p>通过 SPI 机制可以在不修改代码的情况下切换代理实现。
 * {@link DefaultProxyProvider} 会根据目标类型自动选择合适的工厂：
 * 接口→JDK 动态代理，类→Javassist 代理。</p>
 *
 * @param <T> 代理接口类型
 * @author CH
 * @since 2025/7/20
 * @see JdkProxyFactory
 * @see DefaultProxyProvider
 * @see com.chua.common.support.spi.ServiceProvider
 */
@NullUnmarked
public interface ProxyFactory<T> {

    /**
     * 创建代理对象。
     *
     * <p>根据指定的目标类型、接口数组、类加载器和方法拦截器创建代理实例。
     * 代理对象的所有方法调用都会被 {@link MethodIntercept} 拦截并处理。</p>
     *
     * <p><b>参数说明：</b></p>
     * <ul>
     *   <li>{@code target} — 目标类型，决定了代理对象的类型签名</li>
     *   <li>{@code interfaces} — 代理对象需要实现的额外接口数组，可与 target 不同</li>
     *   <li>{@code classLoader} — 用于定义代理类的类加载器</li>
     *   <li>{@code intercept} — 方法拦截器，定义方法调用的拦截逻辑</li>
     * </ul>
     *
     * @param target      目标类型（接口或类），如 {@code Service.class}
     * @param interfaces  要代理的额外接口数组，可为空数组
     * @param classLoader 类加载器，用于定义代理类
     * @param intercept   方法拦截器，代理方法调用时触发
     * @return 代理对象实例，类型为 {@code T}
     */
    T createProxy(Class<T> target, Class<?>[] interfaces, ClassLoader classLoader, MethodIntercept<T> intercept);
}
