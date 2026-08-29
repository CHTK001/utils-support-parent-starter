package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.WalSegmentInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * WAL 存储系统接口，亿级数据分片架构。
 *
 * <p>每个引擎实现此接口，通过 {@code ShardedIndex} 管理多分片 B+Tree 索引，
 * 通过 {@code WalLog} 负责 WAL 段文件的追加写和回放。</p>
 *
 * <h3>亿级数据存储模型</h3>
 * <pre>
 * baseDir/
 *   _wal/               ← WAL 段文件（SegmentWalLog）
 *     ns-000001.wal
 *     ns-000002.wal
 *   _index/             ← B+Tree 索引快照（快速冷启动）
 *     index.bin
 *   _meta/              ← 元数据
 *     schema.json
 * </pre>
 *
 * <h3>路由规则</h3>
 * <pre>
 * hash(key) % shardCount → shard_N
 * shard_N 的 B+Tree 指向 _wal/ns-XXXXX.wal 内的 EntryLoc
 * </pre>
 *
 * @param <K> 索引键类型（String）
 * @author CH
 * @since 4.0.0.42
 */
public interface WalStoreSystem<K extends Comparable<K>> extends AutoCloseable {

    /** 存储类型标识 */
    String type();

    /** 基目录 */
    Path baseDir();

    /** 分片数量 */
    int shardCount();

    // ==================== 写入 ====================

    /**
     * 追加一条记录。
     *
     * @param key     索引键
     * @param payload 业务 payload
     * @return 分配的 LSN
     */
    long append(K key, byte[] payload) throws java.io.IOException;

    /**
     * 批量追加（线程安全，内部攒批）。
     */
    default void appendBatch(List<WalAppendItem<K>> items) throws java.io.IOException {
        for (WalAppendItem<K> item : items) {
            append(item.key(), item.payload());
        }
    }

    /** 追加条目 */
    record WalAppendItem<K>(K key, byte[] payload) {}

    // ==================== 点查 ====================

    /**
     * 按 key 精确查找，O(log N)。
     *
     * @param key 索引键
     * @return payload（不存在返回 empty）
     */
    Optional<byte[]> get(K key) throws java.io.IOException;

    /**
     * 判断 key 是否存在。
     */
    boolean contains(K key) throws java.io.IOException;

    // ==================== 范围查 ====================

    /**
     * 范围查询 [from, to)，按 key 升序。
     *
     * @param from 下界（含）
     * @param to   上界（不含）
     * @return 条目列表
     */
    List<java.util.Map.Entry<K, byte[]>> range(K from, K to) throws java.io.IOException;

    /**
     * 带分页的范围查询。
     */
    List<java.util.Map.Entry<K, byte[]>> range(K from, K to, int offset, int limit)
            throws java.io.IOException;

    // ==================== 删除 ====================

    /**
     * 逻辑删除（追加 tombstone 记录）。
     *
     * @return 是否成功（key 存在返回 true）
     */
    boolean delete(K key) throws java.io.IOException;

    // ==================== 管理 ====================

    /**
     * 重建索引（从 WAL 回放，启动时调用）。
     */
    void rebuildIndex() throws java.io.IOException;

    /**
     * Compaction：合并分片，消除 tombstone 和过期记录。
     */
    void compact() throws java.io.IOException;

    /**
     * 当前总记录数（有效记录，不含 tombstone）。
     */
    int size();

    /**
     * 列出所有分片元信息。
     */
    List<WalSegmentInfo> listSegments() throws java.io.IOException;

    /**
     * 存储类型（kv/ts/vec/jdbc）。
     */
    StoreType storeType();

    enum StoreType { KV, TS, VEC, JDBC }

    @Override
    void close();
}
