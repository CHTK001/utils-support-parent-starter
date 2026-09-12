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
      * 创建 数据recovery 实例
     * @param devicePath device路径
     */
    private DataRecovery(String devicePath) {
        this.devicePath = devicePath;
    }

    /**
     * 的
     *
     * @param devicePath device路径
     * @return 的的结果
     */
    public static DataRecovery of(String devicePath) {
        return new DataRecovery(normalizeDevicePath(devicePath));
    }

    /**
     * normalizedevice路径
     *
     * @param path 路径
     * @return normalizedevice路径的结果
     */
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

    /**
     * Callback
     *
     * @param callback callback
     * @return callback的结果
     */
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
        /** 成功 */
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
        /** 成功 */
        public boolean success;
        /** Bytesoverwritten */
        public long bytesOverwritten;
        /** Passescompleted */
        public int passesCompleted;
        /** 消息 */
        public String message;
    }

    public static class RecoverResult {
        /** 成功数量 */
        public int successCount;
        /** 失败数量 */
        public int failedCount;
        /** 失败列表 */
        public FailedItem[] failedList;
        /** 总数byteswritten */
        public long totalBytesWritten;
    }

    public static class FailedItem {
        /** 路径 */
        public String path;
        /** ReasonMLML */
        public String reason;
    }

    /**
     * 扫描
     *
     * @param scanMode 扫描mode
     * @return 扫描的结果
     */
    public ScanResult scan(int scanMode) {
        String json = nativeScan(devicePath, scanMode);
        return parse(json, ScanResult.class);
    }

    /**
     * 扫描和recover
     *
     * @param scanMode 扫描mode
     * @param outputDir 输出dir
     * @return 扫描和recover的结果
     */
    public ScanResult scanAndRecover(int scanMode, String outputDir) {
        String json = nativeScanAndRecover(devicePath, scanMode, outputDir);
        return parse(json, ScanResult.class);
    }

    /**
     * Recover
     *
     * @param filePaths 文件路径
     * @param outputDir 输出dir
     * @param preserveStructure preserve结构
     * @return recover的结果
     */
    public RecoverResult recover(String[] filePaths, String outputDir, boolean preserveStructure) {
        String json = nativeRecover(devicePath, filePaths, outputDir, preserveStructure);
        return parse(json, RecoverResult.class);
    }

    /**
     * Permanent删除
     *
     * @param filePath 文件路径
     * @param method 方法
     * @return permanent删除的结果
     */
    public DeleteResult permanentDelete(String filePath, String method) {
        String json = nativeDelete(devicePath, filePath, method);
        return parse(json, DeleteResult.class);
    }

    /**
     * 扫描异步
     *
     * @param scanMode 扫描mode
     * @return 扫描异步的结果
     */
    public CompletableFuture<ScanResult> scanAsync(int scanMode) {
        return CompletableFuture.supplyAsync(() -> scan(scanMode));
    }

    /**
     * recover异步
     *
     * @param filePaths 文件路径
     * @param outputDir 输出dir
     * @param preserveStructure preserve结构
     * @return recover异步的结果
     */
    public CompletableFuture<RecoverResult> recoverAsync(String[] filePaths, String outputDir, boolean preserveStructure) {
        return CompletableFuture.supplyAsync(() -> recover(filePaths, outputDir, preserveStructure));
    }

    /**
     * 删除异步
     *
     * @param filePath 文件路径
     * @param method 方法
     * @return 删除异步的结果
     */
    public CompletableFuture<DeleteResult> deleteAsync(String filePath, String method) {
        return CompletableFuture.supplyAsync(() -> permanentDelete(filePath, method));
    }

    /**
     * Native扫描
     *
     * @param devicePath device路径
     * @param scanMode 扫描mode
     * @return NAT扫描的结果
     */
    private native String nativeScan(String devicePath, int scanMode);
    /**
     * NAT扫描和recover
     *
     * @param devicePath device路径
     * @param scanMode 扫描mode
     * @param outputDir 输出dir
     * @return NAT扫描和recover的结果
     */
    private native String nativeScanAndRecover(String devicePath, int scanMode, String outputDir);
    /**
      * natrecover
     *
     * @param devicePath device路径
     * @param filePaths 文件路径
     * @param outputDir 输出dir
     * @param preserveStructure preserve结构
     * @return NATrecover的结果
     */
    private native String nativeRecover(String devicePath, String[] filePaths, String outputDir, boolean preserveStructure);
    /**
     * Native删除
     *
     * @param devicePath device路径
     * @param filePath 文件路径
     * @param method 方法
     * @return NAT删除的结果
     */
    private native String nativeDelete(String devicePath, String filePath, String method);

    /**
     * 解析
     *
     * @param json json
     * @param clazz clazz
     * @return 解析的结果
     * @author CH
     * @since 4.0.0
     */
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
          * 创建 recovery异常 实例
         * @param message 消息
         * @param cause Throwable
         * @param cause cause
         */
        public RecoveryException(String message, Throwable cause) { super(message, cause); }
    }
}
