package com.chua.runtime.apm.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 隔离加载器内的 SQLite 会话（存储实现的 JDBC 执行体）。
 *
 * <p><b>为什么需要单独的类</b>：agent 把自身 jar 追加到 bootstrap 搜索路径后，bootstrap
 * 解析不到 {@code java.sql}。此时只有<b>由隔离加载器自身定义</b>的类才能完整使用 JDBC；
 * 由 bootstrap 定义的类（{@code ApmStorageClassLoader}）一碰 {@code java.sql}
 * 的解析就抛 {@code NoClassDefFoundError}。</p>
 *
 * <p>因此本类承担所有 JDBC 动作，连接在本类内部创建并复用，对外只暴露
 * {@code String}/{@code int}/{@code long}/{@code boolean} 与字符串数组。</p>
 *
 * <p><b>必须遵守的写法约束（已实测）</b>：</p>
 * <ul>
 *   <li>只用 {@link Statement} 执行 SQL，<b>禁止</b> {@code PreparedStatement}：
 *       后者在 sqlite 实现里走 {@code SafeStmtPtr.safeRun*}（{@code invokedynamic}），
 *       bootstrap 已追加时必抛 {@code NoClassDefFoundError: java/sql/Statement}。</li>
 *   <li><b>禁止</b> {@code try-with-resources}：会合成对 {@code javax.sql.CachedRowSet}
 *       等类型的引用，触发链接失败；改用 {@code finally} + {@code close}。</li>
 *   <li><b>禁止</b>任何 {@code javax.sql} 类型。</li>
 *   <li>本类抛出/捕获的受检异常统一是 {@link SQLException}。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqliteSession {

    /**
     * 官方驱动类名（只依赖 {@code java.sql}，可安全用于隔离加载器）。
     */
    private static final String DRIVER_CLASS = "org.sqlite.JDBC";

    /**
     * 连接。
     */
    private Connection connection;

    /**
     * 构造器。
     */
    public SqliteSession() {
    }

    /**
     * 打开连接。
     *
     * <p>降级策略：驱动实例化失败时抛出原始的 {@link Throwable}（而非包装成
     * {@link SQLException}），避免在异常构造路径上再解析 {@code java.sql.SQLException}。</p>
     *
     * @param url 连接地址
     * @return 是否成功
     * @throws Exception 驱动不可用或建连失败
     */
    public boolean open(String url) throws Exception {
        close();
        ClassLoader loader = SqliteSession.class.getClassLoader();
        Class<?> type = Class.forName(DRIVER_CLASS, true, loader);
        Object raw = type.getDeclaredConstructor().newInstance();
        if (!(raw instanceof Driver)) {
            throw new IllegalStateException("SQLite 驱动未实现 java.sql.Driver: " + DRIVER_CLASS);
        }
        this.connection = ((Driver) raw).connect(url, new Properties());
        if (this.connection != null) {
            tune();
        }
        return this.connection != null;
    }

    /**
     * 调整连接参数以适配「高频小批量写入」。
     *
     * <p>默认 {@code journal_mode=delete} + {@code synchronous=FULL} 会让每次提交都
     * fsync，单条插入约 21ms（实测）。改为 WAL + NORMAL 后，同一批次的多次提交
     * 合并为一次刷盘，批量写入吞吐提升一个数量级；WAL 在进程崩溃时仍保证已提交数据
     * 不丢失（只可能在断电时丢失最近若干毫秒）。</p>
     *
     * <p>失败不阻塞：某些文件系统（网络盘）不支持 WAL，此时保留默认模式即可。</p>
     */
    private void tune() {
        try {
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=NORMAL");
            execute("PRAGMA busy_timeout=5000");
        } catch (Throwable e) {
            // 参数调整失败不影响可用性
        }
    }

    /**
     * 强制把 WAL 落盘并收敛（关闭前调用，避免留下 -wal 文件）。
     *
     * @throws SQLException 失败
     */
    public void checkpoint() throws SQLException {
        execute("PRAGMA wal_checkpoint(TRUNCATE)");
    }

    /**
     * 是否已连接。
     *
     * @return 已连接返回 true
     */
    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 执行 DDL/DML。
     *
     * @param sql SQL 语句
     * @return 影响行数
     * @throws SQLException 失败
     */
    public int execute(String sql) throws SQLException {
        Statement st = null;
        try {
            st = require().createStatement();
            return st.executeUpdate(sql);
        } finally {
            closeQuietly(st);
        }
    }

    /**
     * 查询第一行第一列。
     *
     * @param sql SQL 语句
     * @return 值；无结果返回 null
     * @throws SQLException 失败
     */
    public String queryFirstString(String sql) throws SQLException {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = require().createStatement();
            rs = st.executeQuery(sql);
            return rs.next() ? rs.getString(1) : null;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 查询单列全部值。
     *
     * @param sql SQL 语句
     * @return 值列表
     * @throws SQLException 失败
     */
    public List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = require().createStatement();
            rs = st.executeQuery(sql);
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 按列名查询多行。
     *
     * @param sql     SQL 语句
     * @param columns 列名
     * @return 行列表；每行与 {@code columns} 等长
     * @throws SQLException 失败
     */
    public List<String[]> queryRows(String sql, String[] columns) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = require().createStatement();
            rs = st.executeQuery(sql);
            int n = columns.length;
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) {
                    row[i] = rs.getString(columns[i]);
                }
                rows.add(row);
            }
            return rows;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 关闭连接。
     */
    public void close() {
        Connection conn = this.connection;
        this.connection = null;
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (Throwable ignored) {
            // 关闭失败不影响后续
        }
    }

    /**
     * 取连接。
     *
     * @return 连接
     * @throws SQLException 未打开
     */
    private Connection require() throws SQLException {
        if (connection == null) {
            throw new SQLException("SQLite 连接未打开");
        }
        return connection;
    }

    /**
     * 安静关闭。
     *
     * @param closeable 可关闭对象（{@code null} 忽略）
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 转义 SQL 字面量字符串。
     *
     * @param value 原值
     * @return 已转义的单引号包裹字面量
     */
    public static String quote(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
