package com.chua.datalake.support.engine;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.json.Json;
import com.chua.datalake.support.client.DefaultDatalakeHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询 Engine — 通过 HTTP客户端 调用 数据湖-启动 的 api服务端，
 * 实现标准 {@link com.chua.common.support.lang.datasource.engine.Engine} 接口。
 *
 * <p>使用时注入 baseUrl 即可，运行时 HTTP 调用。该引擎只读：
 * {@link #addDataSource(String, EngineDataSource)}、{@link #store(String, List)}
 * 等写入口一律抛 {@link UnsupportedOperationException}，避免调用方误以为数据已落库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class HttpDatalakeQueryEngine implements com.chua.common.support.lang.datasource.engine.Engine {

    /**
     * HTTP 客户端
     */
      private final DefaultDatalakeHttpClient httpClient;

      /**
       * 关闭标记：{@link #close()} 后置位，使 close() 幂等且 {@link #isClosed()} 可查询。
       * <p>HttpDatalakeQueryEngine 直接实现 {@code Engine} 而非继承 {@code AbstractEngine}，
       * 因此不继承基类的关闭标记，需自行维护。</p>
       */
      private final java.util.concurrent.atomic.AtomicBoolean closed =
              new java.util.concurrent.atomic.AtomicBoolean(false);


    /**
     * 默认数据源名称
     */
    private final String defaultName = "datalake";

    /**
     * 构造。
     *
     * @param baseUrl 数据湖-启动 提供的 API 服务地址
     */
    public HttpDatalakeQueryEngine(String baseUrl) {
        this.httpClient = new DefaultDatalakeHttpClient(baseUrl);
    }

    @Override
    /**
     * 添加数据源
    */
    public <T> com.chua.common.support.lang.datasource.engine.Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 只读，不接受本地数据源注册");
    }

    @Override
    /**
     * 存储
    */
    public <T> com.chua.common.support.lang.datasource.engine.Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 只读，不接受写入");
    }

    @Override
    /**
     * 设置默认数据源名称
    */
    public com.chua.common.support.lang.datasource.engine.Engine setDefaultDataSourceName(String name) {
        return this;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor(String dataSourceName) {
        return new HttpSqlExecutor();
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor() {
        return getExecutor(defaultName);
    }

    @Override
    /**
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return null;
    }

    @Override
    /**
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource() {
        return getDataSource(defaultName);
    }

    @Override
    /**
     * 查询
    */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 查询，请使用 getExecutor()");
    }

    @Override
    /**
     * 更新
    */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 更新");
    }

    @Override
    /**
     * 删除
    */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 删除");
    }

    @Override
    /**
     * 获取Dialect
    */
    public com.chua.common.support.lang.datasource.dialect.Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    /**
     * 获取默认数据源名称
    */
    public String getDefaultDataSourceName() {
        return defaultName;
    }

    @Override
    /**
     * Meta
    */
    public MetaData meta() {
        throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持元数据操作");
    }

    @Override
    /**
     * 不支持元数据操作
    */
    public boolean supportsMeta() {
        return false;
    }

    @Override
    /**
     * 关闭
    */
      public void close() {
          if (!closed.compareAndSet(false, true)) {
              return;
          }
          httpClient.close();
      }

      @Override
      /**
       * 是否已关闭
       *
       * @return 已关闭返回 true
       */
      public boolean isClosed() {
          return closed.get();
      }


    /**
     * SQL 执行器：内部委托 HTTP 客户端。
     * @author CH
     * @since 4.0.0
     */
    private class HttpSqlExecutor implements SqlExecutor {

        @Override
        /**
         * 查询
        */
        public List<Map<String, Object>> query(String sql, Object... params) {
            try {
                return httpClient.queryAsList(sql, params);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("[datalake-query] " + e.getMessage(), e);
            }
        }

        @Override
        /**
         * 查询
        */
        public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
            List<Map<String, Object>> rows = query(sql, params);
            List<T> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                result.add(Json.getMapper().convertValue(row, rowType));
            }
            return result;
        }

        @Override
        /**
         * 查询Page
        */
        public List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params) {
            List<Map<String, Object>> rows = query(sql, params);
            if (pagination == null) {
                return rows;
            }
            // 查询端点没有分页通道，只能在客户端切片：整表已取回，total 取实际行数
            int size = Math.max(0, pagination.getPageSize());
            int from = Math.min(rows.size(), Math.max(0, (pagination.getPageNum() - 1) * size));
            int to = size == 0 ? from : Math.min(rows.size(), from + size);
            pagination.setTotal(rows.size());
            return new ArrayList<>(rows.subList(from, to));
        }

        @Override
        /**
         * 执行
        */
        public int execute(String sql, Object... params) {
            String body;
            try {
                body = httpClient.queryWithArgs(sql, params);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("[datalake-query] " + e.getMessage(), e);
            }
            return parseAffected(body);
        }

        @Override
        /**
         * 批量
        */
        public int[] batch(String sql, List<Object[]> batchParams) {
            throw new UnsupportedOperationException(
                    "[datalake-query] 查询端点未定义参数通道，不支持批量执行");
        }

        /**
         * 解析受影响行数。
         *
         * @param body 响应体
         * @return 受影响行数
         */
        private int parseAffected(String body) {
            String text = body == null ? "" : body.trim();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "[datalake-query] 执行结果不是受影响行数: " + text, e);
            }
        }
    }
}
