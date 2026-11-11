package com.chua.ffmpeg.rust.support.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class NativeFFmpeg {

    private static final Logger LOG = LoggerFactory.getLogger(NativeFFmpeg.class);
    private static final String TARGET = "com.chua.nativeffmpeg.support.NativeFFmpeg";
    private static final String CALLBACK_TYPE = TARGET + "$FrameCallback";
    private static final String DEPENDENCY = "com.chua:utils-support-native-ffmpeg:4.0.0.43";

    private static volatile boolean resolved;
    private static volatile Class<?> type;

    private NativeFFmpeg() {
    }

    @FunctionalInterface
    interface FrameCallback {

        void onFrame(long frameNumber, long timestampMs, int width, int height,
                     String codec, double fps, boolean keyFrame);
    }

    static boolean isLoaded() {
        return getLoadError() == null;
    }

    static Throwable getLoadError() {
        Method method = method("getVersion");
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

    static String getVersion() {
        Object value = call(require("getVersion"));
        return value instanceof String version ? version : null;
    }

    static int pushStream(String inputUrl, String streamUrl, String videoCodec, String audioCodec,
                          int width, int height, int fps) {
        Object value = call(require("pushStream", String.class, String.class, String.class,
                String.class, int.class, int.class, int.class),
                inputUrl, streamUrl, videoCodec, audioCodec, width, height, fps);
        return value instanceof Integer code ? code : -1;
    }

    static int pushStreamWithCallback(String inputUrl, String streamUrl, String videoCodec,
                                      String audioCodec, int width, int height, int fps,
                                      FrameCallback callback) {
        Object value = call(require("pushStreamWithCallback", String.class, String.class,
                String.class, String.class, int.class, int.class, int.class, callbackType()),
                inputUrl, streamUrl, videoCodec, audioCodec, width, height, fps,
                toNativeCallback(callback));
        return value instanceof Integer code ? code : -1;
    }

    static int pullStream(String streamUrl, String outputPath, double duration) {
        Object value = call(require("pullStream", String.class, String.class, double.class),
                streamUrl, outputPath, duration);
        return value instanceof Integer code ? code : -1;
    }

    static int pullStreamWithCallback(String streamUrl, String outputPath, double duration,
                                      FrameCallback callback) {
        Object value = call(require("pullStreamWithCallback", String.class, String.class,
                double.class, callbackType()), streamUrl, outputPath, duration,
                toNativeCallback(callback));
        return value instanceof Integer code ? code : -1;
    }

    static double getDuration(String inputUrl) {
        Object value = call(require("getDuration", String.class), inputUrl);
        return value instanceof Double duration ? duration : -1D;
    }

    static int convertFile(String inputUrl, String outputPath, String videoCodec, String audioCodec,
                           int width, int height, int fps, double startTime, double duration,
                           boolean removeVideo, boolean removeAudio) {
        Object value = call(require("convertFile", String.class, String.class, String.class,
                String.class, int.class, int.class, int.class, double.class, double.class,
                boolean.class, boolean.class), inputUrl, outputPath, videoCodec, audioCodec,
                width, height, fps, startTime, duration, removeVideo, removeAudio);
        return value instanceof Integer code ? code : -1;
    }

    static int captureFrame(String inputUrl, long timestampMs, String outputPath) {
        Object value = call(require("captureFrame", String.class, long.class, String.class),
                inputUrl, timestampMs, outputPath);
        return value instanceof Integer code ? code : -1;
    }

    static int concatFiles(String inputPaths, String outputPath) {
        Object value = call(require("concatFiles", String.class, String.class), inputPaths, outputPath);
        return value instanceof Integer code ? code : -1;
    }

    static String getMediaInfo(String inputUrl) {
        Object value = call(require("getMediaInfo", String.class), inputUrl);
        return value instanceof String json ? json : null;
    }

    static int rotate(String inputUrl, String outputPath, int angle) {
        Object value = call(require("rotate", String.class, String.class, int.class),
                inputUrl, outputPath, angle);
        return value instanceof Integer code ? code : -1;
    }

    static int addWatermark(String inputUrl, String watermarkPath, String outputPath, int x, int y) {
        Object value = call(require("addWatermark", String.class, String.class, String.class,
                int.class, int.class), inputUrl, watermarkPath, outputPath, x, y);
        return value instanceof Integer code ? code : -1;
    }

    static int imagesToVideo(String imageDir, String outputPath, int fps, String imagePattern) {
        Object value = call(require("imagesToVideo", String.class, String.class, int.class,
                String.class), imageDir, outputPath, fps, imagePattern);
        return value instanceof Integer code ? code : -1;
    }

    private static Class<?> callbackType() {
        Class<?> iface;
        try {
            iface = Class.forName(CALLBACK_TYPE, false, NativeFFmpeg.class.getClassLoader());
        } catch (Throwable e) {
            throw new IllegalStateException("ffmpeg 帧回调类型不可用：请自行添加依赖 " + DEPENDENCY, e);
        }
        return iface;
    }

    private static Object toNativeCallback(FrameCallback callback) {
        if (callback == null) {
            return null;
        }
        Class<?> iface = callbackType();
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    if ("onFrame".equals(method.getName()) && args != null && args.length == 7) {
                        callback.onFrame(((Number) args[0]).longValue(),
                                ((Number) args[1]).longValue(),
                                ((Number) args[2]).intValue(),
                                ((Number) args[3]).intValue(),
                                (String) args[4],
                                ((Number) args[5]).doubleValue(),
                                Boolean.TRUE.equals(args[6]));
                    }
                    return null;
                });
    }

    private static Method require(String name, Class<?>... parameterTypes) {
        Method method = method(name, parameterTypes);
        if (method == null) {
            throw new IllegalStateException("ffmpeg 原生能力不可用：请自行添加依赖 " + DEPENDENCY);
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
            throw new IllegalStateException("ffmpeg 原生调用失败: " + e.getTargetException(),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ffmpeg 原生调用失败: " + e, e);
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
            synchronized (NativeFFmpeg.class) {
                if (!resolved) {
                    try {
                        type = Class.forName(TARGET, true, NativeFFmpeg.class.getClassLoader());
                    } catch (Throwable e) {
                        LOG.warn("未引入 native 模块 {}，ffmpeg 原生能力不可用；请自行添加依赖 {}",
                                TARGET, DEPENDENCY);
                    }
                    resolved = true;
                }
            }
        }
        return type;
    }
}
