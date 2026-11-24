package com.chua.common.support.task.backup;

/**
 * 数据库备份历史记录
 *
 * <p>一次数据库备份（成功或失败）落库到 {@link BackupHistoryStore} 的最小审计单元。
 * 时间字段使用 ISO-8601 字符串（与 {@link BackupManifest} 同口径），
 * 避免引入 Java 时间类型的序列化依赖；路径存绝对路径字符串。
 *
 * @author CH
 * @since 2026/11/24
 */
public record BackupHistoryRecord(
        String id,
        String dbName,
        boolean success,
        String backupPath,
        String manifestPath,
        int fileCount,
        long totalSizeBytes,
        long durationMillis,
        String startTime,
        String endTime,
        String errorMessage) {

    /**
     * 紧凑构造器：空白字符串归一为 null，保证历史查询与序列化语义一致。
     */
    public BackupHistoryRecord {
        id = blankToNull(id);
        dbName = blankToNull(dbName);
        backupPath = blankToNull(backupPath);
        manifestPath = blankToNull(manifestPath);
        errorMessage = blankToNull(errorMessage);
    }

    /**
     * 空白字符串转 null。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
