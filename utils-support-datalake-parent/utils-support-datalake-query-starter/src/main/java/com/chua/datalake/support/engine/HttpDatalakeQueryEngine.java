package com.chua.datalake.support.engine;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.datalake.support.client.DatalakeHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 查询 Engine — 通过 HttpClient 调用 datalake-starter 的 ApiServer，
 * 实现标准 {@link com.chua.common.support.lang.datasource.engine.Engine} 接口。
 *
 * <p>使用时注入 baseUrl 即可，运行时 HTTP 调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class HttpDatalakeQueryEngine implements com.chua.common.support.lang.datasource.engine.Engine {

    /**
     * HTTP 客户端
     */
    private final DatalakeHttpClient client;

    /**
     * 默认数据源名称
     */
    private final String defaultName = "datalake";

    /**
     * 构造。
     *
     * @param baseUrl datalake-starter 提供的 API 服务地址
     */
    public HttpDatalakeQueryEngine(String baseUrl) {
        this.client = new DatalakeHttpClient(baseUrl);
    }

    @Override
    /** 添加DataSource */
    public <T> com.chua.common.support.lang.datasource.engine.Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        return this;
    }

    @Override
    /** Store */
    public <T> com.chua.common.support.lang.datasource.engine.Engine store(String name, List<T> data) {
        return this;
    }

    @Override
    /** 设置DefaultDataSourceName */
    public com.chua.common.support.lang.datasource.engine.Engine setDefaultDataSourceName(String name) {
        return this;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor(String dataSourceName) {
        return new HttpSqlExecutor();
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor() {
        return getExecutor(defaultName);
    }

    @Override
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return null;
    }

    @Override
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource() {
        return getDataSource(defaultName);
    }

    @Override
    /** 查询 */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 查询，请使用 getExecutor()");
    }

    @Override
    /** 更新 */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 更新");
    }

    @Override
    /** 删除 */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 删除");
    }

    @Override
    /** 获取Dialect */
    public com.chua.common.support.lang.datasource.dialect.Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    /** 获取DefaultDataSourceName */
    public String getDefaultDataSourceName() {
        return defaultName;
    }

    @Override
    /** Meta */
    public MetaData meta() {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持元数据操作");
    }

    @Override
    /** 关闭 */
    public void close() {
        client.close();
    }

    /**
     * SQL 执行器：内部委托 HTTP 客户端。
     */
    private class HttpSqlExecutor implements SqlExecutor {

        @Override
        /** 查询 */
        public List<Map<String, Object>> query(String sql, Object... params) {
            try {
                String body = client.query(sql);
                log.info("[datalake-query] 查询执行: sql={}, body={}", sql, body);
                return List.of();
            } catch (Exception e) {
                log.error("[datalake-query] 查询执行失败: sql={}", sql, e);
                return List.of();
            }
        }

        @Override
        /** 查询 */
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            log.warn("[datalake-query] 不支持类型映射查询，请改用 query(sql, params)");
            return List.of();
        }

        @Override
        /** 查询Page */
        public List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params) {
            return query(sql, params);
        }

        @Override
        /** 执行 */
        public int execute(String sql, Object... params) {
            try {
                client.query(sql);
                return 0;
            } catch (Exception e) {
                log.error("[datalake-query] 执行失败: sql={}", sql, e);
                return 0;
            }
        }

        @Override
        /** Batch */
        public int[] batch(String sql, List<Object[]> batchParams) {
            return new int[0];
        }
    }
}