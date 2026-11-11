package com.chua.metrics.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class MetricsNativeLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsNativeLibrary.class);
    private static final String TARGET = "com.chua.nativemetrics.support.MetricsNativeLibrary";
    private static final String DEPENDENCY = "com.chua:utils-support-native-metrics:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private final Object delegate;

    private MetricsNativeLibrary(Object delegate) {
        this.delegate = delegate;
    }

    static MetricsNativeLibrary create() {
        Class<?> target = type();
        if (target == null) {
            return null;
        }
        try {
            Object instance = target.getMethod("create").invoke(null);
            return instance == null ? null : new MetricsNativeLibrary(instance);
        } catch (InvocationTargetException e) {
            LOG.warn("metrics 原生库实例化失败: {}", e.getTargetException().toString());
            return null;
        } catch (ReflectiveOperationException e) {
            LOG.warn("metrics 原生库实例化失败: {}", e.toString());
            return null;
        }
    }

    void start(long intervalMs) {
        invoke("start", new Class<?>[]{long.class}, intervalMs);
    }

    String poll() {
        Object value = invoke("poll", new Class<?>[0]);
        return value instanceof String json ? json : null;
    }

    void close() {
        invoke("close", new Class<?>[0]);
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = delegate.getClass().getMethod(name, parameterTypes);
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            LOG.warn("metrics 原生调用 {} 失败: {}", name, e.getTargetException().toString());
            return null;
        } catch (ReflectiveOperationException e) {
            LOG.warn("metrics 原生调用 {} 失败: {}", name, e.toString());
            return null;
        }
    }

    private static Class<?> type() {
        if (!resolved) {
            synchronized (MetricsNativeLibrary.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, MetricsNativeLibrary.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，指标采集能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
