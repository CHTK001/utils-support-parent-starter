package com.chua.datasource.support.middleware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读查询结果。
 *
 * <p>列用「并集推导」而不是首行文档的键：中间件的文档结构常常不齐，
 * 只取首行会让后面文档的字段直接消失。实现方把遇到过的键按首次出现顺序汇总即可。</p>
 *
 * <p>行数口径：实现方按宿主传入的 {@code limit} 兜底；命中默认上限时
 * 应置 {@link #truncated()} 为 {@code true}，并把截断前的真实行数写进
 * {@link #totalRows()}，前端据此提示用户收窄条件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareQueryResult {

    /** 列名，按首次出现顺序 */
    private final List<String> columns;

    /** 行数据，每行的键应与 {@link #columns()} 对应（允许缺列） */
    private final List<Map<String, Object>> rows;

    /** 是否被截断 */
    private final boolean truncated;

    /** 截断前的真实行数，未截断时等于行数 */
    private final long totalRows;

    /** 执行耗时（毫秒） */
    private final long executionTimeMs;

    /** 失败原因，成功时为空串 */
    private final String message;

    /**
     * 全字段构造。
     *
     * @param columns         列名
     * @param rows            行数据
     * @param truncated       是否被截断
     * @param totalRows       截断前的真实行数
     * @param executionTimeMs 执行耗时（毫秒）
     * @param message         失败原因
     */
    public MiddlewareQueryResult(List<String> columns, List<Map<String, Object>> rows,
                                 boolean truncated, long totalRows,
                                 long executionTimeMs, String message) {
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        this.truncated = truncated;
        this.totalRows = totalRows;
        this.executionTimeMs = executionTimeMs;
        this.message = message;
    }

    /**
     * 空结果。
     *
     * @return 无列无行的结果对象
     */
    public static MiddlewareQueryResult empty() {
        return new MiddlewareQueryResult(List.of(), List.of(), false, 0L, 0L, "");
    }

    /**
     * 失败结果。
     *
     * @param message 失败原因
     * @return 结果对象
     */
    public static MiddlewareQueryResult fail(String message) {
        return new MiddlewareQueryResult(List.of(), List.of(), false, 0L, 0L, message);
    }

    /**
     * 便捷构造：分列与行组装一个成功结果。
     *
     * @param columns         列名
     * @param rows            行数据
     * @param truncated       是否被截断
     * @param totalRows       截断前的真实行数
     * @param executionTimeMs 执行耗时（毫秒）
     * @return 结果对象
     */
    public static MiddlewareQueryResult of(List<String> columns, List<Map<String, Object>> rows,
                                           boolean truncated, long totalRows, long executionTimeMs) {
        return new MiddlewareQueryResult(columns, rows, truncated, totalRows, executionTimeMs, "");
    }

    /**
     * 单行单列结果（计数场景）。
     *
     * @param column 列名
     * @param value  值
     * @return 结果对象
     */
    public static MiddlewareQueryResult single(String column, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, value);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return new MiddlewareQueryResult(List.of(column), rows, false, 1L, 0L, "");
    }

    /**
     * @return 列名列表
     */
    public List<String> columns() {
        return columns;
    }

    /**
     * @return 行数据
     */
    public List<Map<String, Object>> rows() {
        return rows;
    }

    /**
     * @return 行数
     */
    public int rowCount() {
        return rows.size();
    }

    /**
     * @return 是否被截断
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * @return 截断前的真实行数
     */
    public long totalRows() {
        return totalRows;
    }

    /**
     * @return 执行耗时（毫秒）
     */
    public long executionTimeMs() {
        return executionTimeMs;
    }

    /**
     * @return 失败原因，成功时为空串
     */
    public String message() {
        return message == null ? "" : message;
    }

    @Override
    public String toString() {
        return "MiddlewareQueryResult{rowCount=" + rowCount() + ", truncated=" + truncated
                + ", totalRows=" + totalRows + ", ms=" + executionTimeMs + ", message=" + message() + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #columns()}。
     *
     * @return columns
     */
    public java.util.List<String> getColumns() {
        return columns();
    }

    /**
     * Jackson getter，对应 {@link #rows()}。
     *
     * @return rows
     */
    public java.util.List<java.util.Map<String, Object>> getRows() {
        return rows();
    }

    /**
     * Jackson getter，对应 {@link #rowCount()}。
     *
     * @return rowCount
     */
    public int getRowCount() {
        return rowCount();
    }

    /**
     * Jackson getter，对应 {@link #truncated()}。
     *
     * @return truncated
     */
    public boolean getTruncated() {
        return truncated();
    }

    /**
     * Jackson getter，对应 {@link #totalRows()}。
     *
     * @return totalRows
     */
    public long getTotalRows() {
        return totalRows();
    }

    /**
     * Jackson getter，对应 {@link #executionTimeMs()}。
     *
     * @return executionTimeMs
     */
    public long getExecutionTimeMs() {
        return executionTimeMs();
    }

    /**
     * Jackson getter，对应 {@link #message()}。
     *
     * @return message
     */
    public String getMessage() {
        return message();
    }
}
