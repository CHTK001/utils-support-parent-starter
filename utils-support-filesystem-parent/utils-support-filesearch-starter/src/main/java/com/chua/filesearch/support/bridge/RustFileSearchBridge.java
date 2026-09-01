package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class RustFileSearchBridge {

    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                System.out.println("[filesearch] native library loaded");
            } catch (Throwable e) {
                System.err.println("[filesearch] load failed: " + e.getMessage());
                loaded = false;
            }
        }
    }

    public static boolean isLoaded() { return loaded; }

    // ===== JNI native ???????? Rust DLL ???????=====
    // Rust: fn searchByName -> c_int   (returns count of matched files)
    private static native int _jni_searchByName(String root, String pattern, int max, Consumer<FileResultData> cb);
    private static native int _jni_getTree(String root, int depth, int max, Consumer<FileResultData> cb);
    private static native String _jni_getVersion();
    private static native void _jni_cancel();
    // Direct JSON access (no callback)
    private static native String _raw_searchByName(String root, String pattern, int max);
    private static native String _raw_getTree(String root, int depth, int max);

    // ===== ?? API =====
    public static String getVersion() {
        loadLibrary();
        return _jni_getVersion();
    }

    public static void cancel() { _jni_cancel(); }

    public static int searchByName(String rootPath, String namePattern, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        // Use JSON API for reliable results (callback would crash due to CString lifetime)
        String json = _raw_searchByName(rootPath, namePattern, maxResults);
        return applyJson(json, callback);
    }

    public static int getTree(String rootPath, int maxDepth, int maxResults,
                               Consumer<FileResultData> callback) {
        loadLibrary();
        String json = _raw_getTree(rootPath, maxDepth, maxResults);
        return applyJson(json, callback);
    }

    public static int searchBySize(String rootPath, long minSize, long maxSize, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        return -1;
    }

    public static int searchByPath(String rootDir, String pathPattern, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        return -1;
    }

    private static int applyJson(String json, Consumer<FileResultData> callback) {
        if (json == null || json.isEmpty()) return -1;
        try {
            JsonNode root = MAPPER.readTree(json);
            int rc = root.path("rc").asInt(-1);
            JsonNode arr = root.path("results");
            if (arr.isArray() && callback != null) {
                for (JsonNode item : arr) {
                    callback.accept(new FileResultData(
                        item.path("path").asText(""),
                        item.path("size").asLong(0),
                        item.path("modified").asLong(0),
                        false,
                        item.path("ext").asText(""),
                        0, 0, 0, 0
                    ));
                }
            }
            return rc;
        } catch (Exception e) {
            System.err.println("[filesearch] parse error: " + e.getMessage());
            return -1;
        }
    }
}
