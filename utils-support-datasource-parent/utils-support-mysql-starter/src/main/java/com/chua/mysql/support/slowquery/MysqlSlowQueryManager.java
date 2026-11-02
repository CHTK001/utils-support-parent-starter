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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MySQL 慢查询分析管理器 SPI 实现。
 *
 * <p>三个数据来源，按优先级回退：</p>
 *
 * <ol>
 *   <li>{@code performance_schema.events_statements_history_long}（MySQL 5.7+/8 默认开启）：
 *       逐条慢事件，自带客户端主机（{@code PROCESSLIST_HOST}），能精确回答「哪台客户端导致的」；
 *       代价是环形缓冲只保留最近一段时间的记录（默认约一万条，量满后旧的被覆盖），
 *       且只对已经<b>完成</b>的语句计时，看不到正在执行的。</li>
 *   <li>{@code mysql.slow_log}：慢日志写入表时才有的逐条记录（{@code log_output} 含 {@code TABLE}），
 *       通过 {@code user_host}（形如 {@code user[host]}）解析出客户端主机；
 *       没有摘要字段，实现把语句压成单行当作 digest。</li>
 *   <li>{@code performance_schema.events_statements_summary_by_digest}：按语句摘要聚合，
 *       有执行次数 / 平均 / 最大耗时，但没有主机维度（{@code host} 为 null），只作兜底。</li>
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
     * 按耗时倒序列出慢查询。
     *
     * <p>优先级：逐条事件（含客户端主机）→ 慢日志表（解析 {@code user_host}）→
     * 摘要聚合（无主机）。只有「上一来源不可用（表不存在 / 无权限）」才降级，
     * 查询成功但结果为空不算降级 —— 空就是真的没有慢语句。</p>
     *
     * @param minAvgMs 耗时下限（毫秒）
     * @param limit    最多返回条数
     * @return 慢查询列表
     */
    @Override
    public List<SlowQueryInfo> listSlowQueries(double minAvgMs, int limit) {
        int max = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        double floor = Math.max(minAvgMs, 0D);
        try (Connection c = dataSource.getConnection()) {
            try {
                return fromHistoryLong(c, floor, max);
            } catch (SQLException e) {
                // performance_schema 被关闭 / 当前账号无 SELECT 权限 → 退到慢日志表
                try {
                    return fromSlowLog(c, floor, max);
                } catch (SQLException ex) {
                    // 慢日志表也未开 → 退到摘要聚合（拿不到主机，host 为 null）
                    return fromDigest(c, floor, max);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 MySQL 慢查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 {@code performance_schema.events_statements_history_long} 取逐条慢事件。
     *
     * <p>与摘要表不同，这里是「一次执行 = 一行」，没有 {@code COUNT_STAR}：
     * {@code calls} 固定为 1，总时长 = 平均 = 最大。环形缓冲不记录墙上时间，
     * 因此 {@code firstSeen}/{@code lastSeen} 给 null，由前端显示为「—」。</p>
     *
     * <p><b>版本差异</b>：8.0 及以前该表自带 {@code SCHEMA_NAME} 与
     * {@code PROCESSLIST_HOST} 列；8.4 起移除 {@code SCHEMA_NAME}（改
     * {@code CURRENT_SCHEMA}）并去掉 {@code PROCESSLIST_HOST}，客户端主机只能通过
     * {@code THREAD_ID} 关联 {@code performance_schema.threads} 取。这里先探测
     * 列名再拼 SQL，两代都能跑。</p>
     *
     * <p><b>消费者依赖</b>：该表由 {@code setup_consumers} 的
     * {@code events_statements_history_long} 消费者驱动，未开启时表恒为空。
     * 空结果会被误读成「没有慢语句」，所以关闭时直接抛异常走降级链
     * （慢日志 → 摘要聚合），由摘要口径兜底。</p>
     *
     * @param c     连接
     * @param floor 耗时下限（毫秒）
     * @param max   条数上限
     * @return 逐条慢事件列表
     * @throws SQLException 视图不存在 / 无权限 / 消费者未开启
     */
    private List<SlowQueryInfo> fromHistoryLong(Connection c, double floor, int max) throws SQLException {
        requireHistoryLongConsumer(c);
        String schemaCol = historyLongSchemaColumn(c);
        if (schemaCol == null) {
            throw new SQLException("performance_schema.events_statements_history_long 不可查（表不存在或无权限）");
        }
        String sql = "SELECT h.DIGEST, h.DIGEST_TEXT, h." + schemaCol + " AS SCHEMA_NAME,"
                + " t.PROCESSLIST_HOST, h.TIMER_WAIT, h.ROWS_EXAMINED, h.ROWS_SENT"
                + " FROM performance_schema.events_statements_history_long h"
                + " LEFT JOIN performance_schema.threads t ON h.THREAD_ID = t.THREAD_ID"
                + " WHERE h.EVENT_NAME LIKE 'statement/%'"
                + " AND h.DIGEST IS NOT NULL AND h.DIGEST_TEXT IS NOT NULL"
                + " AND h." + schemaCol + " IS NOT NULL AND h." + schemaCol + " NOT IN (" + SYSTEM_SCHEMAS + ")"
                + " AND h.TIMER_WAIT IS NOT NULL"
                + " AND h.TIMER_WAIT >= ?"
                + " ORDER BY h.TIMER_WAIT DESC LIMIT ?";
        List<SlowQueryInfo> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, floor * PICOS_PER_MS);
            ps.setInt(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double ms = rs.getDouble("TIMER_WAIT") / PICOS_PER_MS;
                    list.add(new SlowQueryInfo(
                            rs.getString("DIGEST"),
                            clip(rs.getString("DIGEST_TEXT")),
                            rs.getString("SCHEMA_NAME"),
                            1L,
                            ms,
                            ms,
                            ms,
                            rs.getLong("ROWS_EXAMINED"),
                            rs.getLong("ROWS_SENT"),
                            null,
                            null,
                            cleanHost(rs.getString("PROCESSLIST_HOST"))));
                }
            }
        }
        return list;
    }

    /**
     * 探测 {@code events_statements_history_long} 的 schema 列名。
     *
     * <p>8.0 及以前叫 {@code SCHEMA_NAME}，8.4 起改叫 {@code CURRENT_SCHEMA}。
     * 都探测到优先 {@code SCHEMA_NAME}（老版本语义，8.4 不会同时有）；探测不到
     * （表不存在 / 无权限）返回 null。</p>
     *
     * @param c 连接
     * @return 列名；不可用时为 null
     */
    private static String historyLongSchemaColumn(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COLUMN_NAME"
                     + " FROM information_schema.columns"
                     + " WHERE TABLE_SCHEMA = 'performance_schema'"
                     + " AND TABLE_NAME = 'events_statements_history_long'"
                     + " AND COLUMN_NAME IN ('SCHEMA_NAME', 'CURRENT_SCHEMA')")) {
            String currentSchema = null;
            while (rs.next()) {
                String col = rs.getString(1);
                if ("SCHEMA_NAME".equals(col)) {
                    return col;
                }
                if ("CURRENT_SCHEMA".equals(col)) {
                    currentSchema = col;
                }
            }
            return currentSchema;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 校验逐条事件消费者已开启；关闭时抛异常触发降级。
     *
     * <p>{@code events_statements_history_long} 由消费者 {@code setup_consumers}
     * 驱动，未开启时表恒为空。空结果会被前端误读成「没有慢语句」，所以
     * 这里在关闭时主动抛错，让调用方降级到慢日志 / 摘要聚合。</p>
     *
     * @param c 连接
     * @throws SQLException 消费者关闭或状态不可查
     */
    private static void requireHistoryLongConsumer(Connection c) throws SQLException {
        String enabled = null;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ENABLED"
                     + " FROM performance_schema.setup_consumers"
                     + " WHERE NAME = 'events_statements_history_long'")) {
            if (rs.next()) {
                enabled = rs.getString(1);
            }
        } catch (SQLException e) {
            // 消费者表都查不了，逐条事件表大概率也不可查 → 降级
            throw e;
        }
        if (enabled == null || !"YES".equalsIgnoreCase(enabled)) {
            throw new SQLException("performance_schema 消费者 events_statements_history_long 未开启，无法提供逐条事件");
        }
    }

    /**
     * 从 {@code mysql.slow_log} 取数（没有摘要，逐条记录压成一行）。
     *
     * <p>客户端主机来自 {@code user_host} 列，形如 {@code user[host]}，
     * 用 {@code hostFromUserHost} 解析出方括号里的主机。</p>
     *
     * @param c     连接
     * @param floor 耗时下限（毫秒）
     * @param max   条数上限
     * @return 慢查询列表
     * @throws SQLException 表不存在 / 无权限
     */
    private List<SlowQueryInfo> fromSlowLog(Connection c, double floor, int max) throws SQLException {
        String sql = "SELECT start_time, user_host, query_time, rows_sent, rows_examined, db, sql_text"
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
                            stamp(rs.getTimestamp("start_time")),
                            hostFromUserHost(rs.getString("user_host"))));
                }
            }
        }
        return list;
    }

    /**
     * 从 {@code performance_schema}` 摘要表取数（兜底，拿不到客户端主机）。</p>
     *
     * @param c     连接
     * @param floor 平均耗时下限（毫秒）
     * @param max   条数上限
     * @return 摘要列表
     * @throws SQLException 视图不存在 / 无权限
     */
    private List<SlowQueryInfo> fromDigest(Connection c, double floor, int max) throws SQLException {
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
                            stamp(rs.getTimestamp("LAST_SEEN")),
                            null));
                }
            }
        }
        return list;
    }

    /**
     * 从 {@code mysql.slow_log.user_host} 解析客户端主机。
     *
     * <p>{@code user_host} 形如 {@code user[user] @ host [ip]}，可能有两对括号：
     * 前面的 {@code [user]} 是账号名，最后的 {@code [ip]} 才是客户端地址。
     * 所以取<b>最后一对</b>方括号的内容，而不是第一对。</p>
     *
     * @param userHost 原始值
     * @return 方括号里的客户端地址；无方括号时取整串；为空返回 null
     */
    private static String hostFromUserHost(String userHost) {
        if (userHost == null || userHost.isEmpty()) {
            return null;
        }
        int start = userHost.lastIndexOf('[');
        int end = userHost.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleanHost(userHost.substring(start + 1, end));
        }
        return cleanHost(userHost);
    }

    /**
     * 去空白；空串归一为 null。
     *
     * @param host 原始主机
     * @return 清理后的主机；空为 null
     */
    private static String cleanHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim();
        return value.isEmpty() ? null : value;
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
        DateTimeFormatter sdf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date.toInstant().atZone(ZoneId.systemDefault()));
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