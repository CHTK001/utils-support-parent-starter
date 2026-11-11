package com.chua.image.support.compression.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ImageOptBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ImageOptBridge.class);
    private static final String TARGET = "com.chua.nativeimageopt.support.ImageOptBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-imageopt:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private ImageOptBridge() {
    }

    record Quantized(byte[] palette, int colors, byte[] indices) {
    }

    static void load() {
        call(method("load"));
    }

    static boolean isLoaded() {
        return Boolean.TRUE.equals(call(method("isLoaded")));
    }

    static String lastError() {
        Object value = call(method("lastError"));
        return value instanceof String message ? message : "未引入依赖 " + DEPENDENCY;
    }

    static byte[] zlibCompress(byte[] data, int level) {
        Object value = call(require("zlibCompress", byte[].class, int.class), data, level);
        return value instanceof byte[] compressed ? compressed : null;
    }

    static Quantized quantize(byte[] rgba, int width, int height, int maxColors, int ditherLevel, int speed) {
        Object value = call(require("quantize", byte[].class, int.class, int.class, int.class,
                int.class, int.class), rgba, width, height, maxColors, ditherLevel, speed);
        if (value == null) {
            return null;
        }
        return new Quantized((byte[]) read(value, "palette"),
                ((Number) read(value, "colors")).intValue(),
                (byte[]) read(value, "indices"));
    }

    private static Object read(Object record, String accessor) {
        try {
            return record.getClass().getMethod(accessor).invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("native 量化结果读取失败: " + accessor, e);
        }
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("图片压缩 native 能力不可用：请自行添加依赖 " + DEPENDENCY);
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
            throw new IllegalStateException("图片压缩原生调用失败: " + e.getTargetException(),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("图片压缩原生调用失败: " + e, e);
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
            synchronized (ImageOptBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, ImageOptBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，libdeflate/libimagequant 加速不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
