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

    /** Device路径 */
    private final String devicePath;

    /**
     * 创建 DataRecovery 实例
     * @param devicePath devicePath
     */
    private DataRecovery(String devicePath) {
        this.devicePath = devicePath;
    }

    /** Of */
    public static DataRecovery of(String devicePath) {
        return new DataRecovery(normalizeDevicePath(devicePath));
    }

    /** NormalizeDevicePath */
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

    /** Callback */
    public DataRecovery callback(RecoveryCallback callback) {
        this.callback = callback;
        return this;
    }

    /** Callback */
    private RecoveryCallback callback;

    public static interface RecoveryCallback {
        void onProgress(String stage, int percent);
        void onFileFound(FileEntry entry);
        void onRecovered(String path, long bytes);
        void onError(String error);
    }

    public static class FileEntry {
        /** 名称 */
        public String name;
        /** 路径 */
        public String path;
        /** 尺寸bytes */
        public long sizeBytes;
        /** Modified时间戳 */
        public long modifiedTimestamp;
        /** 删除标记时间戳 */
        public long deletedTimestamp;
        /** Recovery分数 */
        public int recoveryScore;
        /** Carved签名 */
        public String carvedSignature;
    }

    public static class ScanResult {
        /** Success */
        public boolean success;
        /** Filesscanned */
        public int filesScanned;
        /** Filesfound */
        public int filesFound;
        /** 消息 */
        public String message;
        /** Entries */
        public FileEntry[] entries;
    }

    public static class DeleteResult {
        /** Success */
        public boolean success;
        /** Bytesoverwritten */
        public long bytesOverwritten;
        /** Passescompleted */
        public int passesCompleted;
        /** 消息 */
        public String message;
    }

    public static class RecoverResult {
        /** Success数量 */
        public int successCount;
        /** Failed数量 */
        public int failedCount;
        /** Failed列表 */
        public FailedItem[] failedList;
        /** 总数byteswritten */
        public long totalBytesWritten;
    }

    public static class FailedItem {
        /** 路径 */
        public String path;
        /** Reason */
        public String reason;
    }

    /** 扫描 */
    public ScanResult scan(int scanMode) {
        String json = nativeScan(devicePath, scanMode);
        return parse(json, ScanResult.class);
    }

    /** 扫描AndRecover */
    public ScanResult scanAndRecover(int scanMode, String outputDir) {
        String json = nativeScanAndRecover(devicePath, scanMode, outputDir);
        return parse(json, ScanResult.class);
    }

    /** Recover */
    public RecoverResult recover(String[] filePaths, String outputDir, boolean preserveStructure) {
        String json = nativeRecover(devicePath, filePaths, outputDir, preserveStructure);
        return parse(json, RecoverResult.class);
    }

    /** Permanent删除 */
    public DeleteResult permanentDelete(String filePath, String method) {
        String json = nativeDelete(devicePath, filePath, method);
        return parse(json, DeleteResult.class);
    }

    /** 扫描Async */
    public CompletableFuture<ScanResult> scanAsync(int scanMode) {
        return CompletableFuture.supplyAsync(() -> scan(scanMode));
    }

    /** RecoverAsync */
    public CompletableFuture<RecoverResult> recoverAsync(String[] filePaths, String outputDir, boolean preserveStructure) {
        return CompletableFuture.supplyAsync(() -> recover(filePaths, outputDir, preserveStructure));
    }

    /** 删除Async */
    public CompletableFuture<DeleteResult> deleteAsync(String filePath, String method) {
        return CompletableFuture.supplyAsync(() -> permanentDelete(filePath, method));
    }

    /** Native扫描 */
    private native String nativeScan(String devicePath, int scanMode);
    /** Native扫描AndRecover */
    private native String nativeScanAndRecover(String devicePath, int scanMode, String outputDir);
    /** NativeRecover */
    private native String nativeRecover(String devicePath, String[] filePaths, String outputDir, boolean preserveStructure);
    /** Native删除 */
    private native String nativeDelete(String devicePath, String filePath, String method);

    /** 解析 */
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
        /**
         * 创建 RecoveryException 实例
         * @param message message
         * @param Throwable Throwable
         */
        public RecoveryException(String message, Throwable cause) { super(message, cause); }
    }
}
