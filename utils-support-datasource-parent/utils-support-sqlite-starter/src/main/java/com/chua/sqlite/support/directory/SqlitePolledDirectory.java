package com.chua.sqlite.support.directory;

import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
   * sqlite 数据库轮询目录实现，基于 JDBC 查询的快照对比机制。
 * <p>
   * 通过定期执行自定义 SQL 查询，对比前后结果快照发现数据变更（插入 / 更新 / 删除）。
 * 内部使用 {@link DiffPolledDirectory} 的差异对比算法：
 * <ol>
 *   <li>每次轮询执行配置的 SQL 查询，获取所有行</li>
 *   <li>每行编码为 {@code key|timestamp} 格式的字符串</li>
 *   <li>与前一次快照对比 key 的新增/消失和 timestamp 的变化</li>
 *   <li>通过 {@link com.chua.common.support.lang.directory.PolledListener} 分发变更事件</li>
 * </ol>
 * </p>
 * <p>
 * 环境配置属性：
 * <ul>
 *   <li>{@code jdbc.url} — JDBC 连接 URL（如 jdbc:sqlite:/data/test.db，<b>必填</b>）</li>
 *   <li>{@code query.sql} — 查询 SQL，第一列作为唯一键，第二列作为修改时间戳（默认 SELECT rowid, * FROM 表名）</li>
 *   <li>{@code query.key.column} — 作为唯一键的列索引（从 1 开始，默认 1）</li>
 *   <li>{@code query.ts.column} — 作为修改时间戳的列索引（从 1 开始，默认 2）</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * DirectoryPollerEnvironment env = new DirectoryPollerEnvironment(
 *     Set.of(WatcherEvent.CREATE, WatcherEvent.MODIFY, WatcherEvent.DELETE),
 *     3, TimeUnit.SECONDS)
 *     .setProperty("jdbc.url", "jdbc:sqlite:/data/test.db");
 *
 * SqlitePolledDirectory poller = new SqlitePolledDirectory("users", env);
 * poller.addListener(new SimplePolledListener(System.out::println));
 * poller.start(env);
 * }</pre> env);
 * poller.addListener(new SimplePolledListener(System.out::println));
 * poller.start(env);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see DiffPolledDirectory
 * @see com.chua.common.support.lang.directory.PolledDirectory
 */
@Slf4j
public class SqlitePolledDirectory extends DiffPolledDirectory<String> {

    /**
     * 安全 SQL 标识符校验规则（仅字母 / 数字 / 下划线）
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * JDBC 连接 URL，如 {@code jdbc:sqlite:/data/test.db}
     */
    private final String jdbcUrl;

    /**
     * 轮询时执行的 SQL 查询语句
     */
    private final String querySql;

    /**
     * 作为唯一键的列在查询结果中的索引（从 1 开始），
     * 用于识别同一行数据
     */
    private final int keyColumnIndex;

    /**
     * 作为修改时间戳的列在查询结果中的索引（从 1 开始），
     * 用于判断数据是否已更新
     */
    private final int tsColumnIndex;

    /**
      * 构造 sqlite 轮询目录。
     *
     * @param listenPath  逻辑路径（用于事件标识，通常为表名）
     * @param environment 环境配置，必须包含 JDBC.url 属性
     * @throws IllegalArgumentException 如果 JDBC.url 未配置
     */
    public SqlitePolledDirectory(String listenPath, DirectoryPollerEnvironment environment) {
        super(listenPath);
        this.jdbcUrl = environment.getProperty("jdbc.url");
        if (jdbcUrl == null) {
            throw new IllegalArgumentException("缺少必填配置: jdbc.url");
        }
        this.querySql = environment.getProperty("query.sql",
                "SELECT rowid, * FROM " + safeIdentifier(listenPath));
        this.keyColumnIndex = Integer.parseInt(
                environment.getProperty("query.key.column", "1"));
        this.tsColumnIndex = Integer.parseInt(
                environment.getProperty("query.ts.column", "2"));
    }

    /**
     * 执行 SQL 查询，获取当前所有行的快照。
     * <p>
     * 每行数据被编码为 {@code key|timestamp} 格式的字符串：
     * <ul>
     *   <li>{@code key} — 唯一键列的值，用于识别行</li>
     *   <li>{@code timestamp} — 时间戳列的值，用于判断变更</li>
     * </ul>
     * </p>
     *
     * @param path 逻辑路径（当前实现中未使用，查询 SQL 已由构造器确定）
     * @return 编码后的行快照列表
     */
    @Override
    protected List<String> listAndModified(String path) {
        List<String> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(querySql)) {

            while (rs.next()) {
                String key = rs.getString(keyColumnIndex);
                String ts = rs.getString(tsColumnIndex);
                if (key != null) {
                    rows.add(key + "|" + (ts != null ? ts : "0"));
                }
            }
        } catch (SQLException e) {
            log.error("SQLite 查询失败: {}", querySql, e);
        }
        return rows;
    }

    /**
     * 从编码后的快照字符串中提取文件名称（唯一键）。
     * <p>
     * 输入格式 {@code key|timestamp}，返回 {@code key} 部分。
     * 如果分隔符 {@code |} 不存在，返回整个字符串。
     * </p>
     *
     * @param item 编码后的快照字符串
     * @return 唯一键值
     */
    @Override
    protected String getFileName(String item) {
        int sep = item.indexOf('|');
        return sep > 0 ? item.substring(0, sep) : item;
    }

    /**
     * 从编码后的快照字符串中提取修改时间戳。
     * <p>
     * 输入格式 {@code key|timestamp}，返回 {@code timestamp} 部分。
      * 优先尝试解析为 long 类型；如果 时间戳 是字符串类型，
      * 使用其 哈希编码 作为粗糙的变更检测标识（仅用于判断是否变化，不表示实际时间）。
     * </p>
     *
     * @param item 编码后的快照字符串
     * @return 修改时间戳（毫秒）或变更标识
     */
    @Override
    protected Long getModified(String item) {
        int sep = item.indexOf('|');
        if (sep < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(item.substring(sep + 1));
        } catch (NumberFormatException e) {
            return (long) item.substring(sep + 1).hashCode();
        }
    }

    /**
     * 校验并返回安全的 SQL 标识符（仅允许字母、数字、下划线）。
     *
     * @param id 待校验标识符
     * @return 去除首尾空白后的标识符
     * @throws IllegalArgumentException 标识符非法时抛出
     */
    private String safeIdentifier(String id) {
        if (id == null) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        String trimmed = id.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("非法的 SQL 标识符: " + id);
        }
        return trimmed;
    }
}
