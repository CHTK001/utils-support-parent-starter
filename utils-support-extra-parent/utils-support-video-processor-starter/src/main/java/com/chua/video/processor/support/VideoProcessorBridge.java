package com.chua.video.processor.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class VideoProcessorBridge {

    private static final Logger LOG = LoggerFactory.getLogger(VideoProcessorBridge.class);
    private static final String TARGET = "com.chua.video.processor.support.bridge.VideoProcessorBridge";
    private static final String DEPENDENCY = "com.chua:utils-support-native-video-processor:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private VideoProcessorBridge() {
    }

    static boolean isLoaded() {
        return Boolean.TRUE.equals(call(method("isLoaded")));
    }

    static void ensureLoaded() {
        Method method = method("ensureLoaded");
        if (method == null) {
            throw new IllegalStateException("视频处理能力不可用：请自行添加依赖 " + DEPENDENCY);
        }
        call(method);
        if (!isLoaded()) {
            throw new IllegalStateException("视频处理原生库未加载：" + loadError() + "；请确认依赖 " + DEPENDENCY);
        }
    }

    static boolean transcodeToHls(String inputPath, String outputDir) {
        Object value = call(require("transcodeToHls", String.class, String.class), inputPath, outputDir);
        return Boolean.TRUE.equals(value);
    }

    static String getVersion() {
        Object value = call(require("getVersion"));
        return value instanceof String version ? version : null;
    }

    private static Throwable loadError() {
        return (Throwable) call(method("getLoadError"));
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("视频处理能力不可用：请自行添加依赖 " + DEPENDENCY);
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
            throw new IllegalStateException("视频处理原生调用失败: " + e.getTargetException(),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("视频处理原生调用失败: " + e, e);
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
            synchronized (VideoProcessorBridge.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, VideoProcessorBridge.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，视频处理能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
