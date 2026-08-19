package com.chua.neo4j.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import com.chua.common.support.utils.CollectionUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neo4j 图数据库引擎实现，通过 Bolt 协议连接 Neo4j 执行 Cypher 查询。
 * <p>
 * 支持 Lambda 链式查询、条件过滤、分页、更新和删除操作。
 * 条件自动转为参数化 Cypher WHERE 子句，防止 Cypher 注入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("neo4j")
public class Neo4jEngine implements Engine {

    /**
     * 数据源映射表。
     */
    private final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称。
     */
    private String defaultDataSourceName;

    /**
     * Neo4j 驱动实例。
     */
    private Driver driver;

    @Override
    @SuppressWarnings("unchecked")
    /** 添加DataSource */
    public <T> Engine addDataSource(String name, EngineDataSource<T> ds) {
        Object src = ds.getSource();
        if (src instanceof String uri) {
            driver = GraphDatabase.driver(uri, AuthTokens.none());
        }
        dataSources.put(name, (EngineDataSource<Object>) ds);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 连接 Neo4j 数据库。
     *
     * @param uri      Bolt URI，如 bolt://host:7687
     * @param user     用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public Neo4jEngine connect(String uri, String user, String password) {
        Config config = Config.builder()
                .withoutEncryption()
                .withMaxConnectionLifetime(30, java.util.concurrent.TimeUnit.MINUTES)
                .build();
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                var result = tx.run("RETURN 1 AS n");
                log.info("[neo4j-engine] 连接验证成功: server={}", result.single().get("n").asInt());
            }
        }
        log.info("[neo4j-engine] 驱动就绪: uri={}", uri);
        return this;
    }

    @Override
    /** 设置DefaultDataSourceName */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /** Store */
    public <T> Engine store(String name, List<T> data) {
        if (driver == null || CollectionUtils.isEmpty(data)) {
            return this;
        }
        Class<T> entityClass = (Class<T>) data.get(0).getClass();
        String label = entityClass.getSimpleName();
        try (var session = driver.session()) {
            for (T entity : data) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (var method : entityClass.getMethods()) {
                    if (method.getName().startsWith("get") && method.getParameterCount() == 0
                            && !method.getName().equals("getClass")) {
                        String fieldName = method.getName().substring(3);
                        fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
                        try {
                            Object value = method.invoke(entity);
                            props.put(fieldName, value);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
                StringBuilder cypher = new StringBuilder("CREATE (n:").append(label).append(" {");
                boolean first = true;
                for (String key : props.keySet()) {
                    if (!first) {
                        cypher.append(", ");
                    }
                    cypher.append(key).append(": $").append(key);
                    first = false;
                }
                cypher.append("})");
                log.info("[neo4j-engine] 执行 Cypher: cypher={}, params={}", cypher, props);
                session.run(cypher.toString(), props);
                log.info("[neo4j-engine] 实体已写入: entity={}", entityClass.getSimpleName());
            }
        } catch (Exception e) {
            log.error("[neo4j-engine] 存储失败: {}", e.getMessage(), e);
        }
        return this;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor(String n) {
        return null;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource(String n) {
        return (EngineDataSource<T>) dataSources.get(n);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    @Override
    /** 获取Dialect */
    public Dialect getDialect(String n) {
        return null;
    }

    @Override
    /** 关闭 */
    public void close() {
        if (driver != null) {
            driver.close();
        }
        dataSources.clear();
    }

    @Override
    /** 查询 */
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
             * @param to to
             * @param entityClass entityClass
             * @param col col
             * @param col col
             * @param entityClass entityClass
             * @param col col
             * @param col col
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param entityClass entityClass
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param setValues setValues
             * @param params params
             * @param params params
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param e e
             * @param conditions conditions
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param sb sb
             * @param c c
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param props props
             * @param entityClass entityClass
             * @param paramType paramType
             * @param value value
             * @param e e
             * @param e e
             * @param value value
             * @param targetType targetType
             * @param Number Number
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /** NewInstance */
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
                     * @param to to
                     * @param entityClass entityClass
                     * @param col col
                     * @param col col
                     * @param entityClass entityClass
                     * @param col col
                     * @param col col
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param entityClass entityClass
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param setValues setValues
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param conditions conditions
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param sb sb
                     * @param c c
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param props props
                     * @param entityClass entityClass
                     * @param paramType paramType
                     * @param value value
                     * @param e e
                     * @param e e
                     * @param value value
                     * @param targetType targetType
                     * @param Number Number
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /** List */
            public List<T> list() {
                return cypherQuery(entityClass, getConditions());
            }

            @Override
            /** One */
            public T one() {
                List<T> r = cypherQuery(entityClass, getConditions());
                if (r.isEmpty()) {
                    return null;
                }
                return r.get(0);
            }

            @Override
            /** Page */
            public Page<T> page(int pn, int ps) {
                List<T> all = cypherQuery(entityClass, getConditions());
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
    /** 更新 */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
             * @param col col
             * @param col col
             * @param entityClass entityClass
             * @param col col
             * @param col col
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param entityClass entityClass
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param setValues setValues
             * @param params params
             * @param params params
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param e e
             * @param conditions conditions
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param sb sb
             * @param c c
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param props props
             * @param entityClass entityClass
             * @param paramType paramType
             * @param value value
             * @param e e
             * @param e e
             * @param value value
             * @param targetType targetType
             * @param Number Number
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /** NewInstance */
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                     * @param col col
                     * @param entityClass entityClass
                     * @param col col
                     * @param col col
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param entityClass entityClass
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param setValues setValues
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param conditions conditions
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param sb sb
                     * @param c c
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param props props
                     * @param entityClass entityClass
                     * @param paramType paramType
                     * @param value value
                     * @param e e
                     * @param e e
                     * @param value value
                     * @param targetType targetType
                     * @param Number Number
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /** 更新 */
            public int update() {
                return cypherUpdate(entityClass, getConditions(), getSetValues());
            }
        };
    }

    @Override
    /** 删除 */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
             * @param col col
             * @param col col
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param entityClass entityClass
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param setValues setValues
             * @param params params
             * @param params params
             * @param e e
             * @param entityClass entityClass
             * @param conditions conditions
             * @param params params
             * @param params params
             * @param e e
             * @param conditions conditions
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param sb sb
             * @param c c
             * @param params params
             * @param alias alias
             * @param 0 0
             * @param params params
             * @param alias alias
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param val val
             * @param props props
             * @param entityClass entityClass
             * @param paramType paramType
             * @param value value
             * @param e e
             * @param e e
             * @param value value
             * @param targetType targetType
             * @param Number Number
             */
            protected String resolveColumn(
                    com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /** NewInstance */
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {

                    @Override
                    /**
                     * 解析Column
                     * @param col col
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param entityClass entityClass
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param setValues setValues
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param entityClass entityClass
                     * @param conditions conditions
                     * @param params params
                     * @param params params
                     * @param e e
                     * @param conditions conditions
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param sb sb
                     * @param c c
                     * @param params params
                     * @param alias alias
                     * @param 0 0
                     * @param params params
                     * @param alias alias
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param val val
                     * @param props props
                     * @param entityClass entityClass
                     * @param paramType paramType
                     * @param value value
                     * @param e e
                     * @param e e
                     * @param value value
                     * @param targetType targetType
                     * @param Number Number
                     */
                    protected String resolveColumn(
                            com.chua.common.support.lang.datasource.engine.wrapper.SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /** 移除 */
            public int remove() {
                return cypherDelete(entityClass, getConditions());
            }
        };
    }

    /**
     * 执行 Cypher 查询。
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> cypherQuery(Class<T> entityClass, List<Condition> conditions) {
        if (driver == null) {
            log.warn("[neo4j-engine] 驱动未初始化，无法执行查询");
            return Collections.emptyList();
        }
        String label = entityClass.getSimpleName();
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");
        StringBuilder cypher = new StringBuilder("MATCH (n:")
                .append(label)
                .append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(" RETURN n");

        try (var session = driver.session()) {
            var result = session.run(cypher.toString(), params);
            return result.list(r -> mapToEntity(r.get("n").asMap(), entityClass));
        } catch (Exception e) {
            System.err.println("[NEO4J QUERY ERROR] " + e.getMessage());
            e.printStackTrace(System.err);
            log.error("Neo4j 查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 执行 Cypher 更新。
     */
    @SuppressWarnings("unchecked")
    private <T> int cypherUpdate(
            Class<T> entityClass,
            List<Condition> conditions,
            Map<String, Object> setValues) {
        if (driver == null || CollectionUtils.isEmpty(setValues)) {
            return 0;
        }
        String label = entityClass.getSimpleName();
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");

        StringBuilder setSb = new StringBuilder(" SET ");
        boolean first = true;
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            if (!first) {
                setSb.append(", ");
            }
            first = false;
            String paramKey = "set_" + entry.getKey();
            setSb.append("n.").append(entry.getKey()).append(" = $").append(paramKey);
            params.put(paramKey, entry.getValue());
        }

        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(setSb).append(" RETURN count(n) AS updated");

        try (var session = driver.session()) {
            var result = session.run(cypher.toString(), params);
            if (result.hasNext()) {
                return result.next().get("updated").asInt();
            }
            return 0;
        } catch (Exception e) {
            log.error("Neo4j 更新失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 执行 Cypher 删除。
     */
    @SuppressWarnings("unchecked")
    private <T> int cypherDelete(Class<T> entityClass, List<Condition> conditions) {
        if (driver == null) {
            return 0;
        }
        String label = entityClass.getSimpleName();
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");

        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(" DETACH DELETE n RETURN count(n) AS deleted");

        try (var session = driver.session()) {
            var result = session.run(cypher.toString(), params);
            if (result.hasNext()) {
                return result.next().get("deleted").asInt();
            }
            return 0;
        } catch (Exception e) {
            log.error("Neo4j 删除失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 将结构化 Condition 列表构建为 Cypher WHERE 子句。
     *
     * @param conditions 条件列表
     * @param params     参数映射（输出）
     * @param alias      节点别名
     * @return Cypher WHERE 字符串
     */
    private String buildCypherWhere(
            List<Condition> conditions,
            Map<String, Object> params,
            String alias) {
        if (CollectionUtils.isEmpty(conditions)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            appendCondition(sb, conditions.get(i), params, alias);
        }
        return sb.toString();
    }

    /**
     * 追加单个条件到 StringBuilder。
     */
    private void appendCondition(
            StringBuilder sb,
            Condition c,
            Map<String, Object> params,
            String alias) {
        if (c.isNested()) {
            sb.append("(");
            List<Condition> nested = c.getNested();
            for (int i = 0; i < nested.size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                appendCondition(sb, nested.get(i), params, alias);
            }
            sb.append(")");
            return;
        }
        String col = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();
        String paramKey = "p" + params.size();

        switch (op) {
            case "=":
                sb.append(alias).append(".").append(col).append(" = $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "!=":
                sb.append(alias).append(".").append(col).append(" <> $").append(paramKey);
                params.put(paramKey, val);
                break;
            case ">":
                sb.append(alias).append(".").append(col).append(" > $").append(paramKey);
                params.put(paramKey, val);
                break;
            case ">=":
                sb.append(alias).append(".").append(col).append(" >= $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "<":
                sb.append(alias).append(".").append(col).append(" < $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "<=":
                sb.append(alias).append(".").append(col).append(" <= $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "LIKE":
                sb.append(alias).append(".").append(col).append(" CONTAINS $").append(paramKey);
                params.put(paramKey, String.valueOf(val).replace("%", ""));
                break;
            case "IS NULL":
                sb.append(alias).append(".").append(col).append(" IS NULL");
                break;
            case "IS NOT NULL":
                sb.append(alias).append(".").append(col).append(" IS NOT NULL");
                break;
            case "IN":
                sb.append(alias).append(".").append(col).append(" IN $").append(paramKey);
                params.put(paramKey, val instanceof Collection ? val : List.of(val));
                break;
            case "NOT IN":
                sb.append("NOT ").append(alias).append(".").append(col).append(" IN $").append(paramKey);
                params.put(paramKey, val instanceof Collection ? val : List.of(val));
                break;
            case "BETWEEN":
                Object[] range = (Object[]) val;
                String p1 = "p" + params.size();
                String p2 = "p" + (params.size() + 1);
                sb.append(alias).append(".").append(col).append(" >= $").append(p1)
                        .append(" AND ").append(alias).append(".").append(col).append(" <= $").append(p2);
                params.put(p1, range[0]);
                params.put(p2, range[1]);
                break;
            default:
                sb.append(alias).append(".").append(col).append(" = $").append(paramKey);
                params.put(paramKey, val);
                break;
        }
    }

    @SuppressWarnings("unchecked")
    /** MapToEntity */
    private <T> T mapToEntity(Map<String, Object> props, Class<T> entityClass) {
        try {
            T instance = entityClass.getDeclaredConstructor().newInstance();
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                String setter = "set"
                        + Character.toUpperCase(entry.getKey().charAt(0))
                        + entry.getKey().substring(1);
                for (var method : entityClass.getMethods()) {
                    if (method.getName().equals(setter) && method.getParameterCount() == 1) {
                        Class<?> paramType = method.getParameterTypes()[0];
                        Object value = entry.getValue();
                        if (value != null && !paramType.isInstance(value)) {
                            value = convertNumber(value, paramType);
                        }
                        if (value == null && paramType.isPrimitive()) {
                            continue;
                        }
                        method.invoke(instance, value);
                        break;
                    }
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 结果映射失败: " + entityClass.getName(), e);
        }
    }

    /** 转换Number */
    private Object convertNumber(Object value, Class<?> targetType) {
        if (!(value instanceof Number)) {
            return value;
        }
        Number n = (Number) value;
        if (targetType == int.class || targetType == Integer.class) {
            return n.intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return n.longValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return n.shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return n.byteValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return n.floatValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return n.doubleValue();
        }
        return value;
    }
}