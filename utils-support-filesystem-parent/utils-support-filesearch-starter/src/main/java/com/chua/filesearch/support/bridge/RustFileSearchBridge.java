package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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

    // JNI: 直接返回 JSON 字符串（不再用回调）
    private static native String _searchByName(String rootPath, String namePattern, int maxResults);
    private static native String _getTree(String rootPath, int maxDepth, int maxResults);
    private static native String getVersion();

    public static List<FileResultData> searchByName(String rootPath, String namePattern, int maxResults) {
        loadLibrary();
        String json = _searchByName(rootPath, namePattern, maxResults);
        return parseResults(json);
    }

    public static List<FileResultData> getTree(String rootPath, int maxDepth, int maxResults) {
        loadLibrary();
        String json = _getTree(rootPath, maxDepth, maxResults);
        return parseResults(json);
    }

    public static String getVersion() {
        loadLibrary();
        return _getVersion();
    }

    private static native String _getVersion();

    private static List<FileResultData> parseResults(String json) {
        List<FileResultData> results = new ArrayList<>();
        if (json == null || json.isEmpty()) return results;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.path("results");
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    results.add(new FileResultData(
                        item.path("path").asText(""),
                        item.path("size").asLong(0),
                        item.path("modified").asLong(0),
                        false,
                        item.path("ext").asText(""),
                        0, 0, 0, 0
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse filesearch result: {}", e.getMessage());
        }
        return results;
    }
}
