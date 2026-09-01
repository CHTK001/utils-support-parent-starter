package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public final class RustFileSearchBridge {

    private static volatile boolean loaded = false;
    private static final Object LOAD_LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record FileResultData(String path, long size, long lastModified,
                                 boolean isDirectory, String extension, int attributes,
                                 long usnRecordId, long parentFileId, long allocatedSize) {}

    public static synchronized void loadLibrary() {
        if (loaded) return;
        synchronized (LOAD_LOCK) {
            if (loaded) return;
            try {
                NativeLoader.of("file-search")
                        .toTarget(NativeUtils.tempRoot().resolve("file-search"))
                        .glob("*file_search*")
                        .load();
                loaded = true;
                log.info("Rust file search native library loaded.");
            } catch (Throwable e) {
                log.error("Failed to load Rust file search: {}", e.getMessage());
                loaded = false;
            }
        }
    }

    public static boolean isLoaded() { return loaded; }

    // ===== 新 JNI API：返回 JSON 字符串 =====
    private static native String _searchByName(String rootPath, String namePattern, int maxResults);
    private static native String _getTree(String rootPath, int maxDepth, int maxResults);
    private static native String _getVersion();
    private static native void _cancel();

    // ===== 公共 API =====
    public static String getVersion() {
        loadLibrary();
        return _getVersion();
    }

    public static void cancel() { _cancel(); }

    public static int searchByName(String rootPath, String namePattern, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        String json = _searchByName(rootPath, namePattern, maxResults);
        return applyResults(json, callback);
    }

    public static int getTree(String rootPath, int maxDepth, int maxResults,
                               Consumer<FileResultData> callback) {
        loadLibrary();
        String json = _getTree(rootPath, maxDepth, maxResults);
        return applyResults(json, callback);
    }

    public static int searchBySize(String rootPath, long minSize, long maxSize, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        return -1; // not implemented
    }

    public static int searchByPath(String rootDir, String pathPattern, int maxResults,
                                    Consumer<FileResultData> callback) {
        loadLibrary();
        return -1; // not implemented
    }

    private static int applyResults(String json, Consumer<FileResultData> callback) {
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
            log.warn("Failed to parse filesearch result: {}", e.getMessage());
            return -1;
        }
    }
}
