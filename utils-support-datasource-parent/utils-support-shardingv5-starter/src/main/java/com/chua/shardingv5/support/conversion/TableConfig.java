package com.chua.shardingv5.support.conversion;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class TableConfig {
    final String prefix; final String shardingColumn;
    final int shardCount; final String algorithm;
    TableConfig(String p, String sc, int c, String a) {
        this.prefix = p; this.shardingColumn = sc; this.shardCount = c; this.algorithm = a;
    }
}