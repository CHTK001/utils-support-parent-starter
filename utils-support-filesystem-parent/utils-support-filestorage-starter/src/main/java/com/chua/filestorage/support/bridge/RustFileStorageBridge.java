package com.chua.filestorage.support.bridge;

import com.chua.filestorage.support.operation.FileOperationSetting;
import com.chua.filestorage.support.spi.FileStorageFilterSetting;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * Rust 文件存储 native 库桥接类。
 *
 * <p>提供 JNI 接口调用 Rust 实现的高性能文件存储功能。
 * 若 native 库未加载，所有方法会自动降级。</p>
 *
 * <p>对应动态库：{@code rust_filestorage_processor}</p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public class RustFileStorageBridge {

    /** Library_name */
    private static final String LIBRARY_NAME = "rust_filestorage_processor";
    /** initialized */
    private static volatile boolean initialized = false;

    static {
        try {
            System.loadLibrary(LIBRARY_NAME);
            if (nativeInit()) {
                initialized = true;
                log.info("[FileStorage][Rust] Rust 文件存储库初始化成功，版本: {}", nativeGetVersion());
            } else {
                log.warn("[FileStorage][Rust] Rust 文件存储库初始化失败，将使用 JDK 实现");
            }
        } catch (Exception e) {
            log.warn("[FileStorage][Rust] 无法加载 Rust 文件存储库: {}", e.getMessage());
        }
    }

    private RustFileStorageBridge() {
        throw new UnsupportedOperationException();
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static native boolean nativeInit();

    private static native String nativeGetVersion();

    public static List<String> nativeCapabilities() {
        try {
            String caps = nativeGetCapabilities();
            return parseList(caps);
        } catch (UnsatisfiedLinkError e) {
            log.warn("[FileStorage][Rust] nativeCapabilities 调用失败", e);
            return Collections.emptyList();
        }
    }

    public static FileOperationSetting nativeParseParams(String paramsJson) {
        try {
            String json = nativeParseParams0(paramsJson);
            if (json == null || json.isBlank()) {
                return FileOperationSetting.builder().build();
            }
            return parseOperationJson(json);
        } catch (UnsatisfiedLinkError e) {
            log.warn("[FileStorage][Rust] nativeParseParams 调用失败", e);
            return FileOperationSetting.builder().build();
        }
    }

    public static List<String> nativeFilterCapabilities() {
        try {
            String caps = nativeGetFilterCapabilities();
            return parseList(caps);
        } catch (UnsatisfiedLinkError e) {
            log.warn("[FileStorage][Rust] nativeFilterCapabilities 调用失败", e);
            return Collections.emptyList();
        }
    }

    public static List<FileStorageFilterSetting.ImageFilterConfig> nativeGetFilterChain() {
        try {
            String json = nativeGetFilterChainJson();
            return parseFilterChainJson(json);
        } catch (UnsatisfiedLinkError e) {
            log.warn("[FileStorage][Rust] nativeGetFilterChain 调用失败", e);
            return Collections.emptyList();
        }
    }

    public static boolean nativeIsExcluded(String path, String extension) {
        try {
            return nativeIsExcluded0(path, extension);
        } catch (UnsatisfiedLinkError e) {
            log.warn("[FileStorage][Rust] nativeIsExcluded 调用失败", e);
            return false;
        }
    }

    private static native String nativeGetCapabilities();

    private static native String nativeParseParams0(String paramsJson);

    private static native String nativeGetFilterCapabilities();

    private static native String nativeGetFilterChainJson();

    private static native boolean nativeIsExcluded0(String path, String extension);

    private static List<String> parseList(String s) {
        if (s == null || s.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    private static FileOperationSetting parseOperationJson(String json) {
        FileOperationSetting.FileOperationSettingBuilder b = FileOperationSetting.builder();
        json = json.replaceAll("[{}\"]", "");
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = kv[0].trim(), v = kv[1].trim();
            if (v.isEmpty() || "null".equals(v)) {
                continue;
            }
            switch (k) {
                case "size" -> b.size(v);
                case "format" -> b.format(v);
                case "quality" -> b.quality(intOrNull(v));
                case "crop" -> b.crop(v);
                case "rotate" -> b.rotate(intOrNull(v));
                case "flip" -> b.flip(v);
                case "grayscale" -> b.grayscale(boolOrNull(v));
                case "blur" -> b.blur(floatOrNull(v));
                case "sharpen" -> b.sharpen(floatOrNull(v));
                case "autoOrient" -> b.autoOrient(boolOrNull(v));
                case "watermarkText" -> b.watermarkText(v);
                case "watermarkImage" -> b.watermarkImage(v);
                case "storageName" -> b.storageName(v);
                case "forceDownload" -> b.forceDownload(boolOrNull(v));
                case "pdfPage" -> b.pdfPage(intOrNull(v));
                case "pdfPageSize" -> b.pdfPageSize(v);
                case "pdfOrientation" -> b.pdfOrientation(v);
                default -> {
                }
            }
        }
        return b.build();
    }

    private static List<FileStorageFilterSetting.ImageFilterConfig> parseFilterChainJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new java.util.ArrayList<>();
        }
        java.util.List<FileStorageFilterSetting.ImageFilterConfig> r = new java.util.ArrayList<>();
        String inner = json.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        if (inner.isBlank()) {
            return r;
        }
        for (String obj : inner.split("\\}\\s*,\\s*\\{")) {
            obj = obj.replaceAll("[{}\"]", "").trim();
            String id = null;
            var params = new java.util.HashMap<String, Object>();
            for (String pair : obj.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) {
                    continue;
                }
                String k = kv[0].trim(), v = kv[1].trim();
                if (k.equals("id")) {
                    id = v;
                } else if (k.startsWith("param_")) {
                    params.put(k.substring(6), v);
                }
            }
            if (id != null) {
                r.add(new FileStorageFilterSetting.ImageFilterConfig(id, params));
            }
        }
        return r;
    }

    private static Integer intOrNull(String s) {
        try { return Integer.valueOf(s); } catch (Exception e) { return null; }
    }

    private static Long longOrNull(String s) {
        try { return Long.valueOf(s); } catch (Exception e) { return null; }
    }

    private static Float floatOrNull(String s) {
        try { return Float.valueOf(s); } catch (Exception e) { return null; }
    }

    private static Boolean boolOrNull(String s) {
        if (s == null) {
            return false;
        }
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }
}
