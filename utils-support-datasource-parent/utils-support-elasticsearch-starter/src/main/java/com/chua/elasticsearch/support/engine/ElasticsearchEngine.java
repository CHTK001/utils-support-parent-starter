package com.chua.elasticsearch.support.engine;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import com.chua.elasticsearch.support.meta.EsMetaData;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import com.chua.common.support.utils.CollectionUtils;
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

    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        Object src = ds.getSource();
        if (src instanceof String url) {
            String[] parts = url.split("://");
            String hostPort = parts[parts.length - 1];
            String[] hostAndPort = hostPort.split(":");
            String host = hostAndPort[0];
            int port = 9200;
            if (hostAndPort.length > 1) {
                port = Integer.parseInt(hostAndPort[1]);
            }
            String scheme = "http";
            if (parts.length > 1) {
                scheme = parts[0];
            }
            RestClient restClient = RestClient.builder(new HttpHost(host, port, scheme)).build();
            RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            client = new ElasticsearchClient(transport);
        }
        dataSources.put(name, (EngineDataSource<Object>) ds);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    public MetaData meta() {
        return new EsMetaData(this);
    }

    public ElasticsearchClient getClient() {
        return client;
    }

    @Override
    public <T> Engine store(String name, List<T> data) {
        log.info("[elasticsearch-datasource] 引擎暂不支持 store 操作: name={}, size={}", name, data == null ? 0 : data.size());
        return this;
    }

    @Override
    public SqlExecutor getExecutor(String n) {
        return null;
    }

    @Override
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String n) {
        return (EngineDataSource<T>) dataSources.get(n);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    public Dialect getDialect(String n) {
        return null;
    }

    @Override
    public void close() {
        dataSources.clear();
    }

    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new LambdaQueryWrapper<T>(entityClass) {

            @Override
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            protected LambdaQueryWrapper<T> newInstance() {
                return new LambdaQueryWrapper<T>(entityClass) {

                    @Override
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            public List<T> list() {
                return search(entityClass, getConditions());
            }

            @Override
            public T one() {
                List<T> results = search(entityClass, getConditions());
                if (results.isEmpty()) {
                    return null;
                }
                return results.get(0);
            }

            @Override
            public Page<T> page(int pn, int ps) {
                List<T> all = search(entityClass, getConditions());
                int from = (pn - 1) * ps;
                int to = Math.min(from + ps, all.size());
                if (from >= all.size()) {
                    return new Page<>(pn, ps, all.size(), Collections.emptyList());
                }
                return new Page<>(pn, ps, all.size(), all.subList(from, to));
            }
        };
    }

    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {

            @Override
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {

                    @Override
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            public int update() {
                return 0;
            }
        };
    }

    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {

            @Override
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {

                    @Override
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            public int remove() {
                return 0;
            }
        };
    }

    // ---------------------------------------------------------------
    // 查询执行 — 条件翻译为 ES Query DSL
    // ---------------------------------------------------------------

    /**
     * 执行查询，将条件列表翻译为 ES Query 并搜索。
     *
     * @param entityClass 实体类类型
     * @param conditions  条件列表（来自 LambdaQueryWrapper）
     * @param <T>         实体类型
     * @return 查询结果列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> search(Class<T> entityClass, List<Condition> conditions) {
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            String indexName = entityClass.getSimpleName().toLowerCase();
            Query query;
            if (CollectionUtils.isEmpty(conditions)) {
                query = Query.of(q -> q.matchAll(m -> m));
            } else {
                query = buildQuery(conditions);
            }
            var response = client.search(
                    s -> s.index(indexName).query(query),
                    Map.class);
            return response.hits().hits().stream()
                    .map(h -> mapToEntity((Map<String, Object>) h.source(), entityClass))
                    .toList();
        } catch (Exception e) {
            log.warn("[elasticsearch-datasource] 查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------------------
    // Condition → ES Query 翻译
    // ---------------------------------------------------------------

    /**
     * 将条件列表（AND 连接）翻译为 ES Query。
     *
     * @param conditions 条件列表
     * @return ES Query
     */
    static Query buildQuery(List<Condition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        if (conditions.size() == 1) {
            return buildConditionQuery(conditions.get(0));
        }
        List<Query> mustQueries = new ArrayList<>(conditions.size());
        for (Condition c : conditions) {
            mustQueries.add(buildConditionQuery(c));
        }
        return Query.of(q -> q.bool(b -> b.must(mustQueries)));
    }

    /**
     * 翻译单个 Condition 为 ES Query。
     *
     * @param c 条件
     * @return ES Query
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
                return Query.of(q -> q.bool(b -> b.should(subQueries)));
            }
            return Query.of(q -> q.bool(b -> b.must(subQueries)));
        }

        String col = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();

        if (col == null) {
            return Query.of(q -> q.matchAll(m -> m));
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
                log.warn("[elasticsearch-datasource] 不支持的操作符: {}", op);
                return Query.of(q -> q.matchAll(m -> m));
        }
    }

    /**
     * 构建 IN 查询（terms）。
     *
     * @param field  字段名
     * @param values 值集合
     * @return ES Query
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
     * 构建 NOT IN 查询。
     *
     * @param field  字段名
     * @param values 值集合
     * @return ES Query
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
     * 将任意值转为 ES FieldValue。
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
     * 将 SQL LIKE 模式（%...%）转为 ES 通配符模式（*...*）。
     *
     * @param value LIKE 模式或普通值
     * @return ES 通配符字符串
     */
    static String likeToWildcard(Object value) {
        if (value == null) {
            return "*";
        }
        String pattern = value.toString();
        if (pattern.contains("%")) {
            return pattern.replace("%", "*");
        }
        return "*" + pattern + "*";
    }

    // ---------------------------------------------------------------
    // 实体映射
    // ---------------------------------------------------------------

    /**
     * 将 ES 返回的 Map 映射为实体对象。
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
            T instance = entityClass.getDeclaredConstructor().newInstance();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                String setterName = "set"
                        + Character.toUpperCase(entry.getKey().charAt(0))
                        + entry.getKey().substring(1);
                for (java.lang.reflect.Method method : entityClass.getMethods()) {
                    if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                        Object converted = convertValue(entry.getValue(), method.getParameterTypes()[0]);
                        method.invoke(instance, converted);
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
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof Number num) {
            if (targetType == Integer.class || targetType == int.class) {
                return num.intValue();
            }
            if (targetType == Long.class || targetType == long.class) {
                return num.longValue();
            }
            if (targetType == Double.class || targetType == double.class) {
                return num.doubleValue();
            }
            if (targetType == Float.class || targetType == float.class) {
                return num.floatValue();
            }
        }
        return value;
    }
}