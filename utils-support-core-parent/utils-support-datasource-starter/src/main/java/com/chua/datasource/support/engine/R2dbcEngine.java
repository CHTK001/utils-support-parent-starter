package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import io.r2dbc.spi.ConnectionFactory;

/**
 * R2DBC 同步引擎适配器，包装 {@link R2dbcSqlExecutor}。
 *
 * <p>作为 {@link AbstractR2dbcReactorEngine} 内部的委托引擎，
 * 使得 {@link com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper}
 * 等响应式 Lambda 包装器能够透明地通过 R2DBC 驱动执行 SQL（底层在 boundedElastic
 * 调度器上阻塞获取结果）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class R2dbcEngine extends AbstractEngine {

    /** R2DBC SQL 执行器 */
    private final R2dbcSqlExecutor executor;

    /**
     * 构造 R2DBC 引擎适配器。
     *
     * @param factory R2DBC 连接工厂
     * @param dialect 方言，可为 null
     */
    public R2dbcEngine(ConnectionFactory factory, Dialect dialect) {
        this.executor = new R2dbcSqlExecutor(factory, dialect);
    }

    /**
     * 获取 R2DBC SQL 执行器。
     *
     * @return R2DBC SQL 执行器
     */
    @Override
    public SqlExecutor getExecutor() {
        return executor;
    }

    /**
     * 获取 R2DBC 连接工厂。
     *
     * @return 连接工厂
     */
    public ConnectionFactory getConnectionFactory() {
        return executor.getFactory();
    }

    /**
     * 获取方言。
     *
     * @return 方言
     */
    public Dialect getDialect() {
        return executor.getDialect();
    }

    @Override
    public Dialect getDialect(String dataSourceName) {
        return executor.getDialect();
    }

    @Override
    public EngineDataSource<Object> getDataSource(String name) {
        return null;
    }

    @Override
    public EngineDataSource<Object> getDataSource() {
        return null;
    }

    /**
     * 基于 R2DBC 执行查询。
     *
     * @param where       WHERE 子句
     * @param params      参数值
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 查询结果
     */
    @Override
    protected <T> java.util.List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass) {
        String tableName = entityClass.getSimpleName().toLowerCase();
        StringBuilder fullSql = new StringBuilder("SELECT * FROM ").append(tableName);
        if (where != null && !where.trim().isEmpty()) {
            fullSql.append(" WHERE ").append(where);
        }
        return executor.query(fullSql.toString(), entityClass, params != null ? params : new Object[0]);
    }

    @Override
    public void close() {
        ConnectionFactory factory = executor.getFactory();
        if (factory instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
            }
        }
    }
}