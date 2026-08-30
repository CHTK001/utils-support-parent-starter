package com.chua.sqlserver.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SQL Server 老版本兼容响应式引擎（SQL Server 2000/2005），使用 jTDS 驱动。
 * 伪响应式实现，jTDS 无 R2DBC 驱动。
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyReactorEngine extends JdbcReactorEngine {

    private final SqlServerLegacyEngine delegate = new SqlServerLegacyEngine();

    public SqlServerLegacyReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        delegate.addDataSource(name, host, port, database, username, password);
        EngineDataSource<?> ds = delegate.getDataSource(name);
        if (ds != null) {
            registerJdbcDataSource(name, ds.url(), username, password);
        }
        return this;
    }

    public Flux<Map<String, Object>> queryAll(String table) {
        return query("SELECT * FROM " + table);
    }

    public Flux<Map<String, Object>> queryWhere(String table, String where, Object... params) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        return query(sql.toString(), params);
    }

    public Mono<Integer> insert(String table, String[] cols, Object... vals) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(String.join(", ", cols)).append(") VALUES (");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        return execute(sb.toString(), vals);
    }
}
