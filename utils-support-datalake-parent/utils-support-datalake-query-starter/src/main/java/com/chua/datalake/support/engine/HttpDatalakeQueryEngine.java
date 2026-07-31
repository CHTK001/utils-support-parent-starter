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
 * @since 4.0.0.43
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
    public <T> com.chua.common.support.lang.datasource.engine.Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        return this;
    }

    @Override
    public <T> com.chua.common.support.lang.datasource.engine.Engine store(String name, List<T> data) {
        return this;
    }

    @Override
    public com.chua.common.support.lang.datasource.engine.Engine setDefaultDataSourceName(String name) {
        return this;
    }

    @Override
    public SqlExecutor getExecutor(String dataSourceName) {
        return new HttpSqlExecutor();
    }

    @Override
    public SqlExecutor getExecutor() {
        return getExecutor(defaultName);
    }

    @Override
    public <T> EngineDataSource<T> getDataSource(String name) {
        return null;
    }

    @Override
    public <T> EngineDataSource<T> getDataSource() {
        return getDataSource(defaultName);
    }

    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("HttpDatalakeQueryEngine 不支持 Lambda 查询，请使用 getExecutor()");
    }

    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("HttpDatalakeQueryEngine 不支持 Lambda 更新");
    }

    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("HttpDatalakeQueryEngine 不支持 Lambda 删除");
    }

    @Override
    public com.chua.common.support.lang.datasource.dialect.Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    public String getDefaultDataSourceName() {
        return defaultName;
    }

    @Override
    public MetaData meta() {
        throw new UnsupportedOperationException("HttpDatalakeQueryEngine 不支持元数据操作");
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * SQL 执行器：内部委托 HTTP 客户端。
     */
    private class HttpSqlExecutor implements SqlExecutor {

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) {
            try {
                String body = client.query(sql);
                log.info("HttpDatalakeQueryEngine query: sql={}, body={}", sql, body);
                return List.of();
            } catch (Exception e) {
                log.error("HttpDatalakeQueryEngine query failed: sql={}", sql, e);
                return List.of();
            }
        }

        @Override
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            log.warn("HttpDatalakeQueryEngine 不支持类型映射查询，请用 query(sql, params)");
            return List.of();
        }

        @Override
        public List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params) {
            return query(sql, params);
        }

        @Override
        public int execute(String sql, Object... params) {
            try {
                client.query(sql);
                return 0;
            } catch (Exception e) {
                log.error("HttpDatalakeQueryEngine execute failed: sql={}", sql, e);
                return 0;
            }
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            return new int[0];
        }
    }
}