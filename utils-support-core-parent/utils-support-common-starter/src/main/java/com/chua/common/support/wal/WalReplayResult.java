package com.chua.common.support.wal;


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
public record WalReplayResult(
    CheckpointMeta checkpoint,
    java.util.List<WalRecord> records
) {

    /**
     * 规范构造器：记录列表做防御性拷贝，并对必填引用组件做空值校验。
     *
     * <p>value class 前置条件——集合组件必须深不可变，且表示对空值敌对。
     * 全部构造点（SimpleWalLog / SegmentWalLog / KafkaWalLog / ChronicleWalLog）
     * 传入的列表元素均非 null，因此可使用 {@code List.copyOf}。</p>
     *
     * @param checkpoint 回放起点 checkpoint
     * @param records    实际回放的记录列表（按 LSN 升序）
     */
    public WalReplayResult {
        checkpoint = java.util.Objects.requireNonNull(checkpoint, "checkpoint 不能为 null");
        records = java.util.List.copyOf(
                java.util.Objects.requireNonNull(records, "records 不能为 null"));
    }

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
        return records.getFirst().lsn();
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
     * @param checkpoint checkpoint
     */
    public static WalReplayResult empty(CheckpointMeta checkpoint) {
        return new WalReplayResult(checkpoint, java.util.Collections.emptyList());
    }
}
