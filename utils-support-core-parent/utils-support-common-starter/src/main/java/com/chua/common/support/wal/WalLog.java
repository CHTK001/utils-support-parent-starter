package com.chua.common.support.wal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * WAL 日志统一接口，支持追加、回放、链式事务、分片查找、checkpoint 管理。
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>{@link SimpleWalLog}：单文件实现，不分片</li>
 *   <li>{@link SegmentWalLog}：分片 + 滚动实现</li>
 * </ul>
 *
 * <h2>checkpoint 设计</h2>
 * <ul>
 *   <li>checkpoint 由 WAL 内部维护（{@link #loadCheckpoint()}）</li>
 *   <li>业务方持久化完成后调用 {@link #markCheckpoint(long)} 推进</li>
 *   <li>回放默认从 checkpoint 之后开始（{@link #replay(WalReplayHandler)}）</li>
 *   <li>需要从特定 LSN 开始可使用 {@link #replay(long, WalReplayHandler)}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface WalLog extends Closeable {

    /**
     * 追加一条记录。
     *
     * @param op      操作类型
     * @param payload 业务字节流
     * @return 写入分配的 LSN
     * @throws IOException IO 异常
     */
    long append(byte op, byte[] payload) throws IOException;

    /**
     * 同步刷盘（不写记录）。
     *
     * @throws IOException IO 异常
     */
    void sync() throws IOException;

    /**
      * 当前最大 LSN（最近一次 追加 分配的 LSN，初始 0）。
     *
     * @return 当前 LSN
     */
    long currentLsn();

    // ==================== Checkpoint ====================

    /**
     * 加载 checkpoint 元信息（启动时调用）。
     *
     * <p>从磁盘读取最近一次持久化的 checkpoint，包括 checkpointLsn、所在分片、偏移量等。</p>
     *
     * @return checkpoint 元信息（无则返回 {@link CheckpointMeta#empty()}）
     * @throws IOException IO 异常
     */
    CheckpointMeta loadCheckpoint() throws IOException;

    /**
     * 推进 checkpoint 到指定 LSN（业务方持久化完成后调用）。
     *
     * <p>将 checkpointLsn 写入 {@code checkpoint.meta} 文件，下次 replay 从 checkpointLsn + 1 开始。</p>
     *
     * @param lsn 已成功持久化的最大 LSN（含）
     * @throws IOException IO 异常
     */
    void markCheckpoint(long lsn) throws IOException;

    /**
     * 重置 checkpoint 到 0（不删除 WAL 文件，仅清空 checkpoint 标记）。
     *
     * <p>下次 replay 会从第一条记录开始重新应用。</p>
     *
     * @throws IOException IO 异常
     */
    void resetCheckpoint() throws IOException;

    /**
     * 强制设置 checkpoint 到指定 LSN（跳过中间未持久化记录，谨慎使用）。
     *
     * @param lsn 目标 LSN
     * @throws IOException IO 异常
     */
    void forceCheckpoint(long lsn) throws IOException;

    // ==================== Replay ====================

    /**
     * 从当前 checkpoint 之后开始回放（推荐用法）。
     *
     * <p>内部流程：</p>
     * <ol>
     *   <li>读取 checkpoint 元信息</li>
     *   <li>从 (checkpointLsn + 1) 开始回放到末尾</li>
     *   <li>handler 返回 false 即中止</li>
     *   <li>回放成功后自动 {@link #markCheckpoint(long)} 推进 checkpoint</li>
     * </ol>
     *
     * @param handler 回放处理器
     * @return 回放结果（含 checkpoint 与回放的记录列表）
     * @throws IOException IO 异常
     */
    WalReplayResult replay(WalReplayHandler handler) throws IOException;

    /**
     * 从指定 LSN 开始回放到末尾。
     *
     * @param fromLsn 起始 LSN（含）
     * @param handler 回放处理器
     * @return 回放结果
     * @throws IOException IO 异常
     */
    WalReplayResult replay(long fromLsn, WalReplayHandler handler) throws IOException;

    /**
      * 范围回放 [从lsn, 转为lsn)。
     *
     * <p>支持重复处理部分数据：业务方拿到 records 列表后可再次遍历。</p>
     *
     * @param fromLsn 起始 LSN（含）
     * @param toLsn   结束 LSN（不含）
     * @param handler 回放处理器（可返回 false 中止）
     * @return 回放结果
     * @throws IOException IO 异常
     */
    WalReplayResult replay(long fromLsn, long toLsn, WalReplayHandler handler) throws IOException;

    // ==================== Chain ====================

    /**
     * 链式事务：回调中追加多条记录，回调返回后统一刷盘。
     *
     * @param handler 链式处理器
     * @return 实际写入的最后一条 LSN
     * @throws IOException IO 异常
     */
    long appendChain(WalChainHandler handler) throws IOException;

    // ==================== Query ====================

    /**
     * 按 LSN 快速定位记录（仅 SEGMENT 实现真正分片定位）。
     *
     * @param lsn 目标 LSN
     * @return 记录（找不到返回 {@link Optional#empty()}）
     * @throws IOException IO 异常
     */
    Optional<WalRecord> findByLsn(long lsn) throws IOException;

    /**
     * 删除已 checkpoint 的旧分片，保留最近 N 个。
     *
     * @param keepSegments 保留分片数
     * @return 实际删除的分片数
     * @throws IOException IO 异常
     */
    int purgeCheckpointed(int keepSegments) throws IOException;

    /**
     * 当前活跃分片信息。
     *
     * @return 活跃分片信息
     */
    WalSegmentInfo currentSegment();

    /**
     * 列出所有分片。
     *
     * @return 分片列表（按 segmentno 升序）
     */
    List<WalSegmentInfo> listSegments();

    /**
     * 关闭并释放资源（不删除文件）。
     *
     * @throws IOException IO 异常
     */
    @Override
    void close() throws IOException;
}
