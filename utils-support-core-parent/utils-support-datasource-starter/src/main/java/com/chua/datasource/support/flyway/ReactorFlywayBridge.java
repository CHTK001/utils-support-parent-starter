package com.chua.datasource.support.flyway;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.datasource.support.engine.JdbcReactorEngine;

import java.util.List;
import java.util.Map;

/**
 * 响应式引擎 → 同步 Engine 桥接适配器。
 * <p>
 * 供 {@link com.chua.common.support.lang.datasource.flyway.DefaultFlyway} 复用：
 * Flyway 仅依赖 {@code execute(sql, params)} 与 {@code getExecutor().query(sql)}，
 * 本桥接将其转发到 {@link JdbcReactorEngine} 的阻塞执行路径（JDBC/R2DBC 降级链）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ReactorFlywayBridge implements Engine {

    /** 目标响应式引擎 */
    private final JdbcReactorEngine delegate;

    /**
     * 构造桥接适配器。
     *
     * @param delegate 目标响应式引擎
     */
    public ReactorFlywayBridge(JdbcReactorEngine delegate) {
        this.delegate = delegate;
    }

    @Override
    public int execute(String sql, Object... params) {
        Integer affected = delegate.execute(sql, params).block();
        return affected == null ? 0 : affected;
    }

    @Override
    public SqlExecutor getExecutor() {
        return new SqlExecutor() {
            @Override
            public List<Map<String, Object>> query(String sql, Object... params) {
                List<Map<String, Object>> rows = delegate.query(sql, params).collectList().block();
                return rows == null ? List.of() : rows;
            }

            @Override
            public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
                List<T> rows = delegate.query(sql, rowType, params).collectList().block();
                return rows == null ? List.of() : rows;
            }

            @Override
            public List<Map<String, Object>> queryPage(String sql,
                    com.chua.common.support.lang.datasource.dialect.Pagination pagination, Object... params) {
                throw new UnsupportedOperationException("桥接执行器不支持分页");
            }

            @Override
            public int execute(String sql, Object... params) {
                return ReactorFlywayBridge.this.execute(sql, params);
            }

            @Override
            public int[] batch(String sql, List<Object[]> batchParams) {
                throw new UnsupportedOperationException("桥接执行器不支持批量");
            }
        };
    }

    // ==================== 其余接口方法：桥接场景不需要 ====================

    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Engine setDefaultDataSourceName(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SqlExecutor getExecutor(String dataSourceName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> EngineDataSource<T> getDataSource(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> EngineDataSource<T> getDataSource() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Dialect getDialect(String dataSourceName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MetaData meta() {
        throw new UnsupportedOperationException();
    }

    @Override
    /** 不支持元数据操作 */
    public boolean supportsMeta() {
        return false;
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }
}
