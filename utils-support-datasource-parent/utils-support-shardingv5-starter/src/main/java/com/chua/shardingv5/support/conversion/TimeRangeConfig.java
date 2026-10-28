package com.chua.shardingv5.support.conversion;

import java.util.List;
import lombok.ToString;
/**
 * 时间范围分片配置（分片列与起止时间）。
 *
 * @author CH
 * @since 4.0.0.42
 */

@ToString
public class TimeRangeConfig {
    final String shardingColumn; // 分库分表column
    final String start;
    final String end;
    final List<String> realTables; // realtables
    /**
     * 构造方法，创建 时间Range配置 实例。
     *
     * @param sc 方法入参 sc
     * @param s 方法入参 s
     * @param e 方法入参 e
     * @param t 方法入参 t
     */
    TimeRangeConfig(String sc, String s, String e, List<String> t) {
        this.shardingColumn = sc;
        this.start = s;
        this.end = e;
        this.realTables = t;
    }
}