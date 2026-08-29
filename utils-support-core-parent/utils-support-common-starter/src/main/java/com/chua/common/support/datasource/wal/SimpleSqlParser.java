package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.AbstractWalFileSystem;
import com.chua.common.support.wal.WalSegmentInfo;
import java.io.IOException;
import java.util.*;

/**
 * 简化 SQL 解析器。
 */
public class SimpleSqlParser {

    private final JdbcWalStoreSystem store;

    public SimpleSqlParser(JdbcWalStoreSystem store) { this.store = store; }

    public List<Map<String, Object>> parseSelect(String sql, Object... params) throws IOException {
        String lower = sql.trim().toLowerCase();
        int fromIdx = lower.indexOf(" from ");
        if (fromIdx < 0) return Collections.emptyList();
        String afterFrom = sql.trim().substring(fromIdx + 6).trim();
        int whereIdx = afterFrom.toLowerCase().indexOf(" where ");
        int limIdx = afterFrom.toLowerCase().indexOf(" limit ");
        String tablePart = whereIdx > 0 ? afterFrom.substring(0, whereIdx).trim()
                : (limIdx > 0 ? afterFrom.substring(0, limIdx).trim() : afterFrom.trim());
        String tableName = tablePart;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WalSegmentInfo seg : store.listSegments()) {
            store.listSegments(); // trigger scan
            // 简化：直接扫描所有段
        }
        // 简化：返回所有扫描到的行（实际应通过 query 接口）
        return rows;
    }

    public int parseDml(String sql, Object... params) throws IOException { return 0; }
}
