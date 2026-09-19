package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.AbstractWalFileSystem;
import com.chua.common.support.wal.WalSegmentInfo;
import com.chua.common.support.wal.SegmentWalLog;
import com.chua.common.support.wal.WalException;
import java.io.IOException;
import java.util.*;

/**
 * 简化 SQL 解析器。
 *
 * <p>只支持 {@code SELECT ... FROM 表} 的全分段扫描与 {@code INSERT INTO 表 (列) VALUES (值)} 写入。
 * 回放或解码任一条记录失败都会中止整次操作，不把残缺结果当作成功返回。</p>
 */
public class SimpleSqlParser {

    /**
     * 参数占位符
     */
    private static final char PLACEHOLDER = '?';

    private final JdbcWalStoreSystem store;

    /**
     * 构造方法，创建 SimpleSqlParser 实例。
     *
     * @param store 存储系统，不允许为 null
     */
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
                replaySegment(log, seg, tableName, rows);
            }
        }
        return rows;
    }

    /**
     * 回放单个分段并收集其中的行数据。
     *
     * <p>分段读取异常与单条记录解码失败均包装为 {@link WalException} 抛出，
     * 避免把部分回放伪装成完整查询结果。</p>
     *
     * @param log WAL 分段日志，不允许为 null
     * @param seg 分段元信息，不允许为 null
     * @param tableName 表名称，不允许为 null
     * @param rows 收集结果，不允许为 null
     * @throws IOException 当分段回放失败时
     */
    private void replaySegment(SegmentWalLog log, WalSegmentInfo seg, String tableName,
                               List<Map<String, Object>> rows) throws IOException {
        try {
            log.replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if ((op & AbstractWalFileSystem.OP_TOMBSTONE) != 0) {
                    return true;
                }
                rows.add(decodeRow(payload, tableName, seg, lsn));
                return true;
            });
        } catch (IOException | RuntimeException e) {
            throw new WalException("WAL 分段回放失败: 表=" + tableName + ", 分段=" + seg.path()
                    + ", lsn区间=[" + seg.firstLsn() + "," + seg.lastLsn() + "]", e);
        }
    }

    /**
     * 解码单条记录为行数据。
     *
     * @param payload 记录载荷
     * @param tableName 表名称，不允许为 null
     * @param seg 分段元信息，不允许为 null
     * @param lsn 记录的 LSN
     * @return 行数据，不会为 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeRow(byte[] payload, String tableName, WalSegmentInfo seg, long lsn) {
        Map<String, Object> row = (Map<String, Object>) store.decodeValue(null, payload);
        if (row == null || row.isEmpty()) {
            throw new WalException("WAL 记录无法解码为行数据: 表=" + tableName + ", 分段=" + seg.path()
                    + ", lsn=" + lsn);
        }
        return row;
    }

    /**
     * 解析 DML 语句并执行。
     *
     * <p>仅支持 {@code INSERT INTO 表 (列, ...) VALUES (值, ...)}，支持 {@code ?} 占位符绑定，
     * 多值组按行逐条写入。该存储只在记录中持久化列数据、未持久化主键与二级索引，
     * 无法按 WHERE 条件定位既有记录，因此 UPDATE/DELETE 显式抛出异常而不是返回 0。</p>
     *
     * @param sql SQL，不允许为 null
     * @param params 参数，不允许为 null
     * @return 实际写入的行数
     * @throws IOException 当执行过程不满足前置条件时
     */
    public int parseDml(String sql, Object... params) throws IOException {
        String statement = sql == null ? "" : sql.trim();
        String lower = statement.toLowerCase();
        if (lower.startsWith("insert into ")) {
            return executeInsert(statement, params);
        }
        throw new UnsupportedOperationException("WAL 行式存储未持久化主键与二级索引，无法按 WHERE 条件定位记录，"
                + "因此只支持 INSERT INTO 表 (列) VALUES (值)，不支持 "
                + (lower.isEmpty() ? "空语句" : lower.split("\\s+")[0].toUpperCase(Locale.ROOT) + " 语句")
                + "；按键删除与范围查询请使用 KvWalStoreSystem，按时间范围查询请使用 TsWalStoreSystem");
    }

    /**
     * 执行 INSERT 写入。
     *
     * @param statement 完整 SQL 语句，不允许为 null
     * @param params 占位符绑定参数
     * @return 实际写入的行数
     * @throws IOException 当执行过程不满足前置条件时
     */
    private int executeInsert(String statement, Object... params) throws IOException {
        String tablePart = statement.substring("insert into ".length()).trim();
        int parenStart = tablePart.indexOf('(');
        if (parenStart < 0) {
            throw new WalException("INSERT 语句缺少列清单: " + statement);
        }
        int parenEnd = matchingParen(tablePart, parenStart, statement);
        String table = tablePart.substring(0, parenStart).trim();
        List<String> columns = splitFlat(tablePart.substring(parenStart + 1, parenEnd), statement);
        String afterColumns = tablePart.substring(parenEnd + 1).trim();
        int valuesIdx = afterColumns.toLowerCase().indexOf("values");
        if (valuesIdx < 0) {
            throw new WalException("INSERT 语句缺少 VALUES 子句: " + statement);
        }
        String tuples = afterColumns.substring(valuesIdx + "values".length()).trim();
        int inserted = 0;
        int paramCursor = 0;
        for (String tuple : splitTuples(tuples, statement)) {
            List<String> literals = splitFlat(tuple, statement);
            if (literals.size() != columns.size()) {
                throw new WalException("INSERT 列数与值数不一致: 列=" + columns.size() + ", 值=" + literals.size()
                        + ", 语句=" + statement);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < literals.size(); i++) {
                String literal = literals.get(i).trim();
                if (PLACEHOLDER == literal.charAt(0) && literal.length() == 1) {
                    if (params == null || paramCursor >= params.length) {
                        throw new WalException("INSERT 占位符缺少绑定参数: 第" + (paramCursor + 1) + "个, 语句=" + statement);
                    }
                    row.put(columns.get(i), params[paramCursor++]);
                }
                else {
                    row.put(columns.get(i), parseLiteral(literal, statement));
                }
            }
            store.insert(table, row);
            inserted++;
        }
        if (inserted == 0) {
            throw new WalException("INSERT 语句未包含任何值组: " + statement);
        }
        return inserted;
    }

    /**
     * 查找与起始括号配对的右括号位置。
     *
     * @param text 待扫描文本，不允许为 null
     * @param start 左括号下标
     * @param statement 完整语句，用于异常信息
     * @return 右括号下标
     */
    private static int matchingParen(String text, int start, String statement) {
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ('\'' == c) {
                quote = !quote;
            }
            else if (!quote && '(' == c) {
                depth++;
            }
            else if (!quote && ')' == c) {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        throw new WalException("INSERT 语句括号不匹配: " + statement);
    }

    /**
     * 拆分出每个值组括号内的内容。
     *
     * @param text VALUES 之后的文本，不允许为 null
     * @param statement 完整语句，用于异常信息
     * @return 每个值组的内部文本
     */
    private static List<String> splitTuples(String text, String statement) {
        List<String> tuples = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        int begin = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ('\'' == c) {
                quote = !quote;
            }
            if (quote) {
                continue;
            }
            if ('(' == c) {
                if (depth++ == 0) {
                    begin = i + 1;
                }
            }
            else if (')' == c && --depth == 0) {
                tuples.add(text.substring(begin, i));
            }
        }
        if (depth != 0 || tuples.isEmpty()) {
            throw new WalException("INSERT 值组格式错误: " + statement);
        }
        return tuples;
    }

    /**
     * 按顶层逗号拆分文本。
     *
     * @param text 待拆分文本，不允许为 null
     * @param statement 完整语句，用于异常信息
     * @return 拆分结果
     */
    private static List<String> splitFlat(String text, String statement) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        int begin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ('\'' == c) {
                quote = !quote;
            }
            else if (!quote && ('(' == c || ')' == c)) {
                throw new WalException("INSERT 列或值中不允许出现嵌套括号: " + statement);
            }
            else if (!quote && ',' == c) {
                parts.add(text.substring(begin, i));
                begin = i + 1;
            }
        }
        parts.add(text.substring(begin));
        List<String> result = new ArrayList<>(parts.size());
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new WalException("INSERT 语句存在空列名或空值: " + statement);
            }
            result.add(trimmed);
        }
        return result;
    }

    /**
     * 解析字面量。
     *
     * @param literal 字面量文本，不允许为 null
     * @param statement 完整语句，用于异常信息
     * @return 列值
     */
    private static Object parseLiteral(String literal, String statement) {
        if (literal.length() >= 2 && literal.charAt(0) == '\'' && literal.charAt(literal.length() - 1) == '\'') {
            return literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        if ("null".equalsIgnoreCase(literal)) {
            return null;
        }
        try {
            return literal.indexOf('.') >= 0 ? Double.parseDouble(literal) : Long.valueOf(literal);
        } catch (NumberFormatException e) {
            throw new WalException("INSERT 值无法解析为数字或字符串: " + literal + ", 语句=" + statement, e);
        }
    }
}
