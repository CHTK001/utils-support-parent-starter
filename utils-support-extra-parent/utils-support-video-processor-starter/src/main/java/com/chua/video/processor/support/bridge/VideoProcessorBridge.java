package com.chua.video.processor.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import java.nio.file.Path;
/**
 * @author CH
 * @since 4.0.0
 */

public class VideoProcessorBridge {

    /** 图书馆_名称 */
    private static final String LIBRARY_NAME = "video_processor";
    /** 加载 */
    private static volatile boolean loaded = false;
    /** 加载错误 */
    private static volatile Throwable loadError = null;

    static {
        try {
            String libFile = NativeUtils.getLibraryFileName(LIBRARY_NAME);
            NativeLoader loader = NativeLoader.of(LIBRARY_NAME)
                    .from(VideoProcessorBridge.class.getClassLoader())
                    .glob(libFile)
                    .toTarget(NativeUtils.tempRoot().resolve(LIBRARY_NAME));
            loader.load();
            loaded = true;
        } catch (Throwable e) {
            loadError = e;
            loaded = false;
        }
    }

    /**
    * 是否加载
    *
    * @return 是否加载的结果
    */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 获取加载记录错误
     *
     * @return 获取加载错误的结果
     */
    public static Throwable getLoadError() {
        return loadError;
    }

    /** ensure加载 */
    public static void ensureLoaded() {
        if (!loaded) {
            throw new UnsupportedOperationException(
                    "Rust VideoProcessor native library not loaded: " +
                            (loadError != null ? loadError.getMessage() : "unknown error"));
        }
    }

    /**
    * transcode转为hls
    *
    * @param inputPath 输入路径
    * @param outputDir 输出dir
    * @return transcode转为hls的结果
    */
    public static native boolean transcodeToHls(String inputPath, String outputDir);

    /**
     * 获取版本
     *
     * @return 获取版本的结果
     */
    public static native String getVersion();
}
