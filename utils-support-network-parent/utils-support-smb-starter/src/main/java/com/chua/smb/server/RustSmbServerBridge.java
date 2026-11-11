package com.chua.smb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class RustSmbServerBridge {

    private static final Logger LOG = LoggerFactory.getLogger(RustSmbServerBridge.class);
    private static final String TARGET = "com.chua.nativesmb.support.RustSmbServerBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-smb:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private RustSmbServerBridge() {
    }

    static void loadLibrary() {
        Method method = require("loadLibrary");
        call(method);
        if (!Boolean.TRUE.equals(call(method("isLoaded")))) {
            throw new IllegalStateException("SMB 服务端能力不可用：请自行添加依赖 " + DEPENDENCY);
        }
    }

    static long start(String bindAddr, int port, String shareName, String rootPath,
                      String user, String password) {
        Object value = call(require("start", String.class, int.class, String.class, String.class,
                String.class, String.class), bindAddr, port, shareName, rootPath, user, password);
        return value instanceof Long handle ? handle : -1L;
    }

    static void stop(long handle) {
        call(require("stop", long.class), handle);
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("SMB 服务端能力不可用：请自行添加依赖 " + DEPENDENCY);
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
            throw new IllegalStateException("SMB 原生调用失败: " + e.getTargetException(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SMB 原生调用失败: " + e, e);
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
            synchronized (RustSmbServerBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, RustSmbServerBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，SMB 服务端能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
