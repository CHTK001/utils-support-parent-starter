package com.chua.datarecovery.support;

import java.util.List;
import java.util.concurrent.CompletableFuture;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class DataRecovery {

    static {
        com.chua.common.support.utils.NativeUtils.loadFromClasspath("data_recovery_ffi");
    }

    private final String devicePath;

    private DataRecovery(String devicePath) {
        this.devicePath = devicePath;
    }

    public static DataRecovery of(String devicePath) {
        return new DataRecovery(normalizeDevicePath(devicePath));
    }

    private static String normalizeDevicePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String lower = path.toLowerCase();
        if (lower.startsWith("\\\\.\\") || lower.startsWith("\\\\?\\") || lower.startsWith("/dev/")) {
            return path;
        }
        // 仅对裸盘符（如 "F:"、"F:\"）转换为原始设备路径 \\.\F:
        // 完整路径（如 "C:\Users\..."）保持原样，供 walkdir 等模式使用
        if (lower.matches("^[a-z]:\\\\?$")) {
            return "\\\\.\\" + path.substring(0, 1).toUpperCase() + ":";
        }
        return path;
    }

    public DataRecovery callback(RecoveryCallback callback) {
        this.callback = callback;
        return this;
    }

    private RecoveryCallback callback;

    public static interface RecoveryCallback {
        void onProgress(String stage, int percent);
        void onFileFound(FileEntry entry);
        void onRecovered(String path, long bytes);
        void onError(String error);
    }

    public static class FileEntry {
        public String name;
        public String path;
        public long sizeBytes;
        public long modifiedTimestamp;
        public long deletedTimestamp;
        public int recoveryScore;
        public String carvedSignature;
    }

    public static class ScanResult {
        public boolean success;
        public int filesScanned;
        public int filesFound;
        public String message;
        public FileEntry[] entries;
    }

    public static class DeleteResult {
        public boolean success;
        public long bytesOverwritten;
        public int passesCompleted;
        public String message;
    }

    public static class RecoverResult {
        public int successCount;
        public int failedCount;
        public FailedItem[] failedList;
        public long totalBytesWritten;
    }

    public static class FailedItem {
        public String path;
        public String reason;
    }

    public ScanResult scan(int scanMode) {
        String json = nativeScan(devicePath, scanMode);
        return parse(json, ScanResult.class);
    }

    public ScanResult scanAndRecover(int scanMode, String outputDir) {
        String json = nativeScanAndRecover(devicePath, scanMode, outputDir);
        return parse(json, ScanResult.class);
    }

    public RecoverResult recover(String[] filePaths, String outputDir, boolean preserveStructure) {
        String json = nativeRecover(devicePath, filePaths, outputDir, preserveStructure);
        return parse(json, RecoverResult.class);
    }

    public DeleteResult permanentDelete(String filePath, String method) {
        String json = nativeDelete(devicePath, filePath, method);
        return parse(json, DeleteResult.class);
    }

    public CompletableFuture<ScanResult> scanAsync(int scanMode) {
        return CompletableFuture.supplyAsync(() -> scan(scanMode));
    }

    public CompletableFuture<RecoverResult> recoverAsync(String[] filePaths, String outputDir, boolean preserveStructure) {
        return CompletableFuture.supplyAsync(() -> recover(filePaths, outputDir, preserveStructure));
    }

    public CompletableFuture<DeleteResult> deleteAsync(String filePath, String method) {
        return CompletableFuture.supplyAsync(() -> permanentDelete(filePath, method));
    }

    private native String nativeScan(String devicePath, int scanMode);
    private native String nativeScanAndRecover(String devicePath, int scanMode, String outputDir);
    private native String nativeRecover(String devicePath, String[] filePaths, String outputDir, boolean preserveStructure);
    private native String nativeDelete(String devicePath, String filePath, String method);

    private static <T> T parse(String json, Class<T> clazz) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RecoveryException("Parse JSON failed: " + json, e);
        }
    }

    public static class RecoveryException extends RuntimeException {
        public RecoveryException(String message, Throwable cause) { super(message, cause); }
    }
}
