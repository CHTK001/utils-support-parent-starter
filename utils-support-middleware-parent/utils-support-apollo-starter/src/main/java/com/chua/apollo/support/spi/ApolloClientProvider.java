package com.chua.apollo.support.spi;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.middleware.MiddlewareCapability;
import com.chua.datasource.support.middleware.MiddlewareClientProvider;
import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareDocument;
import com.chua.datasource.support.middleware.MiddlewareQueryResult;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import com.chua.datasource.support.middleware.MiddlewareTreeNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Apollo 中间件管理面 SPI 实现（默认装载）。
 *
 * <p><b>为什么走 HTTP 而不是引 apollo-client</b>：管理面只需要读 应用/命名空间/配置项 列表，
 * 这些数据（{"appId","name"} / 命名空间列表 / 配置内容）都有相对稳定的 REST API（
 * {@code /v1/apps/...}、{@code /configs/{appId}/{cluster}/{namespace}、
 * {@code /services/config}）。引官方客户端会把一整套配置中心启动逻辑（本地缓存/长轮询/更新重试）
 * 拖进每个使用方。</p>
 *
 * <p><b>只读</b>：应用列表、命名空间列表、配置项列表与值。发布/删除/删除配置项一律不提供。</p>
 *
 * <p><b>管理 API 参考</b>（通过 apollo-client 2.5.0 字节码确认）：</p>
 * <ul>
 *   <li>{@code GET {addr}/v1/apps?pageNo={n}&pageItems={m}} — 应用列表，
 *     返回 {@code {"code":0,"data":[{"appId","name",...},...]}}</li>
 *   <li>{@code GET {addr}/v1/apps/{appId}/clusters/{cluster}/namespaces} — 某应用某集群下的
 *     命名空间列表（{@code cluster} 取值如 {@code default}、{@code master}，返回
 *     {@code data} 列表含 {@code namespaceName} 字段）</li>
 *   <li>{@code GET {addr}/configs/{appId}/{cluster}/{namespace}
 *     ?releaseKey={key}&dataCenter={dc}&ip={ip}&label={label} — 命名空间完整配置，返回
 *     {@code ApolloConfig} JSON（{"appId","cluster","namespaceName","releaseKey",
 *     "configurations":{key:value,...},...}）</li>
 *   <li>{@code GET {addr}/services/config?appId={appId}&ip={ip}} — 元数据发现（连通测试用），
 *     返回 {@code List<ServiceDTO>}（{"appName","homepageUrl","instanceId"}）</li>
 * </ul>
 *
 * <p><b> 节点展开</b>：拓扑按 Apollo 的 应用/集群/命名空间 三模型展开：
 * 顶层节点 = 应用（key = appId），子节点 = 命名空间（key = {@code appId|cluster|namespaceName}），
 * 查询与详情 = 该命名空间的配置项（key/value）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "apollo", order = 200)
public class ApolloClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** HTTP 超时 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /** 每次应用列表翻页的条数（管理面只读） */
    private static final int PAGE_ITEMS = 100;

    /** 标准集群：命名空间按集群隔离，标准集群取这两个即可覆盖绝大多数部署 */
    private static final List<String> CLUSTER_NAMES = List.of("default", "master");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String name() {
        return "apollo";
    }

    @Override
    public String displayName() {
        return "Apollo 配置中心";
    }

    @Override
    public int defaultPort() {
        return 8080;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "应用 (Apps)", "命名空间 (Namespaces)",
                "查询：apps / namespaces / configs <appId>|<cluster>|<namespace>",
                "只读查询支持 apps（应用列表）、namespaces（命名空间列表）、"
                        + "configs <appId>|<cluster>|<namespace>（配置项 key/value 表）");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        try {
            // 管理面入口：apollo Admin Service 应用列表接口（只读，不写），返回 data 列表说明可达
            HttpResponse<String> r = send(request, "/v1/apps?pageNo=1&pageItems=1");
            if (isData(r.body())) {
                return MiddlewareTestResult.ok("", "Apollo 管理面可达（Admin Service）");
            }
            // 兼容：apollo Config Service 元数据发现端点（无 appId 也能 2xx）
            r = send(request, "/services/config?appId=&ip=127.0.0.1");
            if (isData(r.body())) {
                return MiddlewareTestResult.ok("", "Apollo 配置服务可达（Config Service）");
            }
            // 能走到这里说明任一端点都未返回 2xx + 数据
            throw new IllegalStateException("不返回应用数据的 HTTP 响应: " + r.statusCode());
        } catch (Exception e) {
            // 连接失败是业务结果，不是系统错误：不允许抛异常
            return MiddlewareTestResult.fail(rootMessage(e));
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try {
            // 应用列表即顶层节点，key 取 appId
            List<JsonObject> apps = items(request, "/v1/apps?pageNo=1&pageItems=" + PAGE_ITEMS);
            for (JsonObject app : apps) {
                String appId = getField(app, "appId", "");
                if (appId.isBlank()) {
                    continue;
                }
                out.add(new MiddlewareTreeNode(appId, getField(app, "name", appId),
                        "appId=" + appId, null));
            }
        } catch (Exception e) {
            // 读不到就返回空列表，前端据此显示空态而不是报错
            return List.of();
        }
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        if (parentKey == null || parentKey.isBlank()) {
            return List.of();
        }
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try {
            // parentKey = appId，子节点 = 该应用下的所有命名空间
            for (String cluster : CLUSTER_NAMES) {
                String url = "/v1/apps/" + url(parentKey) + "/clusters/" + url(cluster) + "/namespaces";
                for (JsonObject ns : items(request, url)) {
                    String namespaceName = getField(ns, "namespaceName", getField(ns, "namespace", ""));
                    if (namespaceName.isBlank()) {
                        continue;
                    }
                    out.add(new MiddlewareTreeNode(parentKey + "|" + cluster + "|" + namespaceName,
                            namespaceName, "cluster=" + cluster + " appId=" + getField(ns, "appId", ""), null));
                }
            }
        } catch (Exception e) {
            // 读不到就返回空列表，前端据此显示空态而不是报错
            return List.of();
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：apps / namespaces / configs <appId>|<cluster>|<namespace>");
        }
        String cmd = expression.trim();
        String lower = cmd.toLowerCase();
        long t0 = System.currentTimeMillis();
        try {
            if ("apps".equals(lower)) {
                return appsTable(request, t0, limit);
            }
            if ("namespaces".equals(lower)) {
                return namespacesTable(request, t0, limit);
            }
            if (lower.startsWith("configs ")) {
                return configs(request, t0, cmd.substring("configs ".length()).trim(), limit);
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 apps / namespaces / configs <appId>|<cluster>|<namespace>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(rootMessage(e));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        try {
            // scope = "config" / "namespace"，key = appId|cluster|namespace
            String target = (scope == null || scope.isBlank()) ? "config" : scope.trim().toLowerCase();
            if (!"config".equals(target) && !"namespace".equals(target)) {
                return null;
            }
            if (key == null || key.isBlank()) {
                return null;
            }
            String[] parts = key.split("\\|", -1);
            if (parts.length < 3) {
                return null;
            }
            String appId = parts[0];
            String cluster = parts[1];
            String namespace = parts[2];
            if ("config".equals(target)) {
                // 配置服务路径：出发即读取命名空间完整配置（verified: /configs/{appId}/{cluster}/{namespace}）
                return configDocument(request, appId, cluster, namespace);
            }
            // 管理面路径：读取命名空间本身的信息（namespace 详情）
            return namespaceDocument(request, appId, cluster, namespace);
        } catch (Exception ignore) {
            // 详情读不到不影响主流程
        }
        return null;
    }

    // ==================== 内部实现 ====================

    private static final String GET = "GET";

    /** 该响应体内是否含可以读的列表数据（{"code":0,"data":[...]} 或空数组） */
    private static boolean isData(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        JsonObject root = asObject(body);
        if (root == null) {
            return false;
        }
        return root.getJsonArray("data") != null;
    }

    private static String baseUrl(MiddlewareConnectRequest request) {
        return "http://" + request.address();
    }

    /** 一次只读 GET */
    private HttpResponse<String> send(MiddlewareConnectRequest request, String path) throws Exception {
        String full = baseUrl(request) + path;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full)).timeout(HTTP_TIMEOUT);
        b.header("Accept", "application/json");
        b.method(GET, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r;
    }

    /** 只返回 JSON 数组响应的 data 列表（{"code":0,"data":[...]}） */
    private List<JsonObject> items(MiddlewareConnectRequest request, String path) throws Exception {
        String body = send(request, path).body();
        JsonObject root = asObject(body);
        List<JsonObject> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonArray data = root.getJsonArray("data");
        if (data == null) {
            return out;
        }
        for (Object o : data) {
            if (o instanceof JsonObject obj) {
                out.add(obj);
            }
        }
        return out;
    }

    /** JSON 回答体转 JsonObject（解析失败返回 null，调用方负责返回空结果） */
    private static JsonObject asObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = Json.parse(body);
            return node.isObject() ? node.toJsonObject() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 字段安全取值 */
    private static String getField(JsonObject obj, String key, String defaultVal) {
        Object v = Optional.ofNullable(obj).map(o -> o.get(key)).orElse(null);
        if (v == null) {
            return defaultVal;
        }
        String s = v.toString();
        return s.isBlank() ? defaultVal : s;
    }

    /** 只描述错误根因（不暴露包装异常栈） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }

    /** URL 片段 urlencode */
    private static String url(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * apps 列表查询（{"code":0,"data":[...]} 中出列）
     * @param request 参数
     * @param t0 时间
     * @param limit 条数
     * @return 返回值
     * @throws Exception 异常
     */
    private MiddlewareQueryResult appsTable(MiddlewareConnectRequest request, long t0, int limit) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MiddlewareTreeNode n : listTopNodes(request)) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appId", n.key());
            row.put("name", n.label());
            rows.add(row);
        }
        return MiddlewareQueryResult.of(List.of("appId", "name"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /**
     * namespaces 列表查询（按每个应用展开子节点）
     * @param request 参数
     * @param t0 时间
     * @param limit 条数
     * @return 返回值
     * @throws Exception 异常
     */
    private MiddlewareQueryResult namespacesTable(MiddlewareConnectRequest request, long t0, int limit) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MiddlewareTreeNode app : listTopNodes(request)) {
            if (rows.size() >= limit) {
                break;
            }
            for (MiddlewareTreeNode ns : listChildren(request, app.key())) {
                if (rows.size() >= limit) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appId", app.key());
                row.put("key", ns.key());
                row.put("cluster", keyToCluster(ns));
                row.put("namespace", ns.label());
                row.put("comment", ns.comment());
                rows.add(row);
            }
        }
        return MiddlewareQueryResult.of(List.of("appId", "key", "cluster", "namespace", "comment"), rows,
                false, rows.size(), System.currentTimeMillis() - t0);
    }

    /** 把节点 key {appId}|{cluster}|{namespace} 拆出集群 */
    private static String keyToCluster(MiddlewareTreeNode node) {
        if (node == null || node.key().isBlank()) {
            return "";
        }
        String[] parts = node.key().split("\\|", -1);
        return parts.length > 1 ? parts[1] : "";
    }

    /**
     * 命名空间配置内容查询（只读，key/value 表）
     * @param request 参数
     * @param t0 时间
     * @param namespaceKey 路径 appId|cluster|namespace
     * @param limit 条数
     * @return 返回值
     * @throws Exception 异常
     */
    private MiddlewareQueryResult configs(MiddlewareConnectRequest request, long t0, String namespaceKey, int limit)
            throws Exception {
        String[] parts = namespaceKey.split("\\|", -1);
        if (parts.length < 3) {
            return MiddlewareQueryResult.fail("configs 需要 appId|cluster|namespace，如 configs 1234|default|application");
        }
        String appId = parts[0];
        String cluster = parts[1];
        String namespace = parts[2];
        JsonObject config = configJson(request, appId, cluster, namespace);
        if (config == null) {
            return MiddlewareQueryResult.fail("命名空间配置不存在: " + namespaceKey);
        }
        JsonObject configurations = config.getJsonObject("configurations");
        if (configurations == null) {
            return MiddlewareQueryResult.fail("命名空间没有可配置项: " + namespaceKey);
        }
        Map<String, Object> items = configurations.toMap();
        long total = items.size();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : items.entrySet()) {
            if (rows.size() >= limit) {
                break;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", e.getKey());
            r.put("value", e.getValue() == null ? "" : e.getValue().toString());
            rows.add(r);
        }
        return MiddlewareQueryResult.of(List.of("key", "value"), rows, false, total,
                System.currentTimeMillis() - t0);
    }

    /**
     * 通过 Config Service 读取命名空间的完整 ApolloConfig
     * @param request 参数
     * @param appId 应用 id
     * @param cluster 集群
     * @param namespace 命名空间
     * @return 解析后的 ApolloConfig JSONObject，读不到时返回 null
     * @throws Exception 异常
     */
    private JsonObject configJson(MiddlewareConnectRequest request, String appId, String cluster,
                                  String namespace) throws Exception {
        // 配置服务路径（经 apollo-client 2.5.0 字节码确认：configs/{appId}/{cluster}/{namespace}，
        // 可选参数 releaseKey / dataCenter / ip / label / messages）
        String path = "/configs/" + url(appId) + "/" + url(cluster) + "/" + url(namespace);
        String body = send(request, path).body();
        JsonObject root = asObject(body);
        if (root == null) {
            return null;
        }
        // 仍处于当前 releaseKey 且没拿到 configurations 说明不是有效 ApolloConfig
        if (root.getJsonObject("configurations").toMap().isEmpty()) {
            return null;
        }
        return root;
    }

    /**
     * 管理面的命名空间信息（Admin Service /v1/apps/{appId}/clusters/{cluster}/namespaces/{namespace}）
     * @param request 参数
     * @param appId 应用 id
     * @param cluster 集群
     * @param namespace 命名空间
     * @return 返回值
     * @throws Exception 异常
     */
    private MiddlewareDocument namespaceDocument(MiddlewareConnectRequest request, String appId, String cluster,
                                                 String namespace) throws Exception {
        String path = "/v1/apps/" + url(appId) + "/clusters/" + url(cluster) + "/namespaces/" + url(namespace);
        List<JsonObject> list = items(request, path);
        if (list.isEmpty()) {
            return null;
        }
        JsonObject ns = list.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("# appId=").append(getField(ns, "appId", "")).append('\n');
        sb.append("# cluster=").append(getField(ns, "clusterName", "")).append('\n');
        sb.append("# namespace=").append(getField(ns, "namespaceName", "")).append('\n');
        sb.append("# format=").append(getField(ns, "format", "")).append('\n');
        sb.append("# env=").append(getField(ns, "env", "")).append('\n');
        sb.append("# status=").append(getField(ns, "status", "")).append('\n');
        return MiddlewareDocument.of("命名空间 " + namespace + " (" + appId + "/" + cluster + ")", sb.toString());
    }

    /** 创建配置文件或命名空间的详情正文 */
    private MiddlewareDocument configDocument(MiddlewareConnectRequest request, String appId, String cluster,
                                              String namespace) throws Exception {
        JsonObject root = configJson(request, appId, cluster, namespace);
        if (root == null) {
            return null;
        }
        return MiddlewareDocument.of("命名空间 " + namespace + " (" + appId + "/" + cluster + ")",
                root.toJSONString());
    }
}
