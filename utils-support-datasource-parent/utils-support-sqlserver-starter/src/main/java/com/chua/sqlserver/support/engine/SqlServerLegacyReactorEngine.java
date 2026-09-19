package com.chua.sqlserver.support.engine;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SQL 服务端 老版本兼容响应式引擎（SQL 服务端 2000/2005），使用 jtds 驱动。
 * 伪响应式实现，jtds 无 R2DBC 驱动。
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyReactorEngine extends JdbcReactorEngine {

    private final SqlServerLegacyEngine delegate = new SqlServerLegacyEngine(); // delegate

    /**
     * 添加一个 SQL Server 数据源（便捷重载）。
     * 委托给底层传统引擎注册数据源，若数据源可用则同步注册到响应式数据源表。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例（链式调用）
     */
    public SqlServerLegacyReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        delegate.addDataSource(name, host, port, database, username, password);
        EngineDataSource<?> ds = delegate.getDataSource(name);
        if (ds != null) {
            registerJdbcDataSource(name, ds.url(), username, password);
        }
        return this;
    }

    /**
     * 查询全部。
     *
     * @param table 表，不允许为 null
     * @return Flux 对象
     */
    public Flux<Map<String, Object>> queryAll(String table) {
        return query("SELECT * FROM " + safeIdentifier(table));
    }

    /**
     * 查询Where。
     *
     * @param table 表，不允许为 null
     * @param where 方法入参 where
     * @param params 参数，不允许为 null
     * @return Flux 对象
     */
    public Flux<Map<String, Object>> queryWhere(String table, String where, Object... params) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(safeIdentifier(table));
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        return query(sql.toString(), params);
    }

    /**
     * 插入。
     *
     * @param table 表，不允许为 null
     * @param cols 方法入参 cols
     * @param vals 方法入参 vals
     * @return Mono 对象
     */
    public Mono<Integer> insert(String table, String[] cols, Object... vals) {
        String safeTable = safeIdentifier(table);
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(safeTable)
                .append(" (").append(String.join(", ", safeColumns(cols))).append(") VALUES (");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        sb.append(")");
        return execute(sb.toString(), vals);
    }

    /**
     * 逐个校验插入列名。
     *
     * @param cols 列名数组，不允许为 null
     * @return 校验通过的列名
     * @throws IllegalArgumentException 列名非法时抛出
     */
    private String[] safeColumns(String[] cols) {
        if (cols == null) {
            throw new IllegalArgumentException("SQL 标识符不能为空");
        }
        String[] safe = new String[cols.length];
        for (int i = 0; i < cols.length; i++) {
            safe[i] = safeIdentifier(cols[i]);
        }
        return safe;
    }

    /**
     * 校验并返回安全的 SQL 标识符（字符集规则见 {@link SqlName#isWord(String)}）。
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
        if (!SqlName.isWord(trimmed)) {
            throw new IllegalArgumentException("非法的 SQL 标识符: " + id);
        }
        return trimmed;
    }
}
