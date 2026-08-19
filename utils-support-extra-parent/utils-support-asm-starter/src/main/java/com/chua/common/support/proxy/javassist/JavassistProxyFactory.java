package com.chua.common.support.proxy.javassist;

import com.chua.common.support.proxy.ProxyFactory;
import com.chua.common.support.proxy.intercept.MethodIntercept;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyObject;
import lombok.SneakyThrows;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Javassist 代理工厂，基于 Javassist 字节码增强技术创建类代理。
 *
 * <p>与 JDK 动态代理不同，Javassist 可以代理具体类（非接口），通过生成子类实现代理。
 * 适用于需要代理 POJO、Service 实现类等非接口类型的场景。</p>
 *
 * @param <T> 代理类型
 * @author CH
 * @since 2025/7/20
 */
@Spi("javassist")
@SuppressWarnings("ALL")
public class JavassistProxyFactory<T> implements ProxyFactory<T> {

    /**
     * 单例实例
     */
    public static final ProxyFactory INSTANCE = new JavassistProxyFactory();

    @Override
    @SneakyThrows
    /**
     * 创建Proxy
     * @param target target
     * @param interfaces interfaces
     * @param classLoader classLoader
     * @param intercept intercept
     */
    public T createProxy(Class<T> target, Class<?>[] interfaces, ClassLoader classLoader,
                        MethodIntercept<T> intercept) {
        javassist.util.proxy.ProxyFactory proxyFactory = new javassist.util.proxy.ProxyFactory();
        proxyFactory.setSuperclass(target);
        proxyFactory.setInterfaces(interfaces);

        Class<?> proxyClass = proxyFactory.createClass();
        Object newInstance = ClassUtils.newInstance(proxyClass);
        ProxyObject proxyObject = (ProxyObject) newInstance;

        proxyObject.setHandler(new MethodHandler() {
            @Override
            /** 调用 */
            public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable {
                intercept.before(self, thisMethod, args, (T) self);

                try {
                    return intercept.invoke(self, thisMethod, args, (T) self);
                } catch (Exception e) {
                    Object result = intercept.handleException(self, thisMethod, args, (T) self, e);
                    if (result != null) {
                        return result;
                    }
                    throw e;
                } finally {
                    intercept.after(self, thisMethod, args, (T) self);
                }
            }
        });

        return (T) newInstance;
    }
}
