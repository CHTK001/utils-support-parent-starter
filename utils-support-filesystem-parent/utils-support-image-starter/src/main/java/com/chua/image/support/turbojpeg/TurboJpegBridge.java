package com.chua.image.support.turbojpeg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class TurboJpegBridge {

    private static final Logger LOG = LoggerFactory.getLogger(TurboJpegBridge.class);
    private static final String TARGET = "com.chua.nativeturbojpeg.support.TurboJpegBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-libjpeg-turbo:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    static final int TJPF_RGB = 0;
    static final int TJPF_GRAY = 6;
    static final int TJSAMP_420 = 2;
    static final int TJSAMP_GRAY = 3;

    private TurboJpegBridge() {
    }

    record Decoded(byte[] pixels, int width, int height, int pitch) {
    }

    static void load() {
        call(method("load"));
    }

    static boolean isLoaded() {
        return Boolean.TRUE.equals(call(method("isLoaded")));
    }

    static Throwable getLoadError() {
        Method method = method("version");
        if (method == null) {
            return new IllegalStateException("请自行添加依赖 " + DEPENDENCY);
        }
        try {
            method.invoke(null);
            return null;
        } catch (InvocationTargetException e) {
            return e.getTargetException();
        } catch (Throwable e) {
            return e;
        }
    }

    static int[] probe(byte[] jpeg) {
        Object value = call(require("probe", byte[].class), jpeg);
        return value instanceof int[] probed ? probed : null;
    }

    static byte[] compress(byte[] pixels, int width, int height, int pitch, int pixelFormat,
                           int quality, int subsamp, int flags) {
        Object value = call(require("compress", byte[].class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class),
                pixels, width, height, pitch, pixelFormat, quality, subsamp, flags);
        return value instanceof byte[] compressed ? compressed : null;
    }

    static Decoded decompress(byte[] jpeg, int pixelFormat, int flags) {
        Object value = call(require("decompress", byte[].class, int.class, int.class),
                jpeg, pixelFormat, flags);
        if (value == null) {
            return null;
        }
        return new Decoded((byte[]) read(value, "pixels"),
                ((Number) read(value, "width")).intValue(),
                ((Number) read(value, "height")).intValue(),
                ((Number) read(value, "pitch")).intValue());
    }

    private static Object read(Object record, String accessor) {
        try {
            return record.getClass().getMethod(accessor).invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("turbojpeg 解码结果读取失败: " + accessor, e);
        }
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("libjpeg-turbo native 能力不可用：请自行添加依赖 " + DEPENDENCY);
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
            throw new IllegalStateException("turbojpeg 原生调用失败: " + e.getTargetException(),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("turbojpeg 原生调用失败: " + e, e);
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
            synchronized (TurboJpegBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, TurboJpegBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，libjpeg-turbo 加速不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
