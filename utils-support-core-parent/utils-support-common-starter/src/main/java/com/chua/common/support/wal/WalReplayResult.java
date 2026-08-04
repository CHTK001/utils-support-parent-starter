package com.chua.common.support.wal;

import org.jspecify.annotations.NullUnmarked;

/**
 * WAL 回放结果。
 *
 * <p>回放完成后返回，包含 checkpoint 元信息与实际回放的记录列表，
 * 业务方拿到记录后可重复处理（例如校验、统计、迁移）。</p>
 *
 * @param checkpoint 回放起点 checkpoint
 * @param records    实际回放的记录列表（按 LSN 升序）
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public record WalReplayResult(
    CheckpointMeta checkpoint,
    java.util.List<WalRecord> records
) {

    /**
     * 实际回放的记录数量。
     *
     * @return 数量
     */
    public int size() {
        return records == null ? 0 : records.size();
    }

    /**
     * 第一条回放记录的 LSN（无记录返回 0）。
     *
     * @return LSN
     */
    public long firstLsn() {
        if (records == null || records.isEmpty()) {
            return 0L;
        }
        return records.get(0).lsn();
    }

    /**
     * 最后一条回放记录的 LSN（无记录返回 0）。
     *
     * @return LSN
     */
    public long lastLsn() {
        if (records == null || records.isEmpty()) {
            return 0L;
        }
        return records.get(records.size() - 1).lsn();
    }

    /**
     * 空结果。
     *
     * @return WalReplayResult 空实例
     */
    public static WalReplayResult empty(CheckpointMeta checkpoint) {
        return new WalReplayResult(checkpoint, java.util.Collections.emptyList());
    }
}
