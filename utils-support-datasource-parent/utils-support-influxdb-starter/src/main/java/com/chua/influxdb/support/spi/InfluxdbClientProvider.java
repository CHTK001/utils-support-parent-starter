package com.chua.influxdb.support.spi;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InfluxDB 中间件管理面 SPI 实现（v2 与 v3）。
 *
 * <p><b>为什么走 HTTP 而不是引客户端</b>：InfluxDB 的管理能力（Bucket、Measurement、
 * Flux/SQL 查询）都有稳定的 HTTP 面（{@code /api/v2/*}、{@code /api/v3/*}），
 * 引官方客户端会把一整套响应式依赖拖进每个使用方。这里用 JDK 自带
 * {@link HttpClient}，实现方零额外依赖。</p>
 *
 * <p><b>v2 / v3 的差异</b>：v3 的查询走 {@code /api/v3/query_sql}（SQL），
 * 列表与健康检查路径也不同；由 {@link #isV3()} 按数据源的 {@code database} 字段
 * 约定区分（建数据源时 v3 实例在 database 里写 {@code v3}）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "influxdb", order = 200)
public class InfluxdbClientProvider implements MiddlewareClientProvider {

    /** SPI 名称，同时是注册文件名 */
    public static final String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /** HTTP 超时：管理面要秒级返回 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    /** 响应体最大读取长度，避免把整个库刷进内存 */
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    @Override
    public String name() {
        return "influxdb";
    }

    @Override
    public String displayName() {
        return "InfluxDB 时序数据库";
    }

    @Override
    public int defaultPort() {
        return 8086;
    }

    @Override
    public MiddlewareCapability capability() {
        return new MiddlewareCapability(
                true, true, true, true,
                "存储桶 (Buckets)", "度量 (Measurements)",
                "输入查询：buckets / measurements / flux <Flux 语句> / sql <SQL 语句>",
                "只读查询支持 buckets（桶列表）、measurements（度量列表）、"
                        + "flux &lt;语句&gt;（Flux 查询）、sql &lt;语句&gt;（InfluxDB v3 SQL 查询）");
    }

    @Override
    public MiddlewareTestResult test(MiddlewareConnectRequest request) {
        boolean v3 = isV3(request);
        try {
            // v2 有 /health，v3 用 /api/v2/health 或 /ping；这里优先 /health，失败再试 /ping
            HttpResponse<String> r = send(request, v3 ? "GET" : "GET", v3 ? "/ping" : "/health", null, null);
            if (r.statusCode() >= 400) {
                HttpResponse<String> r2 = send(request, "GET", "/ping", null, null);
                if (r2.statusCode() >= 400) {
                    return MiddlewareTestResult.fail("HTTP " + r.statusCode() + " / " + r2.statusCode());
                }
            }
            String version = extractVersion(r.body());
            return MiddlewareTestResult.ok(version, v3 ? "InfluxDB v3（SQL）" : "InfluxDB v2（Flux）");
        } catch (Exception e) {
            return MiddlewareTestResult.fail(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try {
            if (isV3(request)) {
                // v3：数据库即顶层节点
                out.add(new MiddlewareTreeNode(request.database(), request.database(), "", null));
                return out;
            }
            String body = send(request, "GET", "/api/v2/buckets", null, null).body();
            for (String name : jsonStringArray(body, "name")) {
                out.add(new MiddlewareTreeNode(name, name, "", null));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey) {
        List<MiddlewareTreeNode> out = new ArrayList<>();
        try {
            if (isV3(request)) {
                // v3：度量即子节点（SQL 查 system 表）
                String sql = "SELECT DISTINCT \"_measurement\" FROM " + parentKey
                        + " GROUP BY \"_measurement\" ORDER BY \"_measurement\"";
                MiddlewareQueryResult r = sqlQuery(request, sql, 200);
                for (Map<String, Object> row : r.rows()) {
                    Object m = row.get("_measurement");
                    if (m != null) {
                        out.add(new MiddlewareTreeNode(String.valueOf(m), String.valueOf(m), "", null));
                    }
                }
                return out;
            }
            // v2：用 Flux 的 bucket 函数列出度量
            String flux = "import \"influxdata/influxdb/schema\"\n"
                    + "schema.measurements(bucket: \"" + parentKey + "\")";
            MiddlewareQueryResult r = fluxQuery(request, flux, 200);
            for (Map<String, Object> row : r.rows()) {
                Object v = row.get("_value");
                if (v != null) {
                    out.add(new MiddlewareTreeNode(String.valueOf(v), String.valueOf(v), "", null));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    @Override
    public MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit) {
        if (expression == null || expression.isBlank()) {
            return MiddlewareQueryResult.fail("查询表达式为空。支持：buckets / measurements / flux <语句> / sql <语句>");
        }
        String cmd = expression.trim();
        long t0 = System.currentTimeMillis();
        try {
            if (cmd.equalsIgnoreCase("buckets")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (MiddlewareTreeNode n : listTopNodes(request)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", n.label());
                    rows.add(row);
                }
                return MiddlewareQueryResult.of(List.of("bucket"), rows, false, rows.size(),
                        System.currentTimeMillis() - t0);
            }
            if (cmd.equalsIgnoreCase("measurements")) {
                List<Map<String, Object>> rows = new ArrayList<>();
                String bucket = request.database();
                for (MiddlewareTreeNode n : listChildren(request, bucket)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("measurement", n.label());
                    rows.add(row);
                }
                return MiddlewareQueryResult.of(List.of("measurement"), rows, false, rows.size(),
                        System.currentTimeMillis() - t0);
            }
            String lower = cmd.toLowerCase();
            if (lower.startsWith("flux ")) {
                return fluxQuery(request, cmd.substring(5), limit);
            }
            if (lower.startsWith("sql ")) {
                return sqlQuery(request, cmd.substring(4), limit);
            }
            return MiddlewareQueryResult.fail("不支持的查询: " + cmd
                    + "。只读查询仅支持 buckets / measurements / flux <语句> / sql <语句>");
        } catch (Exception e) {
            return MiddlewareQueryResult.fail(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key) {
        try {
            if ("bucket".equals(scope)) {
                // 桶详情：用 Flux 查该桶最近一条数据作概要
                String flux = "from(bucket: \"" + key + "\") |> range(start: -1h) |> limit(n: 1)";
                MiddlewareQueryResult r = fluxQuery(request, flux, 1);
                StringBuilder sb = new StringBuilder("存储桶 ").append(key).append('\n');
                if (r.rows().isEmpty()) {
                    sb.append("最近 1 小时无数据");
                } else {
                    r.rows().get(0).forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
                }
                return new MiddlewareDocument("存储桶 " + key, sb.toString(), Map.of());
            }
        } catch (Exception ignore) {
            // 详情读不到不影响主流程
        }
        return null;
    }

    // ==================== 内部实现 ====================

    /**
     * v3 判定：数据源的 {@code database} 字段写 {@code v3} 即视为 v3 实例。
     *
     * <p>不引入版本探测接口：那要多一次 HTTP 往返，而建数据源时就已经知道版本。</p>
     */
    private boolean isV3(MiddlewareConnectRequest request) {
        return "v3".equalsIgnoreCase(request.database().trim());
    }

    /** Flux 查询：POST /api/v2/query，返回注解 CSV */
    private MiddlewareQueryResult fluxQuery(MiddlewareConnectRequest request, String flux, int limit) throws Exception {
        String q = flux + "\n|> limit(n: " + Math.max(1, limit) + ")";
        HttpResponse<String> r = send(request, "POST", "/api/v2/query?org=" + url(request.username()), q,
                "application/vnd.flux");
        return parseCsv(r.body());
    }

    /** SQL 查询（v3）：POST /api/v3/query_sql */
    private MiddlewareQueryResult sqlQuery(MiddlewareConnectRequest request, String sql, int limit) throws Exception {
        String body = "{\"db\":\"" + jsonEscape(request.database()) + "\",\"q\":\""
                + jsonEscape(sql) + "\",\"format\":\"json\"}";
        String wrapped = sql.toLowerCase().contains(" limit ")
                ? sql : sql + " LIMIT " + Math.max(1, limit);
        body = "{\"db\":\"" + jsonEscape(request.database()) + "\",\"q\":\""
                + jsonEscape(wrapped) + "\",\"format\":\"json\"}";
        HttpResponse<String> r = send(request, "POST", "/api/v3/query_sql", body, "application/json");
        return parseJsonRows(r.body());
    }

    /** 发一次 HTTP 请求，带 Token 认证 */
    private HttpResponse<String> send(MiddlewareConnectRequest request, String method, String path,
                                      String body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl(request) + path))
                .timeout(HTTP_TIMEOUT);
        String token = request.password();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Token " + token);
        }
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

    /** 基础地址 */
    private String baseUrl(MiddlewareConnectRequest request) {
        return "http://" + request.address();
    }

    private String url(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** 从 JSON 响应里取某个字符串数组字段（v2 buckets 的 name） */
    private List<String> jsonStringArray(String json, String field) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 取响应体里的版本号 */
    private String extractVersion(String body) {
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 解析 InfluxDB 的注解 CSV 成表格。
     *
     * <p>格式：前若干行是 {@code #datatype}/{@code #group}/{@code #default} 注解，
     * 之后第一行是列名。列名可能重复（result/table/_value），按首次出现顺序去重，
     * 与前端「列并集推导」的口径一致。</p>
     */
    private MiddlewareQueryResult parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return MiddlewareQueryResult.empty();
        }
        String[] lines = csv.split("\n");
        List<String> header = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (header == null) {
                header = new ArrayList<>();
                Set<String> seen = new java.util.HashSet<>();
                for (String c : cells) {
                    if (seen.add(c)) {
                        header.add(c);
                    }
                }
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < cells.size(); i++) {
                row.put(header.get(i), cells.get(i));
            }
            rows.add(row);
        }
        if (header == null) {
            return MiddlewareQueryResult.empty();
        }
        return MiddlewareQueryResult.of(header, rows, false, rows.size(), 0);
    }

    /** 单行 CSV 切分（支持引号转义） */
    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    inQuotes = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 解析 v3 SQL 的 JSON 行结果 */
    private MiddlewareQueryResult parseJsonRows(String json) {
        if (json == null || json.isBlank()) {
            return MiddlewareQueryResult.empty();
        }
        // v3 的 json 格式是每行一个 JSON 对象
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String line : json.split("\n")) {
            line = line.trim();
            if (!line.startsWith("{")) {
                continue;
            }
            Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[^,}]+)").matcher(line);
            Map<String, Object> row = new LinkedHashMap<>();
            while (m.find()) {
                String k = m.group(1);
                String v = m.group(2);
                if (v.startsWith("\"")) {
                    v = v.substring(1, Math.max(1, v.length() - 1));
                }
                if (seen.add(k)) {
                    cols.add(k);
                }
                row.put(k, v);
            }
            rows.add(row);
        }
        return MiddlewareQueryResult.of(cols, rows, false, rows.size(), 0);
    }
}
