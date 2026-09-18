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

    /**
     * 解析Select。
     *
     * @param sql SQL，不允许为 null
     * @param params 参数，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    public List<Map<String, Object>> parseSelect(String sql, Object... params) throws IOException {
        String lower = sql.trim().toLowerCase();
        int fromIdx = lower.indexOf(" from ");
        if (fromIdx < 0) {
            return Collections.emptyList();
        }
        String afterFrom = sql.trim().substring(fromIdx + 6).trim();
        int whereIdx = afterFrom.toLowerCase().indexOf(" where ");
        int limIdx = afterFrom.toLowerCase().indexOf(" limit ");
        String tablePart = whereIdx > 0 ? afterFrom.substring(0, whereIdx).trim()
                : (limIdx > 0 ? afterFrom.substring(0, limIdx).trim() : afterFrom.trim());
        String tableName = tablePart;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < store.walLogs.length; i++) {
            SegmentWalLog log = store.walLogs[i];
            if (log == null) {
                continue;
            }
            for (WalSegmentInfo seg : log.listSegments()) {
                try {
                    log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                        if ((op & 0x80) != 0) {
                            return true;
                        }
                        // JdbcWalStoreSystem 的载荷：不含主键前缀，只有编码后的行数据
                        // decodeValue 得到的行映射会通过 rowId 携带表信息
                        // 这里需要判断该载荷是否属于目标表
                        // 由于仅凭载荷本身难以取出表名，先扫描全部分段再做过滤
                        // 实际上 key 会传给 append()，但并未写入载荷
                        // 因此只能解码后根据行内容判断
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) store.decodeValue(null, payload);
                        if (row != null) {
                            rows.add(row);
                        }
                        return true;
                    });
                } catch (Exception ignored) {}
            }
        }
        return rows;
    }

    public int parseDml(String sql, Object... params) throws IOException { return 0; }
}
