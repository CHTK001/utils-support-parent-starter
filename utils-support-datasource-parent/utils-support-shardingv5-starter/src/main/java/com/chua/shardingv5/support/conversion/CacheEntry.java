package com.chua.shardingv5.support.conversion;

import java.util.List;
/**
* @author CH
* @since 4.0.0.42
 */

public class CacheEntry {
    final List<String> tables; // tables
    final long expireAt; // expireat
    /**
     * 构造方法，创建 缓存条目 实例。
     *
     * @param tables 方法入参 tables
     * @param expireAt 方法入参 expireAt
     */
    CacheEntry(List<String> tables, long expireAt) {
        this.tables = tables;
        this.expireAt = expireAt;
    }
}