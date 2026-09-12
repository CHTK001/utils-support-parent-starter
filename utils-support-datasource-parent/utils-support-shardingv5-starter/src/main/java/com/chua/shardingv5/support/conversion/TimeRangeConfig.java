package com.chua.shardingv5.support.conversion;

import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class TimeRangeConfig {
    final String shardingColumn; final String start; final String end; // 分库分表column
    final List<String> realTables; // realtables
    TimeRangeConfig(String sc, String s, String e, List<String> t) {
        this.shardingColumn = sc; this.start = s; this.end = e; this.realTables = t;
    }
}