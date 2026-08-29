package com.chua.common.support.wal;

/**
 * WAL 文件系统扩展接口，在 {@link com.chua.common.support.file.FileSystem} 基础上
 * 增加 WAL 专用能力：按 LSN 追加写、按偏移精确读、分片管理、compaction。
 *
 * <p>通过 {@code @Spi} 注册为不同数据格式的 WAL 后端（kv/ts/vec/jdbc）。
 * 格式差异仅体现在 payload 的序列化方式上，底层文件布局完全统一。</p>
 *
 * <h3>统一 WAL 文件格式</h3>
 * <pre>
 * 文件头（16B）: magic(4B)="WAL1" | version(4B) | flags(4B) | crc32(4B)
 * Entry（变长）: crc32(4B) | lsn(8B) | op(1B) | len(4B) | payload(lenB)
 *
 * op 取值（业务自定义）：
 *   0x01 = KV 写入
 *   0x02 = TS 写入
 *   0x03 = VEC 写入
 *   0x04 = JDBC 写入
 *   0x80 = tombstone（删除标记）
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface WalFileSystem extends com.chua.common.support.file.FileSystem {

    /** 魔数字节 */
    byte[] MAGIC = new byte[]{'W', 'A', 'L', '1'};
    /** 当前版本 */
    int VERSION = 1;
    /** 文件头固定大小（magic+version+flags+crc = 16B）*/
    int HEADER_SIZE = 16;

    // ==================== WAL 核心操作 ====================

    /**
     * 追加一条 WAL 记录。
     *
     * @param op      操作类型（0x01~0x04 或 0x80 tombstone）
     * @param payload 业务字节流
     * @return 分配的 LSN（单调递增，从 1 开始）
     */
    long append(byte op, byte[] payload) throws java.io.IOException;

    /**
     * 批量追加（原子提交，内部攒批后一次性 fsync）。
     *
     * @param entries 条目列表，每项为 (op, payload)
     * @return 最后一条的 LSN
     */
    default long appendBatch(java.util.List<WalBatchEntry> entries) throws java.io.IOException {
        if (entries == null || entries.isEmpty()) return 0L;
        long lastLsn = 0;
        for (WalBatchEntry e : entries) {
            lastLsn = append(e.op(), e.payload());
        }
        return lastLsn;
    }

    /**
     * 按 LSN 精确读取单条记录。
     *
     * @param lsn 目标 LSN
     * @return 记录（不存在返回 empty）
     */
    java.util.Optional<WalRecord> readByLsn(long lsn) throws java.io.IOException;

    /**
     * 按文件内偏移读取（用于 B+Tree 索引跳转）。
     *
     * @param segmentNo 分片序号
     * @param offset    文件内字节偏移
     * @param length    读取字节数
     * @return 字节数组
     */
    byte[] readAt(int segmentNo, long offset, int length) throws java.io.IOException;

    /**
     * 同步刷盘。
     */
    void sync() throws java.io.IOException;

    /**
     * 获取当前最大 LSN。
     */
    long currentLsn();

    // ==================== 分片管理 ====================

    /**
     * 列出所有分片元信息。
     */
    java.util.List<WalSegmentInfo> listSegments() throws java.io.IOException;

    /**
     * 获取当前活跃分片。
     */
    WalSegmentInfo currentSegment();

    /**
     * Compaction：合并旧分片，消除 tombstone 和过期记录，重建索引。
     *
     * <p>实现类应在内部完成：扫描所有分片 → 过滤有效记录 → 写入新分片 → 原子替换旧分片。</p>
     */
    void compact() throws java.io.IOException;

    // ==================== Checkpoint ====================

    /**
     * 加载 checkpoint 元信息。
     */
    CheckpointMeta loadCheckpoint() throws java.io.IOException;

    /**
     * 推进 checkpoint。
     */
    void markCheckpoint(long lsn) throws java.io.IOException;

    // ==================== 生命周期 ====================

    /**
     * 关闭 WAL 文件系统，释放资源（不删除文件）。
     */
    @Override
    void close();

    /**
     * 批量条目（用于 appendBatch）。
     */
    record WalBatchEntry(byte op, byte[] payload) {
        public static WalBatchEntry of(byte op, byte[] payload) {
            return new WalBatchEntry(op, payload == null ? new byte[0] : payload.clone());
        }
    }
}
