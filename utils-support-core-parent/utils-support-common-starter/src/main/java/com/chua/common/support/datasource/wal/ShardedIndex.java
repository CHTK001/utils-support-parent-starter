package com.chua.common.support.datasource.wal;

import com.chua.common.support.tree.BPlusTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 多分片 B+Tree 索引管理器。
 *
 * <p>每个分片独立维护一棵 {@link BPlusTree}，key 为 String，
 * value 为 {@link EntryLoc}（WAL 分片文件路径 + 字节偏移 + 记录长度）。</p>
 *
 * <pre>
 * 写入: hash(key) % N → shard_N.put(key, EntryLoc)
 * 点查: hash(key) % N → shard_N.get(key) → EntryLoc → mmap精确读
 * 范围: 并行遍历所有 shard_N.range(from, to)，merge 排序
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShardedIndex {

    /**
     * 每棵 B+Tree 的阶数（越大节点容量越高，树高度越低）
     */
    private static final int TREE_ORDER = 200;

    private final int shardCount;
    /**
     * 各分片索引：shardIdx → B+Tree
     */
    private final BPlusTree<String, EntryLoc>[] shards;
    /**
     * 读写锁：写操作独占，读操作共享
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * EntryLoc：WAL 分片内的记录位置
     *
     * <p>B+Tree 的 value，本索引不存业务数据、只存「记录在哪」的定位三元组，
     * 由 {@code AbstractWalStoreSystem#rebuildShardIndex} 在重建索引时批量写入，
     * 读取时经 {@code AbstractWalStoreSystem#readFromSegment} 回读 payload。
     * 本记录不可变，定位信息一旦生成即不可改写。</p>
     *
     * @param segmentNo 分段编号，取自 {@code WalSegmentInfo.segmentNo()}；消费侧用它下标定位 {@code walLogs[]}，
     *                  取值须落在 {@code [0, 分片数)} 内，越界时 {@code readFromSegment} 直接返回 null
     * @param offset    记录定位偏移，无符号语义、非负。唯一构造点传入的是 {@code WalLog#replay} 回调给出的
     *                  <strong>LSN（单调递增的日志序号，并非字节位置）</strong>；而消费侧
     *                  {@code readFromSegment} 按字节位置使用它（{@code FileChannel.read(buf, offset)}）——
     *                  写入与读取口径不一致，此处如实记录现状，不做推断
     * @param length    记录负载长度，单位字节，取自 {@code payload.length}，即 value 字节数组的长度；非负
     */
    public record EntryLoc(int segmentNo, long offset, int length) {}

    /**
     * 构造方法，创建 Sharded索引 实例。
     *
     * @param shardCount 分片数量，不允许为 null
     */
    @SuppressWarnings("unchecked")
    public ShardedIndex(int shardCount) {
        this.shardCount = shardCount;
        this.shards = new BPlusTree[shardCount];
        for (int i = 0; i < shardCount; i++) {
            this.shards[i] = new BPlusTree<>(TREE_ORDER);
        }
    }

    // ==================== 点查 ====================

    /**
     * 按 key 精确查找，返回 EntryLoc。
     * @param key 键，不允许为 null
     * @return 可选结果，不存在时为 Optional.empty()
     */
    public Optional<EntryLoc> get(String key) {
        rwLock.readLock().lock();
        try {
            int idx = route(key);
            return shards[idx].get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 判断 key 是否存在。
     * @param key 键，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    public boolean contains(String key) {
        rwLock.readLock().lock();
        try {
            int idx = route(key);
            return shards[idx].containsKey(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ==================== 写入索引 ====================

    /**
     * 写入索引（点查时调用，update 场景覆盖旧值）。
     * @param key 键，不允许为 null
     * @param loc 方法入参 loc
     */
    public void put(String key, EntryLoc loc) {
        rwLock.writeLock().lock();
        try {
            int idx = route(key);
            shards[idx].put(key, loc);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 删除索引条目（tombstone 时调用）。
     * @param key 键，不允许为 null
     */
    public void remove(String key) {
        rwLock.writeLock().lock();
        try {
            int idx = route(key);
            shards[idx].remove(key);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ==================== 范围查 ====================

    /**
     * 范围查询 [from, to)，合并所有分片结果并按 key 排序。
     * @param from 来自，不允许为 null
     * @param to 转为，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    public List<Map.Entry<String, EntryLoc>> range(String from, String to) {
        rwLock.readLock().lock();
        try {
            List<Map.Entry<String, EntryLoc>> result = new ArrayList<>();
            for (BPlusTree<String, EntryLoc> tree : shards) {
                result.addAll(tree.range(from, to));
            }
            result.sort(Map.Entry.comparingByKey());
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 带分页的范围查询。
     * @param from 来自，不允许为 null
     * @param to 转为，不允许为 null
     * @param offset 偏移量，不允许为 null
     * @param limit 上限，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    public List<Map.Entry<String, EntryLoc>> range(String from, String to, int offset, int limit) {
        List<Map.Entry<String, EntryLoc>> all = range(from, to);
        int fromIdx = Math.min(offset, all.size());
        int toIdx = Math.min(offset + limit, all.size());
        return all.subList(fromIdx, toIdx);
    }

    // ==================== 批量重建 ====================

    /**
     * 批量写入索引（compaction 后重建时使用）。
     * @param entries 方法入参 entries
     */
    public void putAll(List<IndexEntry> entries) {
        rwLock.writeLock().lock();
        try {
            for (IndexEntry e : entries) {
                int idx = route(e.key());
                shards[idx].put(e.key(), e.loc());
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 清空所有分片。
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            for (BPlusTree<String, EntryLoc> tree : shards) {
                tree.clear();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ==================== 统计 ====================

    /**
     * 总索引条目数。
     * @return 结果数值
     */
    public int size() {
        rwLock.readLock().lock();
        try {
            int total = 0;
            for (BPlusTree<String, EntryLoc> tree : shards) {
                total += tree.size();
            }
            return total;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 分片数量。
     * @return 结果数值
     */
    public int shardCount() {
        return shardCount;
    }

    // ==================== 内部工具 ====================

    /**
     * route。
     *
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private int route(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return (hash & 0x7FFFFFFF) % shardCount;
    }

    /**
     * 索引条目（批量重建用）
     *
     * <p>供 {@link #putAll(List)} 一次性灌入的 key/loc 配对，
     * 典型来源是 compaction 之后重扫 WAL 得到的全量清单。
     * 逐条独立于 {@link #put(String, EntryLoc)} 之外的批量入口，两者最终都写入同一组 B+Tree 分片。</p>
     *
     * @param key 索引键，业务键的字符串形态（{@code K.toString()}）；分片路由取
     *            {@code (key.hashCode() & 0x7FFFFFFF) % shardCount}，不要求全局有序，但 range 查询会先归并再按 key 升序排序
     * @param loc 该键对应的 WAL 记录定位三元组；本索引覆盖写，重复键以最后一次写入的 loc 为准
     */
    public record IndexEntry(String key, EntryLoc loc) {}
}
