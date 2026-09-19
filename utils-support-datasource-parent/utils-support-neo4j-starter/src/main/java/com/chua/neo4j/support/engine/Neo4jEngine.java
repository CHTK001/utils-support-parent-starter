package com.chua.neo4j.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.SqlName;
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

import java.util.ArrayList;
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
        if (src instanceof Driver d) {
            driver = d;
        } else if (src instanceof String uri) {
            if (uri.contains("@")) {
                driver = GraphDatabase.driver(uri);
            } else {
                driver = GraphDatabase.driver(uri, AuthTokens.none());
            }
        } else {
            throw new IllegalArgumentException("Neo4j 数据源仅支持 Driver 或 bolt URI 字符串，实际为: "
                    + (src == null ? "null" : src.getClass().getName()));
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
    @SuppressWarnings("unchecked")
    /**
     * 存储
    */
    public <T> Engine store(String name, List<T> data) {
        if (CollectionUtils.isEmpty(data)) {
            return this;
        }
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        Class<T> entityClass = (Class<T>) data.getFirst().getClass();
        String label = safeLabel(entityClass.getSimpleName());
        List<Map<String, Object>> rows = new ArrayList<>(data.size());
        for (T entity : data) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (var method : entityClass.getMethods()) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0
                        && !method.getName().equals("getClass")) {
                    String fieldName = method.getName().substring(3);
                    fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
                    try {
                        Object value = method.invoke(entity);
                        if (value != null) {
                            props.put(safeProperty(fieldName), value);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Neo4j 存储读取实体属性失败: "
                                + entityClass.getName() + "#" + method.getName(), e);
                    }
                }
            }
            rows.add(props);
        }
        String cypher = "UNWIND $rows AS row CREATE (n:" + label + ") SET n = row";
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of("rows", rows)).consume();
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 存储失败: label=" + label + " rows=" + rows.size(), e);
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
     * 返回受影响的节点/关系变更数（不含属性级计数）。</p>
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
            var counters = session.run(ql, cypherParams).consume().counters();
            return counters.nodesCreated() + counters.nodesDeleted()
                    + counters.relationshipsCreated() + counters.relationshipsDeleted();
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
        return Dialect.getExtension("neo4j");
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
                return cypherQuery(entityClass, getConditions(), getOrderBys(),
                        getOffset(), getLimit());
            }

            @Override
            /**
             * One
            */
            public T one() {
                List<T> r = cypherQuery(entityClass, getConditions(), getOrderBys(),
                        getOffset(), 1);
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
                int offset = Math.max(0, (pn - 1) * ps);
                if (supportsNativePagination()) {
                    List<T> records = cypherQuery(entityClass, getConditions(), getOrderBys(),
                            offset, ps);
                    long total = cypherCount(entityClass, getConditions());
                    return new Page<>(pn, ps, total, records);
                }
                // 内存兜底
                List<T> all = cypherQuery(entityClass, getConditions(), getOrderBys(), 0, 0);
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
     * cypher查询。
     *
     * @param entityClass 实体Class，不允许为 null
     * @param conditions 方法入参 conditions
     * @param orderBys 排序列表（"col ASC"/"col DESC"），可为 null
     * @param offset 偏移量，0 表示不限
     * @param limit 上限，0 表示不限
     * @return 结果列表
     */
    private <T> List<T> cypherQuery(Class<T> entityClass, List<Condition> conditions,
                                    List<String> orderBys, int offset, int limit) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        String label = safeLabel(entityClass.getSimpleName());
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");
        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(" RETURN n");
        String orderByClause = buildCypherOrderBy(orderBys);
        if (!orderByClause.isEmpty()) {
            cypher.append(" ORDER BY ").append(orderByClause);
        }
        // 原生分页：追加 SKIP/LIMIT
        if (supportsNativePagination()) {
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
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 查询失败: " + cypher, e);
        }
    }

    /**
     * 统计满足条件的节点数（page 的 total 查询）。
     *
     * @param entityClass 实体Class
     * @param conditions  条件列表
     * @return 节点数
     */
    private long cypherCount(Class<?> entityClass, List<Condition> conditions) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        String label = safeLabel(entityClass.getSimpleName());
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");
        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(" RETURN count(n) AS total");
        try (var session = driver.session()) {
            return session.run(cypher.toString(), params).single().get("total").asLong();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 计数失败: " + cypher, e);
        }
    }

    /**
     * 将包装器排序列表转为 Cypher ORDER BY 子句。
     *
     * @param orderBys "col ASC|DESC" 列表，可为 null
     * @return ORDER BY 表达式（不含关键字），无排序时为空串
     */
    private String buildCypherOrderBy(List<String> orderBys) {
        if (CollectionUtils.isEmpty(orderBys)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : orderBys) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String[] parts = item.trim().split("\\s+");
            String col = safeProperty(parts[0]);
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("n.").append(col).append(desc ? " DESC" : " ASC");
        }
        return sb.toString();
    }

    /**
     * 执行 Cypher 更新。
     */
    private <T> int cypherUpdate(
            Class<T> entityClass,
            List<Condition> conditions,
            Map<String, Object> setValues) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        if (CollectionUtils.isEmpty(setValues)) {
            return 0;
        }
        String label = safeLabel(entityClass.getSimpleName());
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");

        StringBuilder setSb = new StringBuilder(" SET ");
        boolean first = true;
        int setIndex = 0;
        for (Map.Entry<String, Object> entry : setValues.entrySet()) {
            if (!first) {
                setSb.append(", ");
            }
            first = false;
            String paramKey = "set" + setIndex++;
            setSb.append("n.").append(safeProperty(entry.getKey())).append(" = $").append(paramKey);
            params.put(paramKey, entry.getValue());
        }

        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(setSb).append(" RETURN count(n) AS updated");

        try (var session = driver.session()) {
            return session.run(cypher.toString(), params).single().get("updated").asInt();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 更新失败: " + cypher, e);
        }
    }

    /**
     * 执行 Cypher 删除。
     * @param entityClass 实体类
     * @param conditions 条件
     * @return cypher删除的结果
     */
    private <T> int cypherDelete(Class<T> entityClass, List<Condition> conditions) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j 驱动未初始化，请先调用 connect 或注册数据源");
        }
        String label = safeLabel(entityClass.getSimpleName());
        Map<String, Object> params = new LinkedHashMap<>();
        String whereClause = buildCypherWhere(conditions, params, "n");

        StringBuilder cypher = new StringBuilder("MATCH (n:").append(label).append(")");
        if (!whereClause.isEmpty()) {
            cypher.append(" WHERE ").append(whereClause);
        }
        cypher.append(" DETACH DELETE n RETURN count(n) AS deleted");

        try (var session = driver.session()) {
            return session.run(cypher.toString(), params).single().get("deleted").asInt();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Neo4j 删除失败: " + cypher, e);
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

        if (col == null) {
            throw new IllegalArgumentException("查询条件缺少列名: op=" + op);
        }
        String property = safeProperty(col);
        String paramKey = "p" + params.size();

        switch (op) {
            case "=":
                sb.append(alias).append(".").append(property).append(" = $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "!=":
                sb.append(alias).append(".").append(property).append(" <> $").append(paramKey);
                params.put(paramKey, val);
                break;
            case ">":
                sb.append(alias).append(".").append(property).append(" > $").append(paramKey);
                params.put(paramKey, val);
                break;
            case ">=":
                sb.append(alias).append(".").append(property).append(" >= $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "<":
                sb.append(alias).append(".").append(property).append(" < $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "<=":
                sb.append(alias).append(".").append(property).append(" <= $").append(paramKey);
                params.put(paramKey, val);
                break;
            case "LIKE":
                sb.append(alias).append(".").append(property).append(" =~ $").append(paramKey);
                params.put(paramKey, likeToRegex(val == null ? null : val.toString()));
                break;
            case "NOT LIKE":
                sb.append("NOT ").append(alias).append(".").append(property).append(" =~ $").append(paramKey);
                params.put(paramKey, likeToRegex(val == null ? null : val.toString()));
                break;
            case "IS NULL":
                sb.append(alias).append(".").append(property).append(" IS NULL");
                break;
            case "IS NOT NULL":
                sb.append(alias).append(".").append(property).append(" IS NOT NULL");
                break;
            case "IN":
                sb.append(alias).append(".").append(property).append(" IN $").append(paramKey);
                params.put(paramKey, val instanceof Collection ? val : List.of(val));
                break;
            case "NOT IN":
                sb.append("NOT ").append(alias).append(".").append(property).append(" IN $").append(paramKey);
                params.put(paramKey, val instanceof Collection ? val : List.of(val));
                break;
            case "BETWEEN":
                Object[] range = (Object[]) val;
                String p1 = "p" + params.size();
                String p2 = "p" + (params.size() + 1);
                sb.append(alias).append(".").append(property).append(" >= $").append(p1)
                        .append(" AND ").append(alias).append(".").append(property).append(" <= $").append(p2);
                params.put(p1, range[0]);
                params.put(p2, range[1]);
                break;
            default:
                throw new UnsupportedOperationException("Neo4j 不支持操作符: " + op);
        }
    }

    /**
     * 将 SQL LIKE 模式翻译为 Cypher 正则（锚定全串，% → .*，_ → .，其余字面量转义）。
     *
     * @param pattern SQL LIKE 模式，可为 null
     * @return 正则字符串；null 输入返回不可能匹配的正则，保证行为可预期
     */
    static String likeToRegex(String pattern) {
        if (pattern == null) {
            return "(?!x)x";
        }
        StringBuilder sb = new StringBuilder("(?s)^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            switch (ch) {
                case '%':
                    sb.append(".*");
                    break;
                case '_':
                    sb.append('.');
                    break;
                default:
                    if ("\\^$.|?*+()[]{}".indexOf(ch) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(ch);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    /**
     * 校验并返回可安全内联到 Cypher 的节点标签（反引号包裹，内部反引号加倍）。
     *
     * @param label 标签
     * @return 转义后的标签
     */
    static String safeLabel(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("节点标签不能为空");
        }
        return "`" + label.replace("`", "``") + "`";
    }

    /**
     * 校验属性/列名为合法 Cypher 标识符后返回。
     *
     * @param name 属性名
     * @return 校验后的属性名
     */
    static String safeProperty(String name) {
        if (!SqlName.isSimple(name)) {
            throw new IllegalArgumentException("非法属性名: " + name);
        }
        return name;
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
