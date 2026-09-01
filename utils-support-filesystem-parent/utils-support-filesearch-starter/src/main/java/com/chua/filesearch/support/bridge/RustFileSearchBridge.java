package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

public final class RustFileSearchBridge {

    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    public record FileResultData(
            String path, long size, long lastModified, boolean isDirectory,
            String extension, int attributes, long usnRecordId,
            long parentFileId, long allocatedSize) {}

    public static synchronized void loadLibrary() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                NativeLoader.of("file-search")
                        .toTarget(NativeUtils.tempRoot().resolve("file-search"))
                        .glob("*file_search*")
                        .load();
                loaded = true;
                log.info("[filesearch] native library loaded");
            } catch (Throwable e) {
                log.warn("[filesearch] load failed: {}", e.getMessage());
                loaded = false;
            }
        }
    }

    public static boolean isLoaded() { return loaded; }

    // JNI native ???? Rust DLL ???????
    private static native int searchByName(String root, String pattern, int max, Consumer<FileResultData> cb);
    private static native int getTree(String root, int depth, int max, Consumer<FileResultData> cb);
    public static native String getVersion();
    private static native void cancel();

    // ===== ?? API =====
    public static int searchByNameSafe(String rootPath, String namePattern, int maxResults,
                                        Consumer<FileResultData> callback) {
        loadLibrary();
        return searchByName(rootPath, namePattern, maxResults, callback);
    }

    public static int getTreeSafe(String rootPath, int maxDepth, int maxResults,
                                   Consumer<FileResultData> callback) {
        loadLibrary();
        return getTree(rootPath, maxDepth, maxResults, callback);
    }
}
