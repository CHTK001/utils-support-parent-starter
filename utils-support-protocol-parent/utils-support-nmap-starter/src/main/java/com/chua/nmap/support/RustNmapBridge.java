package com.chua.nmap.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class RustNmapBridge {

    private static final Logger LOG = LoggerFactory.getLogger(RustNmapBridge.class);
    private static final String TARGET = "com.chua.nmap.support.bridge.RustNmapBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-nmap:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private RustNmapBridge() {
    }

    static void ensureLoaded() {
        Method method = require("ensureLoaded");
        call(method);
        if (!Boolean.TRUE.equals(call(method("isLoaded")))) {
            throw new IllegalStateException("Nmap 扫描能力不可用：请自行添加依赖 " + DEPENDENCY);
        }
    }

    static int scanSingleTcpPort(String host, int port, int timeout) {
        Object value = call(require("scanSingleTcpPort", String.class, int.class, int.class),
                host, port, timeout);
        return value instanceof Integer code ? code : -1;
    }

    static String scanTcpPortRange(String host, int startPort, int endPort, int timeout, int concurrency) {
        Object value = call(require("scanTcpPortRange", String.class, int.class, int.class,
                int.class, int.class), host, startPort, endPort, timeout, concurrency);
        return value instanceof String json ? json : null;
    }

    static String pingHost(String host, int timeout) {
        Object value = call(require("pingHost", String.class, int.class), host, timeout);
        return value instanceof String json ? json : null;
    }

    static String scanSubnet(String subnet, int timeout, int concurrency) {
        Object value = call(require("scanSubnet", String.class, int.class, int.class),
                subnet, timeout, concurrency);
        return value instanceof String json ? json : null;
    }

    static String detectService(String host, int port, int timeout) {
        Object value = call(require("detectService", String.class, int.class, int.class),
                host, port, timeout);
        return value instanceof String json ? json : null;
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("Nmap 扫描能力不可用：请自行添加依赖 " + DEPENDENCY);
        }
        return method;
    }

    private static Object call(Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Nmap 原生调用失败: " + e.getTargetException(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Nmap 原生调用失败: " + e, e);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        Class<?> target = type();
        if (target == null) {
            return null;
        }
        try {
            return target.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            LOG.warn("native 模块 {} 缺少方法 {}，请确认依赖 {} 的版本", TARGET, name, DEPENDENCY);
            return null;
        }
    }

    private static Class<?> type() {
        if (!resolved) {
            synchronized (RustNmapBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, RustNmapBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，Nmap 扫描能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
