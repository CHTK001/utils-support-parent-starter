package com.chua.common.support.wal;


/**
 * WAL Checkpoint 元信息。
 *
 * <p>用于记录"截至哪个 LSN 已成功持久化"，启动时通过
 * {@link WalLog#loadCheckpoint()} 加载，避免重复应用已 checkpoint 的记录。</p>
 *
 * @param checkpointLsn      已 checkpoint 的最大 LSN（含），初始 0
 * @param checkpointSegmentNo 对应分片序号（简单 固定为 1）
 * @param checkpointOffset    分片内字节偏移（精确恢复点）
 * @param timestamp           checkpoint 时间戳（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record CheckpointMeta(
    long checkpointLsn,
    int checkpointSegmentNo,
    long checkpointOffset,
    long timestamp
) {

    /**
     * 空 checkpoint（初始状态）。
     *
     * @return CheckpointMeta 实例
     */
    public static CheckpointMeta empty() {
        return new CheckpointMeta(0L, 1, 0L, 0L);
    }

    /**
     * 是否为初始空状态。
     *
     * @return true=空
     */
    public boolean isEmpty() {
        return checkpointLsn == 0L;
    }
}
