package com.chua.shardingv5.support.conversion;

import java.util.List;
/**
 * 分片解析结果缓存条目（涉及表名与过期时间）。
 *
 * @author CH
 * @since 4.0.0.42
 */

public class CacheEntry {
    final List<String> tables; // tables
    final long expireAt; // expireat
    /**
     * 构造方法，创建 缓存条目 实例。
     * @param expireAt 方法入参 expireAt
     * @param tables 方法入参 tables
     * @return 返回值
    */
    CacheEntry(List<String> tables, long expireAt) {
        this.tables = tables;
        this.expireAt = expireAt;
    }
}