package com.chua.nacos.support.spi;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nacos 中间件管理面 SPI 实现（默认装载）。
 *
 * <p><b>为什么走 HTTP 而不是引 nacos-client</b>：管理面只需要读
 * 命名空间 / 配置 / 服务列表，这些都有稳定的 OpenAPI（{@code /nacos/v1/console/**}、
 * {@code /nacos/v1/cs/**}）。引官方客户端会把一整套注册中心依赖拖进每个使用方。</p>
 *
 * <p><b>只读</b>：命名空间列表、配置列表与内容、服务与实例列表。
 * 发布配置 / 删除配置 / 注册实例一律不提供。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "nacos", order = 200)
public class NacosClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** HTTP 超时 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    /** Nacos OpenAPI 版本参数 */
    private static final String API = "/nacos/v1/console";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String name() {
        return "nacos";
    }

    @Override
    public String displayName() {
        return "Nacos 注册配置中心";
    }

    @Override
    public int defaultPort() {
        return 8848;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "命名空间 (Namespaces)", "配置 / 服务",
                "输入查询：namespaces / configs <dataId> / services / instances <service>",
                "只读查询支持 namespaces（命名空间列表）、configs <dataId>（配置内容）、"
                        + "services（服务列表）、instances <service>（服务实例与健康状态）");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        try {
            // 健康检查端点：v1 console 的 health
            HttpResponse<String> r = send(request, GET, "/nacos/v1/console/health/readiness", null, null);
            if (r.statusCode() >= 400) {
                return MiddlewareTestResult.fail("HTTP " + r.statusCode() + ": " + r.body());
            }
            // 版本号在 /nacos/v1/console/server/state 或首页；取不到就留空
            return MiddlewareTestResult.ok("", "Nacos 控制台可达");
        } catch (Exception e) {
            return MiddlewareTestResult.fail(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try {
            String body = send(request, GET, API + "/namespaces", null, null).body();
            // 响应形如 {"code":0,"data":[{"namespace":"","namespaceShowName":"public"},...]}
            Matcher ns = Pattern.compile("\"namespace\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            Matcher show = Pattern.compile("\"namespaceShowName\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            while (ns.find()) {
                String id = ns.group(1);
                String name = show.find() ? show.group(1) : id;
                out.add(new MiddlewareTreeNode(id.isEmpty() ? "public" : id,
                        name.isEmpty() ? "public" : name, id, null));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        // parentKey = 命名空间 id
        String ns = "public".equals(parentKey) ? "" : parentKey;
        try {
            // 子节点 = 该命名空间下的配置列表
            String body = send(request, GET,
                    API + "/configurations?dataId=&group=&appName=&namespaceId=" + url(ns)
                            + "&pageNo=1&pageSize=200&search=accurate",
                    null, null).body();
            Matcher id = Pattern.compile("\"dataId\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            Matcher grp = Pattern.compile("\"group\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            int i = 0;
            while (id.find() && i < 200) {
                String d = id.group(1);
                String g = grp.find() ? grp.group(1) : "DEFAULT_GROUP";
                out.add(new MiddlewareTreeNode(ns + "|" + d + "|" + g, d, "group=" + g, null));
                i++;
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：namespaces / configs <dataId> / "
                    + "services / instances <service>");
        }
        String cmd = expression.trim();
        String lower = cmd.toLowerCase();
        long t0 = System.currentTimeMillis();
        try {
            if (lower.equals("namespaces")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (MiddlewareTreeNode n : listTopNodes(request)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("namespace", n.label());
                    row.put("id", n.comment());
                    rows.add(row);
                }
                return MiddlewareQueryResult.of(List.of("namespace", "id"), rows, false, rows.size(),
                        System.currentTimeMillis() - t0);
            }
            if (lower.equals("services")) {
                return services(request, t0, "");
            }
            if (lower.startsWith("services ")) {
                return services(request, t0, cmd.substring(9).trim());
            }
            if (lower.startsWith("instances ")) {
                return instances(request, t0, cmd.substring(10).trim());
            }
            if (lower.startsWith("configs ")) {
                return config(request, t0, cmd.substring(7).trim());
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 namespaces / services / instances <service> / configs <dataId>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        try {
            if ("config".equals(scope)) {
                // key = namespaceId|dataId|group
                String[] parts = key.split("\\|", -1);
                if (parts.length < 3) {
                    return null;
                }
                String ns = parts[0];
                String d = parts[1];
                String g = parts[2];
                String body = send(request, GET,
                        API + "/configurations?dataId=" + url(d) + "&group=" + url(g)
                                + "&namespaceId=" + url(ns) + "&show=all",
                        null, null).body();
                return MiddlewareDocument.of("配置 " + d, body);
            }
            if ("service".equals(scope)) {
                return instancesDocument(request, key);
            }
        } catch (Exception ignore) {
            // 详情读不到不影响主流程
        }
        return null;
    }

    // ==================== 内部实现 ====================

    private static final String GET = "GET";
    private static final String POST = "POST";

    /** 服务列表 */
    private MiddlewareQueryResult services(MiddlewareConnectRequest request, long t0, String ns) throws Exception {
        String namespaceId = ns.isEmpty() || "public".equals(ns) ? "" : ns;
        String body = send(request, GET,
                "/nacos/v1/ns/service/list?pageNo=1&pageSize=200&namespaceId=" + url(namespaceId) + "&ignoreEmptyService=true",
                null, null).body();
        List<Map<String, Object>> rows = new ArrayList<>();
        Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        Matcher cnt = Pattern.compile("\"clusterCount\"\\s*:\\s*\"?([0-9]+)\"?").matcher(body);
        int i = 0;
        while (name.find() && i < 200) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", name.group(1));
            row.put("clusters", cnt.find() ? cnt.group(1) : "-");
            rows.add(row);
            i++;
        }
        return MiddlewareQueryResult.of(List.of("service", "clusters"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /** 服务实例列表（含健康状态） */
    private MiddlewareQueryResult instances(MiddlewareConnectRequest request, long t0, String service) throws Exception {
        if (service.isEmpty()) {
            return MiddlewareQueryResult.fail("instances 需要服务名，如 instances my-service");
        }
        String body = send(request, GET,
                "/nacos/v1/ns/instance/list?serviceName=" + url(service) + "&healthyOnly=false",
                null, null).body();
        List<Map<String, Object>> rows = new ArrayList<>();
        Matcher ip = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        Matcher port = Pattern.compile("\"port\"\\s*:\\s*\"?([0-9]+)\"?").matcher(body);
        Matcher healthy = Pattern.compile("\"healthy\"\\s*:\\s*(true|false)").matcher(body);
        int i = 0;
        while (ip.find() && i < 200) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service);
            row.put("ip", ip.group(1));
            row.put("port", port.find() ? port.group(1) : "-");
            row.put("healthy", healthy.find() ? healthy.group(1) : "-");
            rows.add(row);
            i++;
        }
        return MiddlewareQueryResult.of(List.of("service", "ip", "port", "healthy"), rows, false, rows.size(),
                System.currentTimeMillis() - t0);
    }

    /** 服务实例详情文档 */
    private MiddlewareDocument instancesDocument(MiddlewareConnectRequest request, String service) throws Exception {
        MiddlewareQueryResult r = instances(request, System.currentTimeMillis(), service);
        StringBuilder sb = new StringBuilder("服务 ").append(service).append('\n');
        for (Map<String, Object> row : r.rows()) {
            sb.append(row.get("ip")).append(':').append(row.get("port"))
                    .append("  healthy=").append(row.get("healthy")).append('\n');
        }
        return MiddlewareDocument.of("服务 " + service, sb.toString());
    }

    /** 单个配置内容 */
    private MiddlewareQueryResult config(MiddlewareConnectRequest request, long t0, String dataId) throws Exception {
        if (dataId.isEmpty()) {
            return MiddlewareQueryResult.fail("configs 需要 dataId，如 configs application.yml");
        }
        String body = send(request, GET, API + "/configurations?dataId=" + url(dataId)
                + "&group=DEFAULT_GROUP&namespaceId=&show=all", null, null).body();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dataId", dataId);
        row.put("content", body);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        return MiddlewareQueryResult.of(List.of("dataId", "content"), rows, false, 1,
                System.currentTimeMillis() - t0);
    }

    /** 发一次 HTTP 请求（带 accessToken 时自动加参数） */
    private HttpResponse<String> send(MiddlewareConnectRequest request, String method, String path,
                                      String body, String contentType) throws Exception {
        String full = baseUrl(request) + path;
        String token = request.password();
        if (token != null && !token.isBlank() && full.contains("?")) {
            full = full + "&accessToken=" + url(token);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full)).timeout(HTTP_TIMEOUT);
        if (body != null) {
            b.header("Content-Type", contentType == null ? "application/json" : contentType);
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r;
    }

    private String baseUrl(MiddlewareConnectRequest request) {
        return "http://" + request.address();
    }

    private String url(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
