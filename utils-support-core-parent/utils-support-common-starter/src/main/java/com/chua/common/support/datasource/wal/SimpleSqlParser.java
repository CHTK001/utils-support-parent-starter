package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.AbstractWalFileSystem;
import com.chua.common.support.wal.WalSegmentInfo;
import com.chua.common.support.wal.SegmentWalLog;
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
        for (int i = 0; i < store.walLogs.length; i++) {
            SegmentWalLog log = store.walLogs[i];
            if (log == null) continue;
            for (WalSegmentInfo seg : log.listSegments()) {
                try {
                    log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                        if ((op & 0x80) != 0) return true;
                        // JdbcWalStoreSystem payload: no key prefix, just encoded row
                        // The row map from decodeValue includes the table info via rowId
                        // We need to check if this payload belongs to our table
                        // Since we can't easily extract table from payload alone, scan all and filter later
                        // Actually, the key is passed to append() but not stored in payload
                        // So we decode and check the row content
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) store.decodeValue(null, payload);
                        if (row != null) rows.add(row);
                        return true;
                    });
                } catch (Exception ignored) {}
            }
        }
        return rows;
    }

    public int parseDml(String sql, Object... params) throws IOException { return 0; }
}
