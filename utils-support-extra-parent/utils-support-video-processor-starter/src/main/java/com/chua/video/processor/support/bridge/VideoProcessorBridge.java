package com.chua.video.processor.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import java.nio.file.Path;
/**
 * @author CH
 */

public class VideoProcessorBridge {

    /** Library_name */
    private static final String LIBRARY_NAME = "video_processor";
    private static volatile boolean loaded = false;
    private static volatile Throwable loadError = null;

    static {
        try {
            String libFile = NativeUtils.getLibraryFileName(LIBRARY_NAME);
            NativeLoader loader = NativeLoader.of(LIBRARY_NAME)
                    .from(VideoProcessorBridge.class.getClassLoader())
                    .glob(libFile)
                    .toTarget(Path.of(System.getProperty("java.io.tmpdir"), LIBRARY_NAME));
            loader.load();
            loaded = true;
        } catch (Throwable e) {
            loadError = e;
            loaded = false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static Throwable getLoadError() {
        return loadError;
    }

    public static void ensureLoaded() {
        if (!loaded) {
            throw new UnsupportedOperationException(
                    "Rust VideoProcessor native library not loaded: " +
                            (loadError != null ? loadError.getMessage() : "unknown error"));
        }
    }

    public static native boolean transcodeToHls(String inputPath, String outputDir);

    public static native String getVersion();
}
