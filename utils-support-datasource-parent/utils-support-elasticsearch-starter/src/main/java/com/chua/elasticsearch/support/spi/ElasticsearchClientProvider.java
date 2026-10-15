package com.chua.elasticsearch.support.spi;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 中间件管理面 SPI 实现（默认装载）。
 *
 * <p>与 Kafka / InfluxDB / Redis 的代码路径完全相同（都是 {@link MiddlewareClientProvider}
 * 的一个实现），差别只在装配方式：本 SPI 放在 {@code utils-support-elasticsearch-starter}
 * 内，因此宿主引入该 starter 即生效，无需再新建模块。</p>
 *
 * <p><b>只读</b>：{@code GET /}、{@code _cat/indices}、{@code _cat/nodes}、{@code _count}、
 * {@code _search}、{@code _mapping}、{@code _doc/{id}} 这些读接口。
 * {@code DELETE} / {@code _bulk} / 索引创建删除等写操作**一律不提供** ——
 * 管理面定位是「看清现状」，写操作在白名单校验阶段就被挡掉，
 * 根本不会走到建客户端那一步（见 {@link #query}）。</p>
 *
 * <p><b>为什么用底层 RestClient 而不用 co.elastic.clients 类型化客户端</b>：
 * 管理面读的都是 {@code _cat} / {@code _search} 这类协议稳定的只读端点，
 * 底层客户端直接拼路径更短；类型化客户端会把这批请求包成大量 DTO，
 * 且跟着 ES 版本变动 API，改动成本高。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "elasticsearch", order = 200)
public class ElasticsearchClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** 连接与读超时（毫秒）：管理面是「看一眼」，慢于这个数就当失败处理 */
    private static final int TIMEOUT_MS = 5000;

    /** 单次查询返回的行上限，避免把大索引刷进响应体 */
    private static final int MAX_ROWS = 500;

    /** 文档文本截断长度，避免单条大字段撑爆响应 */
    private static final int MAX_TEXT = 4000;

    /** JSON 解析器（只读共享，线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public String displayName() {
        return "Elasticsearch 搜索引擎";
    }

    @Override
    public int defaultPort() {
        return 9200;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "索引 (Indices)", "字段 (Fields)",
                "输入查询：nodes / indices / count <index> / mapping <index> / search <index> [查询] / get <index> <id>",
                "只读查询支持 nodes（节点列表）、indices（索引概览）、count <index>（文档数）、"
                        + "mapping <index>（字段映射）、search <index> [查询]（检索，查询缺省 match_all）、"
                        + "get <index> <id>（取单条文档）");
    }

    // ==================== 连接测试 ====================

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        try (RestClient client = client(request)) {
            JsonNode root = readJson(client, new Request("GET", "/"));
            String version = root.path("version").path("number").asText("");
            String cluster = root.path("cluster_name").asText("");
            int nodes = root.path("number_of_nodes").asInt(0);
            // 索引数单独查一次：_cat/indices 带 h=index 时返回的是每索引一行
            int indices = 0;
            try {
                JsonNode cat = readJson(client, catIndices("/_cat/indices?format=json&h=index"));
                indices = cat.size();
            } catch (Exception ignore) {
                // 索引清单拿不到不影响连通性结论（可能无索引或权限受限）
            }
            return MiddlewareTestResult.ok(version,
                    "集群=" + cluster + " 节点=" + nodes + " 个，索引=" + indices + " 个");
        } catch (Exception e) {
            return MiddlewareTestResult.fail(rootMessage(e));
        }
    }

    // ==================== 树节点 ====================

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try (RestClient client = client(request)) {
            JsonNode rows = readJson(client, catIndices("/_cat/indices?format=json&h=index,docs.count,store.size,health,status"));
            for (JsonNode r : rows) {
                String index = r.path("index").asText("");
                if (index.isEmpty()) {
                    continue;
                }
                out.add(new MiddlewareTreeNode(index, index,
                        r.path("status").asText("-") + "/" + r.path("health").asText("-")
                                + " " + humanSize(r.path("store.size").asText("")),
                        parseLong(r.path("docs.count").asText(""))));
            }
            out.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        if (parentKey == null || parentKey.isEmpty()) {
            return out;
        }
        /** 字段类型（Fields）列表：取 mapping 里的 properties */
        try (RestClient client = client(request)) {
            JsonNode mapping = readJson(client, new Request("GET", "/" + enc(parentKey) + "/_mapping"));
            JsonNode props = mappingProperties(mapping, parentKey);
            if (!props.isObject()) {
                return out;
            }
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String type = e.getValue().path("type").asText("-");
                String index = e.getValue().path("index").asText("");
                out.add(new MiddlewareTreeNode(e.getKey(), e.getKey(), type + (index.isEmpty() ? "" : " index=" + index), null));
            }
            out.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    // ==================== 只读查询 ====================

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        long t0 = System.currentTimeMillis();
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：nodes / indices / count <index> / "
                    + "mapping <index> / search <index> [查询] / get <index> <id>");
        }
        String expr = expression.trim();
        String lower = expr.toLowerCase();

        // 只读白名单：先判语句形态，命中不了就直接拒绝。
        // 这一步必须发生在建客户端之前 —— 写语句连「连不连得上」都不该试探，
        // 更不能因为构造请求而顺手把客户端建起来。
        boolean allowed = lower.equals("nodes") || lower.equals("indices") || lower.equals("count")
                || lower.startsWith("count ") || lower.equals("mapping") || lower.startsWith("mapping ")
                || lower.equals("search") || lower.startsWith("search ")
                || lower.equals("get") || lower.startsWith("get ");
        if (!allowed) {
            return MiddlewareQueryResult.fail("不支持的查询: " + expr
                    + "。只读查询仅支持 nodes / indices / count <index> / mapping <index> / "
                    + "search <index> [查询] / get <index> <id>");
        }

        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_ROWS));
        try (RestClient client = client(request)) {
            if (lower.equals("nodes")) {
                return MiddlewareQueryResult.of(List.of("node", "name", "roles"),
                        nodeRows(client), false, 0L, System.currentTimeMillis() - t0);
            }
            if (lower.equals("indices")) {
                JsonNode rows = readJson(client, catIndices(
                        "/_cat/indices?format=json&h=index,docs.count,deleted,store.size,health,status"));
                List<Map<String, Object>> out = new ArrayList<>();
                for (JsonNode r : rows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("index", r.path("index").asText(""));
                    row.put("docs.count", parseLong(r.path("docs.count").asText("")));
                    row.put("deleted", parseLong(r.path("deleted").asText("")));
                    row.put("store.size", r.path("store.size").asText("-"));
                    row.put("health", r.path("health").asText("-"));
                    row.put("status", r.path("status").asText("-"));
                    out.add(row);
                }
                return MiddlewareQueryResult.of(
                        List.of("index", "docs.count", "deleted", "store.size", "health", "status"),
                        out, out.size() >= n, out.size(), System.currentTimeMillis() - t0);
            }
            if (lower.startsWith("count")) {
                String index = arg(lower, "count");
                JsonNode r = readJson(client, new Request("GET", "/" + enc(index) + "/_count"));
                return MiddlewareQueryResult.single("count", r.path("count").asLong());
            }
            if (lower.startsWith("mapping")) {
                String index = arg(lower, "mapping");
                JsonNode mapping = readJson(client, new Request("GET", "/" + enc(index) + "/_mapping"));
                return MiddlewareQueryResult.of(List.of("field", "type"),
                        fieldRows(mapping, index), false, 0L, System.currentTimeMillis() - t0);
            }
            if (lower.startsWith("get")) {
                String[] a = args(lower, "get", 2);
                if (a.length < 2) {
                    return MiddlewareQueryResult.fail("用法：get <index> <id>");
                }
                JsonNode doc = readJson(client, new Request("GET", "/" + enc(a[0]) + "/_doc/" + enc(a[1])));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("_index", doc.path("_index").asText(a[0]));
                row.put("_id", doc.path("_id").asText(a[1]));
                row.put("_source", doc.path("_source").toString());
                return MiddlewareQueryResult.of(List.of("_index", "_id", "_source"),
                        List.of(row), false, 1L, System.currentTimeMillis() - t0);
            }
            // search <index> [查询]
            // 按**原文**切分，不用 args() 的小写化结果反查原文：
            // 用户输入的大小写/空格被改掉一次就会让 indexOf 失配甚至抛异常。
            String tail = expr.substring("search".length()).trim();
            int sp = tail.indexOf(' ');
            String index = sp < 0 ? tail : tail.substring(0, sp).trim();
            String query = sp < 0 ? "" : tail.substring(sp + 1).trim();
            if (index.isEmpty()) {
                return MiddlewareQueryResult.fail("用法：search <index> [查询]");
            }
            return searchRows(client, index, query, n, t0);
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
    }

    /** search <index> [查询]：把可选查询原样塞进 match_multi_match 的 query 串 */
    private MiddlewareQueryResult searchRows(RestClient client, String index, String query, int n, long t0)
            throws Exception {
        String body;
        if (query == null || query.isEmpty()) {
            body = "{\"size\":" + n + ",\"query\":{\"match_all\":{}}}";
        } else {
            body = "{\"size\":" + n + ",\"query\":{\"match\":{\"*\":\"" + jsonEscape(query) + "\"}}}";
        }
        Request req = new Request("POST", "/" + enc(index) + "/_search");
        req.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
        JsonNode hits = readJson(client, req).path("hits").path("hits");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", h.path("_id").asText(""));
            row.put("_score", h.path("_score").asDouble(0D));
            row.put("_source", h.path("_source").toString());
            out.add(row);
        }
        // 真实总数另走一次 _count：hits.total 只在 track_total_hits 打开时才是全量
        long total = readJsonTotal(client, index, query);
        return MiddlewareQueryResult.of(List.of("_id", "_score", "_source"),
                out, total > out.size(), total, System.currentTimeMillis() - t0);
    }

    /** 另取一次真实总数：hits.total 走 track_total_hits 时才有值，这里直接用 _count 更稳 */
    private long readJsonTotal(RestClient client, String index, String query) {
        try {
            String body = (query == null || query.isEmpty())
                    ? "{\"query\":{\"match_all\":{}}}"
                    : "{\"query\":{\"match\":{\"*\":\"" + jsonEscape(query) + "\"}}}";
            Request req = new Request("POST", "/" + enc(index) + "/_count");
            req.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
            return readJson(client, req).path("count").asLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<Map<String, Object>> nodeRows(RestClient client) throws Exception {
        JsonNode rows = readJson(client, new Request("GET", "/_cat/nodes?format=json&h=ip,node.role,master,name"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", r.path("name").asText("-"));
            row.put("name", r.path("ip").asText("-"));
            row.put("roles", r.path("node.role").asText("-"));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> fieldRows(JsonNode mapping, String index) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode props = mappingProperties(mapping, index);
        if (!props.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", e.getKey());
            row.put("type", e.getValue().path("type").asText("-"));
            out.add(row);
        }
        return out;
    }

    /**
     * 从 {@code GET /{index}/_mapping} 的响应里取字段定义。
     *
     * <p>真实响应是<b>三层</b>：{@code {索引: {mappings: {properties: {...}}}}}。
     * 少写中间那层 {@code mappings}，{@code path("properties")} 会静默拿到缺失节点、
     * 返回空字段表——不报错，只是「字段永远为空」，正是最难发现的那类错。
     * ES 7 之前的老响应体曾直接是 {@code {索引: {properties: ...}}}，
     * 这里同时兼容两种形状，避免老集群下又变成空的。</p>
     */
    private static JsonNode mappingProperties(JsonNode mapping, String index) {
        JsonNode node = mapping.path(index);
        JsonNode nested = node.path("mappings").path("properties");
        return nested.isObject() ? nested : node.path("properties");
    }

    // ==================== 详情 ====================

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        if (scope == null || scope.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }
        try (RestClient client = client(request)) {
            JsonNode doc = readJson(client, new Request("GET", "/" + enc(scope) + "/_doc/" + enc(key)));
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("index", doc.path("_index").asText(scope));
            attrs.put("id", doc.path("_id").asText(key));
            if (doc.hasNonNull("_version")) {
                attrs.put("version", String.valueOf(doc.path("_version").asLong()));
            }
            String text = doc.path("_source").toString();
            return new MiddlewareDocument(scope + "/" + key, truncate(text), attrs);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 底层工具 ====================

    /** 构造只读客户端；账号为空则不加认证（ES 允许匿名读） */
    private RestClient client(MiddlewareConnectRequest request) {
        RestClientBuilder builder = RestClient.builder(HttpHost.create("http://" + request.address()));
        // 回调要交回 Builder 本身，不是配好的 RequestConfig：RestClient 每次建连都从它 clone
        builder.setRequestConfigCallback(rc -> RequestConfig.custom()
                .setConnectTimeout(TIMEOUT_MS)
                .setSocketTimeout(TIMEOUT_MS));
        if (request.username() != null && !request.username().isEmpty()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                    request.username(), request.password() == null ? "" : request.password()));
            builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(cp));
        }
        return builder.build();
    }

    private static Request catIndices(String path) {
        return new Request("GET", path);
    }

    /** 执行请求并把响应体解析成 JSON；响应体为空时返回空对象而不是抛异常 */
    private static JsonNode readJson(RestClient client, Request request) throws Exception {
        Response response = client.performRequest(request);
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return MAPPER.createObjectNode();
        }
        String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    /** 取「count 」/「mapping 」这类前缀后的第一个参数 */
    private static String arg(String lower, String verb) {
        String[] a = args(lower, verb, 1);
        return a.length >= 1 ? a[0] : "";
    }

    /** 按 verb 切分表达式，最多取 limit 个参数；search 的第二段是查询串，不做空白切分 */
    private static String[] args(String lower, String verb, int limit) {
        String tail = lower.substring(verb.length()).trim();
        if (tail.isEmpty()) {
            return new String[0];
        }
        if (verb.equals("search")) {
            int sp = tail.indexOf(' ');
            if (sp < 0) {
                return new String[]{tail};
            }
            return new String[]{tail.substring(0, sp), tail.substring(sp + 1).trim()};
        }
        return tail.split("\\s+", limit);
    }

    /** 路径片段转义：ES 索引名里常含 * 与 , 不能直接拼路径 */
    private static String enc(String s) {
        return s == null ? "" : s.replace(" ", "%20");
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** _cat 的计数列在无文档时返回空串 */
    private static Long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 1.2gb -> 1.2gb（_cat 已带单位，这里只补空值） */
    private static String humanSize(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…(截断)";
    }

    /** 异常消息取根因，链路上层层包装时最内层才是真因 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}