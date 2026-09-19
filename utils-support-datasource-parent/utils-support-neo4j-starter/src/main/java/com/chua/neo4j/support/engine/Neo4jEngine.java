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
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neo4j 图数据库引擎实现，通过 螺栓 协议连接 Neo4j 执行 Cypher 查询。
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

    /**
     * 方言（从 META-INF/dialect-env/neo4j.env 加载）。
     */
    private final java.util.Properties dialectProps;

    /**
     * Neo4jengine。
     */
    public Neo4jEngine() {
        this.dialectProps = loadProps("neo4j");
    }

    /**
     * 从类路径加载方言环境配置文件。
     *
     * @param protocol 方言协议名，对应 {@code META-INF/dialect-env/<protocol>.env} 文件
     * @return 加载的属性对象，文件不存在或加载失败时返回空属性对象
     */
    private static java.util.Properties loadProps(String protocol) {
        try {
            java.io.InputStream is = Neo4jEngine.class.getClassLoader()
                    .getResourceAsStream("META-INF/dialect-env/" + protocol + ".env");
            if (is == null) {
                return new java.util.Properties();
            }
            java.util.Properties props = new java.util.Properties();
            props.load(new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8)));
            return props;
        } catch (Exception e) {
            return new java.util.Properties();
        }
    }

    /**
     * supportsNativePagination。
     *
     * @return 是否成功（true 表示成功）
     */
    private boolean supportsNativePagination() {
        String v = dialectProps.getProperty("supports-native-pagination", "true");
        return !"false".equalsIgnoreCase(v.trim());
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 添加数据源
     *
     * @param name 名称
     * @param ds ds
     * @return 添加数据源的结果
     */
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
     * @param uri      螺栓 URI，如 螺栓://主机:7687
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
    /**
     * 设置默认数据源名称
    */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /**
     * 存储
    */
    public <T> Engine store(String name, List<T> data) {
        if (driver == null || CollectionUtils.isEmpty(data)) {
            return this;
        }
        Class<T> entityClass = (Class<T>) data.getFirst().getClass();
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
                            Object value = ReflectUtils.invoke(entity, method.getName(), Object.class);
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
    /**
     * 执行原生 Cypher 语句。
     * <p>位置参数按 {@code p0、p1…} 转换为 Cypher {@code $pN} 命名参数；
     * 返回受影响的节点/关系/属性变更总数。</p>
     *
     * @param ql     Cypher 语句
     * @param params 参数列表
     * @return 受影响行数
     */
    public int execute(String ql, Object... params) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        Map<String, Object> cypherParams = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i++) {
            cypherParams.put("p" + i, params[i]);
        }
        try (Session session = driver.session()) {
            var summary = session.run(ql, cypherParams).consume();
            var counters = summary.counters();
            return counters.nodesCreated() + counters.nodesDeleted()
                    + counters.relationshipsCreated() + counters.relationshipsDeleted()
                    + counters.propertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Cypher 执行失败: " + ql, e);
        }
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
        return null;
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
        if (driver != null) {
            driver.close();
        }
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
             * 解析属性函数引用的列名。
             *
             * @param col 属性函数引用
             * @return 解析后的列名
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
                     * 解析属性函数引用的列名。
                     *
                     * @param col 属性函数引用
                     * @return 解析后的列名
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
                return cypherQuery(entityClass, getConditions());
            }

            @Override
            /**
             * One
            */
            public T one() {
                List<T> r = cypherQuery(entityClass, getConditions());
                if (r.isEmpty()) {
                    return null;
                }
                return r.getFirst();
            }

            @Override
            /**
             * Page
            */
            public Page<T> page(int pn, int ps) {
                int offset = (pn - 1) * ps;
                int limit = ps;
 // 原生分页：限制 非零时驱动 跳过/限制
                if (supportsNativePagination() && limit > 0) {
                    List<T> all = cypherQuery(entityClass, getConditions(), offset, limit);
                    long total = all.size(); // 注意：原生分页时 total 需另发 数量 查询
                    return new Page<>(pn, ps, total, all);
                }
                // 内存兜底
                List<T> all = cypherQuery(entityClass, getConditions());
                int from = Math.min(offset, all.size());
                int to = Math.min(from + ps, all.size());
                if (from >= all.size()) {
                    return new Page<>(pn, ps, all.size(), Collections.emptyList());
                }
                return new Page<>(pn, ps, all.size(), all.subList(from, to));
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
             * 解析属性函数引用的列名。
             *
             * @param col 属性函数引用
             * @return 解析后的列名
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
                     * 解析属性函数引用的列名。
                     *
                     * @param col 属性函数引用
                     * @return 解析后的列名
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
                return cypherUpdate(entityClass, getConditions(), getSetValues());
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
             * 解析属性函数引用的列名。
             *
             * @param col 属性函数引用
             * @return 解析后的列名
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
                     * 解析属性函数引用的列名。
                     *
                     * @param col 属性函数引用
                     * @return 解析后的列名
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
                return cypherDelete(entityClass, getConditions());
            }
        };
    }

    /**
     * 执行 Cypher 查询（无分页）。
     *
     * @param entityClass 实体类
     * @param conditions  条件列表
     * @param <T>         实体类型
     * @return 查询结果列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> cypherQuery(Class<T> entityClass, List<Condition> conditions) {
        return cypherQuery(entityClass, conditions, 0, 0);
    }

    /**
     * cypher查询。
     *
     * @param entityClass 实体Class，不允许为 null
     * @param conditions 方法入参 conditions
     * @param offset 偏移量，不允许为 null
     * @param limit 上限，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private <T> List<T> cypherQuery(Class<T> entityClass, List<Condition> conditions, int offset, int limit) {
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
 // 原生分页：追加 跳过/限制
        if (supportsNativePagination() && (limit > 0 || offset > 0)) {
            if (offset > 0) {
                cypher.append(" SKIP ").append(offset);
            }
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
        }

        try (var session = driver.session()) {
            var result = session.run(cypher.toString(), params);
            return result.list(r -> mapToEntity(r.get("n").asMap(), entityClass));
        } catch (Exception e) {
            log.error("[NEO4J QUERY ERROR] {}", e.getMessage(), e);
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
     * @param entityClass 实体类
     * @param conditions 条件
     * @return cypher删除的结果
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
     * 将结构化 条件 列表构建为 Cypher WHERE 子句。
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
     * 追加单个条件到 字符串构建器。
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
    /**
     * 映射转为实体
     *
     * @param props props
     * @param entityClass 实体类
     * @return 映射转为实体的结果
     */
    private <T> T mapToEntity(Map<String, Object> props, Class<T> entityClass) {
        try {
            T instance = ReflectUtils.instantiate(entityClass);
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
                        ReflectUtils.invoke(instance, method.getName(), void.class, value);
                        break;
                    }
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 结果映射失败: " + entityClass.getName(), e);
        }
    }

    /**
     * 转换数字
     *
     * @param value 值
     * @param targetType 目标类型
     * @return 转换数字的结果
     */
    private Object convertNumber(Object value, Class<?> targetType) {
        if (!(value instanceof Number)) {
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
