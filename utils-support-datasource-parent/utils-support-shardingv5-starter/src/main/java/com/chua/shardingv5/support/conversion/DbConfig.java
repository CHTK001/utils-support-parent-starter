package com.chua.shardingv5.support.conversion;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class DbConfig {
    final String prefix; final String shardingColumn;
    final int dbCount; final String algorithm;
    DbConfig(String p, String sc, int c, String a) {
        this.prefix = p; this.shardingColumn = sc; this.dbCount = c; this.algorithm = a;
    }
}