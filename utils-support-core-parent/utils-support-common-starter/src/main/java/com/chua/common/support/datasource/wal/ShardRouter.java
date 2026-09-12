package com.chua.common.support.datasource.wal;

/**
* 分片路由：基于 hashCode 取模将 key 均匀分配到 N 个分片。
*
* <p>亿级数据下，每个分片独立管理一个 B+Tree + 一个 WAL 段文件，
* 查询时先路由到对应分片再精确查找，避免全表扫描。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class ShardRouter {

    private final int shardCount;

    public ShardRouter(int shardCount) {
        if (shardCount < 1) throw new IllegalArgumentException("shardCount must be >= 1");
        this.shardCount = shardCount;
    }

    /**
    * 根据 key 计算所属分片序号（0 ~ shardCount-1）。
     */
    public int shardOf(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        // 避免负数
        return (hash & 0x7FFFFFFF) % shardCount;
    }

    /**
    * 返回分片数量。
     */
    public int shardCount() {
        return shardCount;
    }

    /**
    * 返回分片名称（用于文件命名）。
     */
    public String shardName(int idx) {
        return String.format("shard_%04d", idx);
    }
}
