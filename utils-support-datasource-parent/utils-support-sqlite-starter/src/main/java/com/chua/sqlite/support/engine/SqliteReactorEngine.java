package com.chua.sqlite.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * SQLite 真响应式引擎，对应同步侧 {@link SqliteEngine}。
 *
 * <p>通过 FFM（Project Panama）绑定 {@code sqlite3_hook.dll} 动态库，
 * 利用 {@code sqlite3_update_hook} 实时捕获 INSERT/UPDATE/DELETE 变更事件，
 * 以 {@link reactor.core.publisher.Sinks.Many} 推送到 {@link #changes()} 响应式流。</p>
 *
 * <ul>
 *   <li>写操作（INSERT/UPDATE/DELETE）→ 经 hook 连接执行，变更事件同步推送</li>
 *   <li>读操作（SELECT）→ 走 HikariCP 连接池 JDBC 路径，支持并发读</li>
 *   <li>变更事件 → {@link #changes()} 返回 replay Flux，支持多订阅者</li>
 * </ul>
 *
 * <pre>{@code
 * SqliteReactorEngine engine = new SqliteReactorEngine();
 * engine.addDataSource("default", "data/mydb.sqlite");
 *
 * // 变更监听（真响应式，需先订阅再写）
 * engine.changes().subscribe(e ->
 *     System.out.println(e.getType() + " on " + e.getTable()));
 *
 * // 写操作自动触发事件
 * engine.execute("INSERT INTO users(name) VALUES('张三')").block();
 *
 * // 查询
 * Flux<Map<String, Object>> rows = engine.query("SELECT * FROM users");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@ Spi("sqlite")
public class SqliteReactorEngine extends JdbcReactorEngine {

    private static final Pattern WRITE_PATTERN = Pattern.compile(
            "^\\s*(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE)\\s",
            Pattern.CASE_INSENSITIVE);

    private SqliteHookConnection hookConnection;

    /**
     * 添加一个 SQLite 数据源。
     *
     * @param name     数据源名称
     * @param filePath SQLite 数据库文件路径
     * @return 当前引擎实例
     */
    public SqliteReactorEngine addDataSource(String name, String filePath) {
        SqliteEngine delegate = new SqliteEngine();
        delegate.addDataSource(name, filePath);
        this.delegate = delegate;

        registerJdbcDataSource(name, "jdbc:sqlite:" + filePath, null, null);
        this.hookConnection = SqliteHookConnection.open(filePath);
        return this;
    }

    /**
     * 变更事件响应式流。
     *
     * <p>每个订阅者在订阅时都会收到来自 hook 连接的实时变更推送（INSERT/UPDATE/DELETE）。
     * 流为 replay 模式（缓冲区上限 1024 条），新订阅者不会错过近期历史事件。</p>
     *
     * @return 变更事件 Flux
     */
    public Flux<SqliteChangeEvent> changes() {
        if (hookConnection == null) {
            return Flux.empty();
        }
        return hookConnection.changes();
    }

    /**
     * 响应式原生 SQL 执行。
     *
     * <p>写操作（INSERT/UPDATE/DELETE 等）走 hook 连接，确保变更事件触发；
     * 读操作走 JDBC 连接池。</p>
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 受影响行数 Mono
     */
    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        if (isWriteOperation(sql)) {
            return executeViaHook(sql);
        }
        return super.execute(sql, params);
    }

    /**
     * 响应式批量操作。
     *
     * <p>批量写操作逐条走 hook 连接，确保每条变更都触发事件。</p>
     *
     * @param sql         SQL 模板
     * @param batchParams 批量参数列表
     * @return 每批影响行数 Flux
     */
    @Override
    public Flux<Integer> batch(String sql, java.util.List<Object[]> batchParams) {
        if (!isWriteOperation(sql) || hookConnection == null) {
            return super.batch(sql, batchParams);
        }
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(batchParams)
                .flatMap(paramArray -> executeViaHook(formatBatchSql(sql, paramArray)))
                .onErrorResume(e -> super.batch(sql, batchParams));
    }

    /* ==================== 内部实现 ==================== */

    private Mono<Integer> executeViaHook(String sql) {
        if (hookConnection == null) {
            return super.execute(sql);
        }
        try {
            int rc = hookConnection.exec(sql);
            return (rc == 0) ? Mono.just(1) : Mono.error(
                    new IllegalStateException("SQLite hook_exec failed, rc=" + rc));
        } catch (Throwable e) {
            return super.execute(sql);
        }
    }

    private static boolean isWriteOperation(String sql) {
        return sql != null && WRITE_PATTERN.matcher(sql).find();
    }

    private static String formatBatchSql(String sql, Object[] params) {
        if (params == null || params.length == 0) return sql;
        StringBuilder sb = new StringBuilder(sql.length() + params.length * 16);
        int pi = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                if (pi < params.length) {
                    Object v = params[pi++];
                    sb.append(quoteLiteral(v));
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String quoteLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        String s = v.toString().replace("'", "''");
        return "'" + s + "'";
    }

    /**
     * 关闭引擎，释放所有资源（包括 hook 连接）。
     */
    @Override
    public void close() {
        if (hookConnection != null) {
            hookConnection.close();
            hookConnection = null;
        }
        super.close();
    }
}
