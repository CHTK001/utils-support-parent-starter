package com.chua.mysql.support.slowquery;

import com.chua.datasource.support.slowquery.SlowQueryInfo;
import com.chua.datasource.support.slowquery.SlowQueryManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MySQL 慢查询分析管理器 SPI 实现。
 *
 * <p>两个数据来源，按优先级回退：</p>
 *
 * <ol>
 *   <li>{@code performance_schema.events_statements_summary_by_digest}（MySQL 5.7+/8 默认开启）：
 *       按语句摘要聚合，有执行次数 / 平均 / 最大耗时，是最完整的一档；</li>
 *   <li>{@code mysql.slow_log}：慢日志写入表时才有的逐条记录（{@code log_output} 含 {@code TABLE}），
 *       没有摘要字段，实现把语句压成单行当作 digest。</li>
 * </ol>
 *
 * <p>performance_schema 的 timer 列单位是<b>皮秒</b>，所以对外换算成毫秒要除以 1e9。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public class MysqlSlowQueryManager implements SlowQueryManager, DataSourceAware {

    /**
     * 默认返回条数。
     */
    private static final int DEFAULT_LIMIT = 100;

    /**
     * 条数上限，防止一次拉爆前端。
     */
    private static final int MAX_LIMIT = 500;

    /**
     * 皮秒 → 毫秒。
     *
     * <p>{@code performance_schema} 的 {@code *_TIMER_WAIT} 列单位是<b>皮秒</b>：
     * 1 秒 = 1e12 皮秒、1 毫秒 = 1e9 皮秒。写成 1e6（微秒/毫秒）会让结果整体放大 1000 倍
     * —— 曾把 410ms 的语句显示成 410923ms（约 7 分钟），这里必须用 1e9。</p>
     */
    private static final double PICOS_PER_MS = 1_000_000_000D;

    /**
     * 摘要文本最大长度（前端「语句摘要」列）。
     */
    private static final int DIGEST_MAX = 200;

    /**
     * 系统库不参与排名，否则全是 performance_schema 自己的语句。
     */
    private static final String SYSTEM_SCHEMAS = "'performance_schema','information_schema','mysql','sys'";

    /**
     * 数据来源。
     */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code mysql}。
     *
     * @return "mysql"
     */
    @Override
    public String type() {
        return "mysql";
    }

    /**
     * 设置 JDBC 数据源。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 按平均耗时倒序列出慢查询摘要。
     *
     * @param minAvgMs 平均耗时下限（毫秒）
     * @param limit    最多返回条数
     * @return 慢查询摘要列表
     */
    @Override
    public List<SlowQueryInfo> listSlowQueries(double minAvgMs, int limit) {
        int max = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        double floor = Math.max(minAvgMs, 0D);
        try (Connection c = dataSource.getConnection()) {
            try {
                return fromPerformanceSchema(c, floor, max);
            } catch (SQLException e) {
                // performance_schema 被关闭 / 当前账号无 SELECT 权限 → 退到慢日志表
                return fromSlowLog(c, floor, max);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 MySQL 慢查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 {@code performance_schema} 摘要表取数。
     *
     * @param c     连接
     * @param floor 平均耗时下限（毫秒）
     * @param max   条数上限
     * @return 摘要列表
     * @throws SQLException 视图不存在 / 无权限
     */
    private List<SlowQueryInfo> fromPerformanceSchema(Connection c, double floor, int max) throws SQLException {
        String sql = "SELECT SCHEMA_NAME, DIGEST, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT,"
                + " AVG_TIMER_WAIT, MAX_TIMER_WAIT, SUM_ROWS_EXAMINED, SUM_ROWS_SENT,"
                + " FIRST_SEEN, LAST_SEEN"
                + " FROM performance_schema.events_statements_summary_by_digest"
                + " WHERE DIGEST IS NOT NULL AND DIGEST_TEXT IS NOT NULL"
                + " AND SCHEMA_NAME IS NOT NULL AND SCHEMA_NAME NOT IN (" + SYSTEM_SCHEMAS + ")"
                + " AND AVG_TIMER_WAIT >= ?"
                + " ORDER BY AVG_TIMER_WAIT DESC LIMIT ?";
        List<SlowQueryInfo> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, floor * PICOS_PER_MS);
            ps.setInt(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new SlowQueryInfo(
                            rs.getString("DIGEST"),
                            clip(rs.getString("DIGEST_TEXT")),
                            rs.getString("SCHEMA_NAME"),
                            rs.getLong("COUNT_STAR"),
                            rs.getDouble("SUM_TIMER_WAIT") / PICOS_PER_MS,
                            rs.getDouble("AVG_TIMER_WAIT") / PICOS_PER_MS,
                            rs.getDouble("MAX_TIMER_WAIT") / PICOS_PER_MS,
                            rs.getLong("SUM_ROWS_EXAMINED"),
                            rs.getLong("SUM_ROWS_SENT"),
                            stamp(rs.getTimestamp("FIRST_SEEN")),
                            stamp(rs.getTimestamp("LAST_SEEN"))));
                }
            }
        }
        return list;
    }

    /**
     * 从 {@code mysql.slow_log} 取数（没有摘要，逐条记录压成一行）。
     *
     * @param c     连接
     * @param floor 平均耗时下限（毫秒）
     * @param max   条数上限
     * @return 慢查询列表
     * @throws SQLException 表不存在 / 无权限
     */
    private List<SlowQueryInfo> fromSlowLog(Connection c, double floor, int max) throws SQLException {
        String sql = "SELECT start_time, query_time, rows_sent, rows_examined, db, sql_text"
                + " FROM mysql.slow_log ORDER BY start_time DESC LIMIT ?";
        List<SlowQueryInfo> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double ms = timeToMs(rs.getString("query_time"));
                    if (ms < floor) {
                        continue;
                    }
                    String text = clip(rs.getString("sql_text"));
                    list.add(new SlowQueryInfo(
                            text,
                            text,
                            rs.getString("db"),
                            1L,
                            ms,
                            ms,
                            ms,
                            rs.getLong("rows_examined"),
                            rs.getLong("rows_sent"),
                            stamp(rs.getTimestamp("start_time")),
                            stamp(rs.getTimestamp("start_time"))));
                }
            }
        }
        return list;
    }

    /**
     * 把 MySQL {@code TIME} 字面量（{@code HH:mm:ss.SSSSSS}）换算成毫秒。
     *
     * @param value 原始值
     * @return 毫秒；解析失败返回 0
     */
    private static double timeToMs(String value) {
        if (value == null || value.isEmpty()) {
            return 0D;
        }
        try {
            String[] parts = value.split(":");
            if (parts.length != 3) {
                return 0D;
            }
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return ((hours * 60 + minutes) * 60 + seconds) * 1000D;
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    /**
     * 时间戳格式化为 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param timestamp 时间戳
     * @return 字符串；入参为 null 时返回 null
     */
    private static String stamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Date date = new Date(timestamp.getTime());
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    /**
     * 压空白并截断（摘要列只做展示，不做完整 SQL 备份）。
     *
     * @param text 原始文本
     * @return 单行摘要
     */
    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > DIGEST_MAX ? flat.substring(0, DIGEST_MAX) + "…" : flat;
    }
}
