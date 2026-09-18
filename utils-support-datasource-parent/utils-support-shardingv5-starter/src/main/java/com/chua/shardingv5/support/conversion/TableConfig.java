package com.chua.shardingv5.support.conversion;
/**
* @author CH
* @since 4.0.0.42
 */

public class TableConfig {
    final String prefix; // 前缀
    final String shardingColumn;
    final int shardCount; // shard数量
    final String algorithm;
    /**
     * 构造方法，创建 表配置 实例。
     *
     * @param p 方法入参 p
     * @param sc 方法入参 sc
     * @param c 方法入参 c
     * @param a 方法入参 a
     */
    TableConfig(String p, String sc, int c, String a) {
        this.prefix = p;
        this.shardingColumn = sc;
        this.shardCount = c;
        this.algorithm = a;
    }
}