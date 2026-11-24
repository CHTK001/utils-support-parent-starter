package com.chua.common.support.task.backup;

/**
 * 数据库恢复事件监听器
 *
 * <p>通过 {@link DatabaseRestoreConfig#setListeners(java.util.List)} 挂载到本次恢复，
 * 在恢复开始、进度推进、成功、失败四个时点收到 {@link RestoreEvent} 通知，
 * 可用于进度条展示、日志审计或向 socket 广播恢复进度。
 *
 * <p>监听器抛出的任何异常都只记录告警，不会中断恢复流程。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   RestoreListener listener = new RestoreListener() {
 *       @Override
 *       public void onProgress(RestoreEvent event) {
 *           log.info("恢复中: {}/{} 脚本, 已执行 {} 条",
 *                   event.getFilesDone(), event.getFilesTotal(), event.getStatementsExecuted());
 *       }
 *   };
 * }</pre>
 *
 * @author CH
 * @since 2026/11/24
 */
public interface RestoreListener {

    /**
     * 恢复开始（脚本清单与校验和已就绪，尚未执行）。
     *
     * @param event 恢复事件
     */
    default void onStart(RestoreEvent event) {
    }

    /**
     * 恢复进度推进（每 {@code PROGRESS_INTERVAL} 条语句及每个脚本完成时触发）。
     *
     * @param event 恢复事件（含当前脚本与语句计数）
     */
    default void onProgress(RestoreEvent event) {
    }

    /**
     * 恢复全部成功。
     *
     * @param event 恢复事件（含恢复结果）
     */
    default void onSuccess(RestoreEvent event) {
    }

    /**
     * 恢复失败。
     *
     * @param event 恢复事件（含错误信息）
     */
    default void onFailure(RestoreEvent event) {
    }
}
