package com.chua.common.support.task.backup;

/**
 * 数据库备份事件监听器
 *
 * <p>通过 {@link DatabaseBackupConfig#setListeners(java.util.List)} 挂载到本次备份，
 * 在备份开始、单表导出完成、成功、失败四个时点收到 {@link BackupEvent} 通知。
 *
 * <p>监听器抛出的任何异常都只记录告警，不会中断备份流程。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   BackupListener listener = new BackupListener() {
 *       @Override
 *       public void onSuccess(BackupEvent event) {
 *           log.info("备份完成: {} 张表, {} 行", event.getFinishedTables(), event.getTotalRows());
 *       }
 *   };
 * }</pre>
 *
 * @author CH
 * @since 2026/11/24
 */
public interface BackupListener {

    /**
     * 备份开始（表清单已确定，尚未导出）。
     *
     * @param event 备份事件
     */
    default void onStart(BackupEvent event) {
    }

    /**
     * 单张表导出完成。
     *
     * @param event 备份事件（含当前表名与行数）
     */
    default void onTableDone(BackupEvent event) {
    }

    /**
     * 备份全部成功。
     *
     * @param event 备份事件（含备份结果）
     */
    default void onSuccess(BackupEvent event) {
    }

    /**
     * 备份失败。
     *
     * @param event 备份事件（含错误信息）
     */
    default void onFailure(BackupEvent event) {
    }
}
