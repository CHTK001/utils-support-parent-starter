package com.chua.shardingv5.support.conversion;

import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class CacheEntry {
    final List<String> tables;
    final long expireAt;
    CacheEntry(List<String> tables, long expireAt) {
        this.tables = tables; this.expireAt = expireAt;
    }
}