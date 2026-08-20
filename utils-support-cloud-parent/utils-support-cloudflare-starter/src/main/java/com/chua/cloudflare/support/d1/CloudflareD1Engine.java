package com.chua.cloudflare.support.d1;

import com.chua.cloudflare.support.CloudflareClient;

import java.util.List;
import java.util.Map;

/**
 * D1 SQLite 引擎客户端。
 *
 * <p>封装 Cloudflare D1 SQL 查询能力，提供参数绑定、批量查询、事务等高级 API。
 * 本类是薄壳委托 + 结果映射层：所有执行均通过 {@link CloudflareClient#post}
 * 提交到 {@code /accounts/{account_id}/d1/database/{db_id}/query}。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * CloudflareConfig config = new CloudflareConfig()
 *         .setToken("xxx")
 *         .setAccountId("xxx")
 *         .setDatabaseId("xxx");
 * CloudflareD1Engine engine = new CloudflareD1Engine(new CloudflareClient(config));
 *
 * // 建表
 * engine.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)");
 *
 * // 插入（命名参数）
 * engine.execute(
 *     "INSERT INTO users (name) VALUES (:name)",
 *     Map.of("name", "Alice"));
 *
 * // 查询
 * List<Map<String, Object>> rows = engine.query(
 *     "SELECT * FROM users WHERE id = ?", 1);
 *
 * // 批量
 * engine.batch(List.of(
 *     new D1Statement("INSERT INTO users(name) VALUES(?)", "Bob"),
 *     new D1Statement("INSERT INTO users(name) VALUES(?)", "Carol")
 * ));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CloudflareD1Engine {

    /**
     * Cloudflare API 客户端
     */
    private final CloudflareClient client;

    /**
     * 默认数据库 ID（优先取 {@code client.getConfig().getDatabaseId()}，单库场景免传）
     */
    private final String defaultDatabaseId;

    /**
     * 用 Cloudflare 客户端构造，自动使用配置中的默认 databaseId。
     *
     * @param client Cloudflare 客户端
     */
    public CloudflareD1Engine(CloudflareClient client) {
        this(client, null);
    }

    /**
     * 用 Cloudflare 客户端和显式 databaseId 构造。
     *
     * @param client         Cloudflare 客户端
     * @param databaseId     数据库 ID，传 null 使用 config 中的 databaseId
     */
    public CloudflareD1Engine(CloudflareClient client, String databaseId) {
        if (client == null) {
            throw new IllegalArgumentException("CloudflareClient must not be null");
        }
        this.client = client;
        this.defaultDatabaseId = databaseId;
    }

    /**
     * 执行 SQL（无返回结果，如 INSERT/UPDATE/CREATE）。
     *
     * @param sql   SQL 语句，可含 ? 占位符或 :name 命名参数
     * @param params 参数值，按出现顺序绑定 ?；或 Map 用于 :name
     * @return D1 元数据（含 last_row_id、changes 等）
     */
    public D1Result execute(String sql, Object... params) {
        return executeWithParams(sql, D1SqlParameter.ofPositional(params));
    }

    /**
     * 执行 SQL（命名参数）。
     *
     * @param sql   SQL 语句
     * @param named 命名参数映射
     * @return D1 元数据
     */
    public D1Result execute(String sql, Map<String, Object> named) {
        return executeWithParams(sql, D1SqlParameter.ofNamed(named));
    }

    /**
     * 执行单条 SQL 并返回结果列表（每行一个 Map，列名为键）。
     *
     * @param sql   SQL 语句
     * @param params 参数（同 execute）
     * @param <T>   返回类型
     * @return 结果列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String sql, Object... params) {
        D1Result result = executeWithParams(sql, D1SqlParameter.ofPositional(params));
        return (List<Map<String, Object>>) (List<?>) result.getRows();
    }

    /**
     * 执行单条 SQL 并返回结果列表（命名参数）。
     *
     * @param sql   SQL 语句
     * @param named 命名参数
     * @return 结果列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String sql, Map<String, Object> named) {
        D1Result result = executeWithParams(sql, D1SqlParameter.ofNamed(named));
        return (List<Map<String, Object>>) (List<?>) result.getRows();
    }

    /**
     * 批量执行多条 SQL（事务或独立执行取决于 D1 行为）。
     *
     * @param statements 多条语句
     * @return 最后一条语句的元数据（或聚合）
     */
    public List<D1Result> batch(List<D1Statement> statements) {
        D1BatchRequest request = D1BatchRequest.of(resolveDatabaseId(), statements);
        Object raw = client.post(d1QueryPath(), request);
        return D1Result.parseBatch(raw);
    }

    /**
     * 多语句执行（一条 HTTP 调用执行多条 SQL）。
     *
     * @param sql    SQL 模板，每条一次
     * @param paramsList 与每条 SQL 对应的参数列表
     * @return 每条的结果
     */
    public List<D1Result> multi(String sql, List<Object[]> paramsList) {
        List<D1Statement> statements = new java.util.ArrayList<>();
        for (Object[] params : paramsList) {
            statements.add(D1Statement.of(sql, D1SqlParameter.ofPositional(params)));
        }
        return batch(statements);
    }

    /**
     * 执行 SQL 并返回 D1Result（含元数据 + 行集）。
     */
    private D1Result executeWithParams(String sql, D1SqlParameter params) {
        D1Statement stmt = D1Statement.of(sql, params);
        D1QueryRequest request = D1QueryRequest.single(resolveDatabaseId(), stmt);
        Object raw = client.post(d1QueryPath(), request);
        return D1Result.parse(raw);
    }

    /**
     * 解析实际使用的 databaseId。
     */
    private String resolveDatabaseId() {
        String id = defaultDatabaseId != null
                ? defaultDatabaseId
                : client.getConfig().getDatabaseId();
        if (id == null || id.isEmpty()) {
            throw new IllegalStateException("D1 databaseId is not configured");
        }
        return id;
    }

    /**
     * D1 query API 路径（占位符由 accountId 填充）。
     */
    private String d1QueryPath() {
        return "/accounts/" + client.getConfig().getAccountId() + "/d1/database/"
                + resolveDatabaseId() + "/query";
    }

    /**
     * 获取底层 Cloudflare 客户端。
     *
     * @return 客户端
     */
    public CloudflareClient getClient() {
        return client;
    }
}