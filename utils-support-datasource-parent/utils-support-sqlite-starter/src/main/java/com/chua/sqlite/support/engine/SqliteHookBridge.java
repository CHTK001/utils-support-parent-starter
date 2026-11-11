package com.chua.sqlite.support.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class SqliteHookBridge {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteHookBridge.class);
    private static final String TARGET = "com.chua.nativesqlite.support.SqliteHookBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-sqlite:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private SqliteHookBridge() {
    }

    static boolean isAvailable() {
        return Boolean.TRUE.equals(call(method("isAvailable")));
    }

    static MethodHandle hookOpen() {
        return (MethodHandle) call(method("hookOpen"));
    }

    static MethodHandle hookPoll() {
        return (MethodHandle) call(method("hookPoll"));
    }

    static MethodHandle hookExec() {
        return (MethodHandle) call(method("hookExec"));
    }

    static MethodHandle hookClose() {
        return (MethodHandle) call(method("hookClose"));
    }

    static MethodHandle hookOpenAsync() {
        return (MethodHandle) call(method("hookOpenAsync"));
    }

    static MethodHandle hookExecAsync() {
        return (MethodHandle) call(method("hookExecAsync"));
    }

    static MethodHandle hookCloseAsync() {
        return (MethodHandle) call(method("hookCloseAsync"));
    }

    static MemorySegment eventCallbackStub(MethodHandle target, Arena arena) {
        Object value = call(method("eventCallbackStub", MethodHandle.class, Arena.class), target, arena);
        return value instanceof MemorySegment segment ? segment : MemorySegment.NULL;
    }

    private static Object call(Method method, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("SQLite CDC 原生调用失败: " + e.getTargetException(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SQLite CDC 原生调用失败: " + e, e);
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
            synchronized (SqliteHookBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, SqliteHookBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，SQLite CDC 能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
