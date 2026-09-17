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

    /** 每棵 B+Tree 的阶数（越大节点容量越高，树高度越低） */
    private static final int TREE_ORDER = 200;

    private final int shardCount;
    /** 各分片索引：shardIdx → B+Tree */
    private final BPlusTree<String, EntryLoc>[] shards;
    /** 读写锁：写操作独占，读操作共享 */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** EntryLoc：WAL 分片内的记录位置 */
    public record EntryLoc(int segmentNo, long offset, int length) {}

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
    */
    public int shardCount() {
        return shardCount;
    }

    // ==================== 内部工具 ====================

    private int route(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return (hash & 0x7FFFFFFF) % shardCount;
    }

    /** 索引条目（批量重建用） */
    public record IndexEntry(String key, EntryLoc loc) {}
}
