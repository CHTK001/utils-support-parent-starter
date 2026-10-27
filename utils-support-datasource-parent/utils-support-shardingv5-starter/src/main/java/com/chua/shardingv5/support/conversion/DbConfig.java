package com.chua.shardingv5.support.conversion;
/**
 * 分库配置（表前缀、分片列、库数量与分片算法）。
 *
 * @author CH
 * @since 4.0.0.42
 */

public class DbConfig {
    final String prefix; // 前缀
    final String shardingColumn;
    final int dbCount; // db数量
    final String algorithm;
    /**
     * 构造方法，创建 Db配置 实例。
     *
     * @param p 方法入参 p
     * @param sc 方法入参 sc
     * @param c 方法入参 c
     * @param a 方法入参 a
     */
    DbConfig(String p, String sc, int c, String a) {
        this.prefix = p;
        this.shardingColumn = sc;
        this.dbCount = c;
        this.algorithm = a;
    }
}