package com.chua.spring.support.proxy.intercept;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseExecutor;
import com.chua.common.support.concurrent.collapse.CollapseExecutorFactory;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.MethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.AbstractMethodAnnotationIntercept;
import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spring.support.annotation.Collapsible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 折叠拦截器，处理 {@link Collapsible} 注解标注的方法。
 *
 * <p>将并发到达的"相同方法 + 深度相同实参"调用折叠为一次真实执行并广播结果，
 * 通过 SPI 加载 {@link CollapseExecutorFactory} 创建折叠执行器（默认实现位于
 * utils-support-collapse-starter）；未引入实现模块时自动降级为直接调用。</p>
 *
 * <p>每个解析后的执行器名称对应一个独立的折叠执行器（缓存于 {@link #executors}），
 * 执行器关闭随 Spring 容器销毁回调 {@link #destroy()} 触发。</p>
 *
 * @author CH
 * @since 2026/09/03
 */
@Slf4j
@Spi("com.chua.spring.support.annotation.Collapsible")
public class CollapsibleIntercept extends AbstractMethodAnnotationIntercept
        implements MethodAnnotationIntercept<Collapsible>, DisposableBean {

    /**
     * 折叠执行器工厂的 SPI 名称
     */
    private static final String DEFAULT_FACTORY_NAME = "collapse";

    /**
     * 按执行器名称缓存折叠执行器
     */
    private final Map<String, CollapseExecutor<InvocationKey, Object>> executors = new ConcurrentHashMap<>(16);

    @Override
    public Class<Collapsible> annotationType() {
        return Collapsible.class;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Object intercept(Collapsible annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
        CollapseExecutorFactory factory = ServiceProvider.of(CollapseExecutorFactory.class).getExtension(DEFAULT_FACTORY_NAME);
        if (factory == null) {
            log.warn("未找到折叠执行器工厂 SPI[collapse]，请引入 utils-support-collapse-starter，本次调用不折叠直接执行。");
            return invocation.proceed();
        }
        String name = resolveName(annotation.name(), proxyMethod);
        CollapseExecutor<InvocationKey, Object> executor = executors.computeIfAbsent(name,
                key -> createExecutor(key, annotation, proxyMethod, factory));
        InvocationKey invocationKey = new InvocationKey(proxyMethod.getMethod(),
                normalizeArgs(proxyMethod.getArgs()), invocation);
        return executor.execute(invocationKey);
    }

    /**
     * 创建并注册折叠执行器。
     *
     * @param name        执行器名称
     * @param annotation  折叠注解
     * @param proxyMethod 被拦截方法信息
     * @param factory     折叠执行器工厂
     * @return 折叠执行器实例
     */
    private CollapseExecutor<InvocationKey, Object> createExecutor(String name,
                                                                   Collapsible annotation,
                                                                   ProxyMethod proxyMethod,
                                                                   CollapseExecutorFactory factory) {
        CollapseConfig config = new CollapseConfig();
        config.setName(name);
        config.setWaitThreshold(resolveInt(annotation.waitThreshold(), 10, proxyMethod));
        config.setCollectingWaitTime(resolveLong(annotation.collectingWaitTime(), 0, proxyMethod));
        return factory.create(config, keys -> {
            InvocationKey first = keys.iterator().next();
            return first.invocation.proceed();
        });
    }

    /**
     * 实参深度归一化：数组（含多维、基本类型数组）递归转换为列表，
     * 使实参相等比较退化为逐元素 equals，保证数组参数可正确折叠分组。
     *
     * @param args 原始实参
     * @return 归一化后的实参列表
     */
    private static List<Object> normalizeArgs(Object[] args) {
        List<Object> normalized = new ArrayList<>(args.length);
        for (Object arg : args) {
            normalized.add(normalize(arg));
        }
        return normalized;
    }

    /**
     * 归一化单个实参，数组递归展开为列表。
     *
     * @param arg 原始实参
     * @return 归一化后的实参
     */
    private static Object normalize(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg.getClass().isArray()) {
            int length = Array.getLength(arg);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(normalize(Array.get(arg, i)));
            }
            return list;
        }
        return arg;
    }

    @Override
    public void destroy() {
        for (CollapseExecutor<InvocationKey, Object> executor : executors.values()) {
            try {
                executor.close();
            } catch (Exception e) {
                log.warn("关闭折叠执行器失败：{}", e.getMessage());
            }
        }
        executors.clear();
    }

    /**
     * 折叠调用标识：方法 + 深度归一化后的实参，equals/hashCode 不包含调用上下文。
     *
     * <p>invocation 仅作为同组内真正执行目标方法（proceed）的载体，不参与相等比较。</p>
     */
    private static final class InvocationKey {

        /**
         * 目标方法
         */
        private final Method method;

        /**
         * 深度归一化后的实参
         */
        private final List<Object> normalizedArgs;

        /**
         * 调用上下文（不参与相等比较）
         */
        private final MethodInvocation invocation;

        private InvocationKey(Method method, List<Object> normalizedArgs, MethodInvocation invocation) {
            this.method = method;
            this.normalizedArgs = normalizedArgs;
            this.invocation = invocation;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof InvocationKey)) {
                return false;
            }
            InvocationKey that = (InvocationKey) obj;
            return Objects.equals(method, that.method)
                    && Objects.equals(normalizedArgs, that.normalizedArgs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, normalizedArgs);
        }
    }
}
