package com.chua.shardingv5.support.conversion;

import java.util.List;
/**
 * @author CH
 */

public class TimeRangeConfig {
    final String shardingColumn; final String start; final String end;
    final List<String> realTables;
    TimeRangeConfig(String sc, String s, String e, List<String> t) {
        this.shardingColumn = sc; this.start = s; this.end = e; this.realTables = t;
    }
}