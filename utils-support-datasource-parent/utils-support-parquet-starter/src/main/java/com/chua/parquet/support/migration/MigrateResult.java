package com.chua.parquet.support.migration;

/**
 * 数据迁移结果：包含成功标记、迁移方向、读写行数、批次与耗时。
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class MigrateResult {

    private final boolean success;
    private final String migratorName;
    private final String sourceTable;
    private final String targetTable;
    private final long totalRead;
    private final long totalWritten;
    private final int batchCount;
    private final long elapsedMillis;
    private final String message;

    private MigrateResult(boolean success, String migratorName, String sourceTable, String targetTable,
                          long totalRead, long totalWritten, int batchCount, long elapsedMillis, String message) {
        this.success = success;
        this.migratorName = migratorName;
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.totalRead = totalRead;
        this.totalWritten = totalWritten;
        this.batchCount = batchCount;
        this.elapsedMillis = elapsedMillis;
        this.message = message;
    }

    /**
     * 构建成功结果。
     *
     * @param migratorName  实际执行的插件名
     * @param request       迁移请求
     * @param totalRead     读取总行数
     * @param totalWritten  写入总行数
     * @param batchCount    批次数
     * @param elapsedMillis 耗时（毫秒）
     * @return 成功结果
     */
    public static MigrateResult success(String migratorName, MigrateRequest request,
                                        long totalRead, long totalWritten, int batchCount, long elapsedMillis) {
        return new MigrateResult(true, migratorName, request.getSourceTable(), request.getTargetTable(),
                totalRead, totalWritten, batchCount, elapsedMillis, "OK");
    }

    /**
     * 构建失败结果。
     *
     * @param migratorName  尝试执行的插件名，可能为 {@code null}（未找到插件时）
     * @param request       迁移请求，可能为 {@code null}
     * @param message       失败原因
     * @return 失败结果
     */
    public static MigrateResult failure(String migratorName, MigrateRequest request, String message) {
        return new MigrateResult(false, migratorName,
                request == null ? null : request.getSourceTable(),
                request == null ? null : request.getTargetTable(),
                0L, 0L, 0, 0L, message);
    }

    /**
     * 是否迁移成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 实际执行的插件名。
     *
     * @return 插件名，未找到插件时为 {@code null}
     */
    public String getMigratorName() {
        return migratorName;
    }

    /**
     * 源表名。
     *
     * @return 源表名
     */
    public String getSourceTable() {
        return sourceTable;
    }

    /**
     * 目标表名。
     *
     * @return 目标表名
     */
    public String getTargetTable() {
        return targetTable;
    }

    /**
     * 读取总行数。
     *
     * @return 读取总行数
     */
    public long getTotalRead() {
        return totalRead;
    }

    /**
     * 写入总行数。
     *
     * @return 写入总行数
     */
    public long getTotalWritten() {
        return totalWritten;
    }

    /**
     * 批次数。
     *
     * @return 批次数
     */
    public int getBatchCount() {
        return batchCount;
    }

    /**
     * 耗时（毫秒）。
     *
     * @return 耗时毫秒数
     */
    public long getElapsedMillis() {
        return elapsedMillis;
    }

    /**
     * 结果说明：成功时为 {@code OK}，失败时为失败原因（含回滚情况）。
     *
     * @return 结果说明
     */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "MigrateResult{success=" + success
                + ", migrator=" + migratorName
                + ", source=" + sourceTable
                + ", target=" + targetTable
                + ", totalRead=" + totalRead
                + ", totalWritten=" + totalWritten
                + ", batchCount=" + batchCount
                + ", elapsedMillis=" + elapsedMillis
                + ", message=" + message + '}';
    }
}
