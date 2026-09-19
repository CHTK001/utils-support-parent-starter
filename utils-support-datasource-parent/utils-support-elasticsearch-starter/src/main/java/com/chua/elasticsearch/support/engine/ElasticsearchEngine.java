package com.chua.elasticsearch.support.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.elasticsearch.support.meta.EsMetaData;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.reflection.ReflectUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elasticsearch 搜索引擎实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("elasticsearch")
public class ElasticsearchEngine implements Engine {

    /**
     * 已注册的数据源映射（数据源名称 → 数据源）
     */
    private final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称
     */
    private String defaultDataSourceName;

    /**
     * Elasticsearch 客户端实例
     */
    private ElasticsearchClient client;

    /**
     * 本引擎创建的 RestClient，close() 时统一回收（外部传入的客户端不归本引擎管）
     */
    private final List<RestClient> ownedRestClients = new ArrayList<>();

    @Override
    /**
     * 添加数据源
    */
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        Object src = ds.getSource();
        if (src instanceof ElasticsearchClient esClient) {
            client = esClient;
        } else if (src instanceof String url) {
            client = createClient(url);
        }
        dataSources.put(name, (EngineDataSource<Object>) ds);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 由连接串创建 ES 客户端，RestClient 记入回收列表。
     *
     * @param url es:// 或 http(s)://host:port 连接串
     * @return ES 客户端
     */
    private ElasticsearchClient createClient(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.replaceFirst("^(es|elasticsearch)://", "http://"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Elasticsearch 连接串非法: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost() == null ? "localhost" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 9200;
        RestClient restClient = RestClient.builder(new HttpHost(host, port, scheme)).build();
        ownedRestClients.add(restClient);
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    @Override
    /**
     * 设置默认数据源名称
    */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /**
     * 获取默认数据源名称
    */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /**
     * Meta
    */
    public MetaData meta() {
        return new EsMetaData(this);
    }

    @Override
    /**
     * 支持元数据操作（meta() 返回真实实现）
    */
    public boolean supportsMeta() {
        return true;
    }

    /**
     * 获取客户端
     *
     * @return 获取客户端的结果
     */
    public ElasticsearchClient getClient() {
        return client;
    }

    @Override
    /**
     * 存储
    */
    public <T> Engine store(String name, List<T> data) {
        if (client == null) {
            throw new IllegalStateException("请先 addDataSource 配置 Elasticsearch 客户端");
        }
        if (data == null || data.isEmpty()) {
            return this;
        }
        try {
            var response = client.bulk(builder -> {
                for (T entity : data) {
                    builder.operations(op -> op.index(io -> io.index(name).document(entity)));
                }
                return builder;
            });
            if (response.errors()) {
                long failed = response.items().stream()
                        .filter(item -> item.error() != null).count();
                throw new RuntimeException("Elasticsearch 批量索引部分失败: index=" + name
                        + " 失败条数=" + failed);
            }
        } catch (Exception e) {
            throw new RuntimeException("Elasticsearch 批量索引失败: index=" + name, e);
        }
        return this;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor(String n) {
        return null;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取数据源
     *
     * @param n n
     * @return 获取数据源的结果
     */
    public <T> EngineDataSource<T> getDataSource(String n) {
        return (EngineDataSource<T>) dataSources.get(n);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取数据源
     *
     * @return 获取数据源的结果
     */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    /**
     * 获取Dialect
    */
    public Dialect getDialect(String n) {
        return Dialect.getExtension("elasticsearch");
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        for (RestClient restClient : ownedRestClients) {
            try {
                restClient.close();
            } catch (Exception e) {
                log.warn("[elasticsearch-datasource] RestClient 关闭失败: {}", e.getMessage());
            }
        }
        ownedRestClients.clear();
        client = null;
        dataSources.clear();
    }

    @Override
    /**
     * 查询
    */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new LambdaQueryWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
             * @param col col
             * @param col col
             * @param pn pn
             * @param ps ps
             * @param ps ps
             * @param ps ps
             * @param to 转为
             * @param entityClass 实体类
             * @param col col
             * @param col col
             * @param entityClass 实体类
             * @param col col
             * @param col col
             * @param entityClass 实体类
             * @param conditions 条件
             * @param m m
             * @param entityClass 实体类
             * @param e e
             * @param conditions 条件
             * @param m m
             * @param c c
             * @param m m
             * @param m m
             * @param op op
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param value 值
             * @param s s
             * @param l l
             * @param i i
             * @param s s
             * @param b b
             * @param d d
             * @param f f
             * @param b b
             * @param value 值
             * @param source 源
             * @param entityClass 实体类
             * @param converted 转换
             * @param e e
             * @param e e
             * @param value 值
             * @param targetType 目标类型
             * @param num num
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaQueryWrapper<T> newInstance() {
                return new LambdaQueryWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                     * @param col col
                     * @param pn pn
                     * @param ps ps
                     * @param ps ps
                     * @param ps ps
                     * @param to 转为
                     * @param entityClass 实体类
                     * @param col col
                     * @param col col
                     * @param entityClass 实体类
                     * @param col col
                     * @param col col
                     * @param entityClass 实体类
                     * @param conditions 条件
                     * @param m m
                     * @param entityClass 实体类
                     * @param e e
                     * @param conditions 条件
                     * @param m m
                     * @param c c
                     * @param m m
                     * @param m m
                     * @param op op
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param value 值
                     * @param s s
                     * @param l l
                     * @param i i
                     * @param s s
                     * @param b b
                     * @param d d
                     * @param f f
                     * @param b b
                     * @param value 值
                     * @param source 源
                     * @param entityClass 实体类
                     * @param converted 转换
                     * @param e e
                     * @param e e
                     * @param value 值
                     * @param targetType 目标类型
                     * @param num num
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 列表
            */
            public List<T> list() {
                return search(entityClass, getConditions(), getOrderBys(), getOffset(), getLimit());
            }

            @Override
            /**
             * One
            */
            public T one() {
                List<T> results = search(entityClass, getConditions(), getOrderBys(), 0, 1);
                if (results.isEmpty()) {
                    return null;
                }
                return results.getFirst();
            }

            @Override
            /**
             * Page
            */
            public Page<T> page(int pn, int ps) {
                return searchPage(entityClass, getConditions(), getOrderBys(), pn, ps);
            }
        };
    }

    @Override
    /**
     * 更新
    */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
             * @param col col
             * @param col col
             * @param entityClass 实体类
             * @param col col
             * @param col col
             * @param entityClass 实体类
             * @param conditions 条件
             * @param m m
             * @param entityClass 实体类
             * @param e e
             * @param conditions 条件
             * @param m m
             * @param c c
             * @param m m
             * @param m m
             * @param op op
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param value 值
             * @param s s
             * @param l l
             * @param i i
             * @param s s
             * @param b b
             * @param d d
             * @param f f
             * @param b b
             * @param value 值
             * @param source 源
             * @param entityClass 实体类
             * @param converted 转换
             * @param e e
             * @param e e
             * @param value 值
             * @param targetType 目标类型
             * @param num num
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                     * @param col col
                     * @param entityClass 实体类
                     * @param col col
                     * @param col col
                     * @param entityClass 实体类
                     * @param conditions 条件
                     * @param m m
                     * @param entityClass 实体类
                     * @param e e
                     * @param conditions 条件
                     * @param m m
                     * @param c c
                     * @param m m
                     * @param m m
                     * @param op op
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param value 值
                     * @param s s
                     * @param l l
                     * @param i i
                     * @param s s
                     * @param b b
                     * @param d d
                     * @param f f
                     * @param b b
                     * @param value 值
                     * @param source 源
                     * @param entityClass 实体类
                     * @param converted 转换
                     * @param e e
                     * @param e e
                     * @param value 值
                     * @param targetType 目标类型
                     * @param num num
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 更新
            */
            public int update() {
                throw new UnsupportedOperationException("Elasticsearch 引擎不支持条件更新");
            }
        };
    }

    @Override
    /**
     * 删除
    */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
             * @param col col
             * @param col col
             * @param entityClass 实体类
             * @param conditions 条件
             * @param m m
             * @param entityClass 实体类
             * @param e e
             * @param conditions 条件
             * @param m m
             * @param c c
             * @param m m
             * @param m m
             * @param op op
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param field 字段
             * @param values 值
             * @param m m
             * @param value 值
             * @param s s
             * @param l l
             * @param i i
             * @param s s
             * @param b b
             * @param d d
             * @param f f
             * @param b b
             * @param value 值
             * @param source 源
             * @param entityClass 实体类
             * @param converted 转换
             * @param e e
             * @param e e
             * @param value 值
             * @param targetType 目标类型
             * @param num num
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                     * @param col col
                     * @param entityClass 实体类
                     * @param conditions 条件
                     * @param m m
                     * @param entityClass 实体类
                     * @param e e
                     * @param conditions 条件
                     * @param m m
                     * @param c c
                     * @param m m
                     * @param m m
                     * @param op op
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param field 字段
                     * @param values 值
                     * @param m m
                     * @param value 值
                     * @param s s
                     * @param l l
                     * @param i i
                     * @param s s
                     * @param b b
                     * @param d d
                     * @param f f
                     * @param b b
                     * @param value 值
                     * @param source 源
                     * @param entityClass 实体类
                     * @param converted 转换
                     * @param e e
                     * @param e e
                     * @param value 值
                     * @param targetType 目标类型
                     * @param num num
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 移除
            */
            public int remove() {
                throw new UnsupportedOperationException("Elasticsearch 引擎不支持条件删除");
            }
        };
    }

    // ---------------------------------------------------------------
 // 查询执行 — 条件翻译为 ES 查询 DSL
    // ---------------------------------------------------------------

    /**
     * 执行查询，将条件列表翻译为 ES 查询 并搜索。
     *
     * @param entityClass 实体类类型
     * @param conditions  条件列表（来自 lambda查询包装器）
     * @param orderBys    排序列表（"col ASC"/"col DESC"），可为 null
     * @param from        起始偏移，0 表示不限制
     * @param size        返回上限，0 表示不限制
     * @param <T>         实体类型
     * @return 查询结果列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> search(Class<T> entityClass, List<Condition> conditions,
                               List<String> orderBys, int from, int size) {
        try {
            var response = doSearch(entityClass, conditions, orderBys, from, size, false);
            return response.hits().hits().stream()
                    .map(h -> mapToEntity((Map<String, Object>) h.source(), entityClass))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Elasticsearch 查询失败: index="
                    + entityClass.getSimpleName().toLowerCase(), e);
        }
    }

    /**
     * 分页查询：from/size 与排序下推 ES 服务端，total 取自信任计数。
     *
     * @param entityClass 实体类类型
     * @param conditions  条件列表
     * @param orderBys    排序列表
     * @param pageNum     页码（从 1 开始）
     * @param pageSize    页大小
     * @param <T>         实体类型
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private <T> Page<T> searchPage(Class<T> entityClass, List<Condition> conditions,
                                   List<String> orderBys, int pageNum, int pageSize) {
        String indexName = entityClass.getSimpleName().toLowerCase();
        try {
            var response = doSearch(entityClass, conditions, orderBys,
                    (pageNum - 1) * pageSize, pageSize, true);
            List<T> records = response.hits().hits().stream()
                    .map(h -> mapToEntity((Map<String, Object>) h.source(), entityClass))
                    .toList();
            long total = response.hits().total() == null
                    ? records.size() : response.hits().total().value();
            return new Page<>(pageNum, pageSize, total, records);
        } catch (Exception e) {
            throw new RuntimeException("Elasticsearch 分页查询失败: index=" + indexName, e);
        }
    }

    /**
     * 构建并执行 ES 搜索请求。
     *
     * @param entityClass 实体类（用于推导索引名）
     * @param conditions 条件列表
     * @param orderBys 排序列表
     * @param from from偏移
     * @param size size数量
     * @param trackTotal 是否启用 trackTotal 计数
     * @return 响应 对象
     */
    private co.elastic.clients.elasticsearch.core.SearchResponse<Map> doSearch(
            Class<?> entityClass, List<Condition> conditions,
            List<String> orderBys, int from, int size, boolean trackTotal) {
        if (client == null) {
            throw new IllegalStateException("请先 addDataSource 配置 Elasticsearch 客户端");
        }
        String indexName = entityClass.getSimpleName().toLowerCase();
        Query query = CollectionUtils.isEmpty(conditions)
                ? Query.of(q -> q.matchAll(m -> m)) : buildQuery(conditions);
        try {
            return client.search(s -> {
                s.index(indexName).query(query);
                if (from > 0) {
                    s.from(from);
                }
                if (size > 0) {
                    s.size(size);
                }
                if (trackTotal) {
                    s.trackTotalHits(t -> t.enabled(true));
                }
                applySorts(s, orderBys);
                return s;
            }, Map.class);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Elasticsearch 搜索 IO 失败: index=" + indexName, e);
        }
    }

    /**
     * 将 wrapper 排序片段应用为 ES sort 子句。
     *
     * @param builder builder 对象
     * @param orderBys 排序 对象列表
     */
    private static void applySorts(
            co.elastic.clients.elasticsearch.core.SearchRequest.Builder builder,
            List<String> orderBys) {
        if (orderBys == null || orderBys.isEmpty()) {
            return;
        }
        for (String orderBy : orderBys) {
            String[] parts = orderBy.trim().split("\\s+");
            String field = parts[0];
            co.elastic.clients.elasticsearch._types.SortOrder sort =
                    parts.length > 1 && "DESC".equalsIgnoreCase(parts[1])
                            ? co.elastic.clients.elasticsearch._types.SortOrder.Desc
                            : co.elastic.clients.elasticsearch._types.SortOrder.Asc;
            builder.sort(s -> s.field(f -> f.field(field).order(sort)));
        }
    }

    // ---------------------------------------------------------------
 // 条件 → ES 查询 翻译
    // ---------------------------------------------------------------

    /**
     * 将条件列表（和 连接）翻译为 ES 查询。
     *
     * @param conditions 条件列表
     * @return ES 查询
     */
    static Query buildQuery(List<Condition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        if (conditions.size() == 1) {
            return buildConditionQuery(conditions.getFirst());
        }
        List<Query> mustQueries = new ArrayList<>(conditions.size());
        for (Condition c : conditions) {
            mustQueries.add(buildConditionQuery(c));
        }
        return Query.of(q -> q.bool(b -> b.must(mustQueries)));
    }

    /**
     * 翻译单个 条件 为 ES 查询。
     *
     * @param c 条件
     * @return ES 查询
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Query buildConditionQuery(Condition c) {
        if (c.isNested()) {
            List<Condition> nested = c.getNested();
            if (CollectionUtils.isEmpty(nested)) {
                return Query.of(q -> q.matchAll(m -> m));
            }
            List<Query> subQueries = new ArrayList<>(nested.size());
            for (Condition sub : nested) {
                subQueries.add(buildConditionQuery(sub));
            }
            if ("OR".equalsIgnoreCase(c.getNestedOperator())) {
                return Query.of(q -> q.bool(b -> b.should(subQueries).minimumShouldMatch("1")));
            }
            return Query.of(q -> q.bool(b -> b.must(subQueries)));
        }

        String col = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();

        if (col == null) {
            throw new IllegalArgumentException("查询条件缺少列名: op=" + op);
        }

        switch (op) {
            case "=":
                return Query.of(q -> q.term(t -> t.field(col).value(toFieldValue(val))));
            case "!=":
                return Query.of(q -> q.bool(b -> b.mustNot(
                        mn -> mn.term(t -> t.field(col).value(toFieldValue(val))))));
            case ">":
                return Query.of(q -> q.range(r -> r.untyped(
                        u -> u.field(col).gt(JsonData.of(val)))));
            case ">=":
                return Query.of(q -> q.range(r -> r.untyped(
                        u -> u.field(col).gte(JsonData.of(val)))));
            case "<":
                return Query.of(q -> q.range(r -> r.untyped(
                        u -> u.field(col).lt(JsonData.of(val)))));
            case "<=":
                return Query.of(q -> q.range(r -> r.untyped(
                        u -> u.field(col).lte(JsonData.of(val)))));
            case "LIKE":
                return Query.of(q -> q.wildcard(w -> w.field(col).wildcard(likeToWildcard(val))));
            case "NOT LIKE":
                return Query.of(q -> q.bool(b -> b.mustNot(
                        mn -> mn.wildcard(w -> w.field(col).wildcard(likeToWildcard(val))))));
            case "IN":
                return buildInQuery(col, (Collection<?>) val);
            case "NOT IN":
                return buildNotInQuery(col, (Collection<?>) val);
            case "IS NULL":
                return Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.exists(e -> e.field(col)))));
            case "IS NOT NULL":
                return Query.of(q -> q.exists(e -> e.field(col)));
            case "BETWEEN": {
                Object[] range = (Object[]) val;
                return Query.of(q -> q.range(r -> r.untyped(
                        u -> u.field(col)
                                .gte(JsonData.of(range[0]))
                                .lte(JsonData.of(range[1])))));
            }
            default:
                throw new UnsupportedOperationException("Elasticsearch 不支持操作符: " + op);
        }
    }

    /**
     * 构建 入 查询（terms）。
     *
     * @param field  字段名
     * @param values 值集合
     * @return ES 查询
     */
    static Query buildInQuery(String field, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Query.of(q -> q.matchNone(m -> m));
        }
        List<FieldValue> fieldValues = new ArrayList<>(values.size());
        for (Object v : values) {
            fieldValues.add(toFieldValue(v));
        }
        return Query.of(q -> q.terms(t -> t.field(field)
                .terms(TermsQueryField.of(tf -> tf.value(fieldValues)))));
    }

    /**
     * 构建 NOT 入 查询。
     *
     * @param field  字段名
     * @param values 值集合
     * @return ES 查询
     */
    static Query buildNotInQuery(String field, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        List<FieldValue> fieldValues = new ArrayList<>(values.size());
        for (Object v : values) {
            fieldValues.add(toFieldValue(v));
        }
        Query termsQuery = Query.of(q -> q.terms(t -> t.field(field)
                .terms(TermsQueryField.of(tf -> tf.value(fieldValues)))));
        return Query.of(q -> q.bool(b -> b.mustNot(termsQuery)));
    }

    // ---------------------------------------------------------------
    // 值转换工具
    // ---------------------------------------------------------------

    /**
     * 将任意值转为 ES 字段值。
     *
     * @param value 值
     * @return FieldValue
     */
    static FieldValue toFieldValue(Object value) {
        if (value == null) {
            return FieldValue.NULL;
        }
        if (value instanceof String s) {
            return FieldValue.of(s);
        }
        if (value instanceof Long l) {
            return FieldValue.of(l);
        }
        if (value instanceof Integer i) {
            return FieldValue.of(i.longValue());
        }
        if (value instanceof Short s) {
            return FieldValue.of(s.longValue());
        }
        if (value instanceof Byte b) {
            return FieldValue.of(b.longValue());
        }
        if (value instanceof Double d) {
            return FieldValue.of(d);
        }
        if (value instanceof Float f) {
            return FieldValue.of(f.doubleValue());
        }
        if (value instanceof Boolean b) {
            return FieldValue.of(b);
        }
        return FieldValue.of(value.toString());
    }

    /**
     * 将 SQL LIKE 模式转为 ES wildcard 模式。
     * <p>{@code %} → {@code *}，{@code _} → {@code ?}；值中出现的 ES 元字符
     * （{@code *}/{@code ?}/{@code \}）先反斜杠转义，防止字面量被当作通配符。</p>
     *
     * @param value LIKE 模式或普通值
     * @return ES 通配符字符串
     */
    static String likeToWildcard(Object value) {
        if (value == null) {
            return "*";
        }
        String pattern = value.toString();
        StringBuilder sb = new StringBuilder(pattern.length() + 2);
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            switch (ch) {
                case '%' -> sb.append('*');
                case '_' -> sb.append('?');
                case '*', '?', '\\' -> sb.append('\\').append(ch);
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // 实体映射
    // ---------------------------------------------------------------

    /**
     * 将 ES 返回的 映射 映射为实体对象。
     *
     * @param source      ES 文档源
     * @param entityClass 目标实体类型
     * @param <T>         实体类型
     * @return 实体实例
     */
    @SuppressWarnings("unchecked")
    private <T> T mapToEntity(Map<String, Object> source, Class<T> entityClass) {
        if (source == null) {
            return null;
        }
        try {
            T instance = ReflectUtils.instantiate(entityClass);
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                String setterName = "set"
                        + Character.toUpperCase(entry.getKey().charAt(0))
                        + entry.getKey().substring(1);
                for (java.lang.reflect.Method method : entityClass.getMethods()) {
                    if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                        Object converted = convertValue(entry.getValue(), method.getParameterTypes()[0]);
                        ReflectUtils.invoke(instance, method.getName(), void.class, converted);
                        break;
                    }
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("ES 结果映射失败: " + entityClass.getName(), e);
        }
    }

    /**
     * 将值转换为目标类型。
     *
     * @param value      原始值
     * @param targetType 目标类型
     * @return 转换后的值
     */
    static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(value, targetType);
        if (converted != null) {
            return converted;
        }
        return value;
    }
}
