package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.annotations.Spi;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;

/**
* {@link Cacheable} 注解的 SPI 拦截器，使 Invoker 的 Proxy 支持缓存。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("org.springframework.cache.annotation.Cacheable")
public class CacheableIntercept implements MethodAnnotationIntercept<Cacheable> {

    @Override
    /** 注解类型 */
    public Class<Cacheable> annotationType() {
        return Cacheable.class;
    }

    @Override
    /** 订单 */
    public int order() {
        return 200;
    }

    @Override
    /** Intercept */
    public Object intercept(Cacheable annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        return invocation.proceed();
    }
}