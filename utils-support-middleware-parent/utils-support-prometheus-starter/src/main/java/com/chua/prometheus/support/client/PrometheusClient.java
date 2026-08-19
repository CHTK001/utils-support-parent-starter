package com.chua.prometheus.support.client;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.prometheus.support.model.PrometheusAlert;
import com.chua.prometheus.support.model.PrometheusMetric;
import com.chua.prometheus.support.model.PrometheusRule;
import com.chua.prometheus.support.model.PrometheusTarget;
import com.chua.prometheus.support.model.QueryResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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

/**
 * Prometheus 链式客户端
 * <p>
 * 基于 JDK HttpClient 封装 Prometheus HTTP API, 支持即时查询、范围查询、
 * 序列/标签发现、目标/规则/告警查询。
 * </p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * PrometheusClient client = PrometheusClient.builder()
 *     .baseUrl("http://localhost:9090")
 *     .timeoutMs(5000)
 *     .basicAuth("user", "pass")
 *     .build();
 *
 * QueryResult result = client.query("up").execute();
 * List<PrometheusTarget> targets = client.targets().list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PrometheusClient implements AutoCloseable {

    /**
     * 默认超时(毫秒)
     */
    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    /**
     * 即时查询路径
     */
    private static final String PATH_QUERY = "/api/v1/query";

    /**
     * 范围查询路径
     */
    private static final String PATH_QUERY_RANGE = "/api/v1/query_range";

    /**
     * 序列查询路径
     */
    private static final String PATH_SERIES = "/api/v1/series";

    /**
     * 标签值查询路径
     */
    private static final String PATH_LABELS = "/api/v1/label/";

    /**
     * 目标查询路径
     */
    private static final String PATH_TARGETS = "/api/v1/targets";

    /**
     * 规则查询路径
     */
    private static final String PATH_RULES = "/api/v1/rules";

    /**
     * 告警查询路径
     */
    private static final String PATH_ALERTS = "/api/v1/alerts";

    /**
     * 基础地址(不含尾斜杠)
     */
    private final String baseUrl;

    /**
     * 基础认证信息
     */
    private final String basicAuth;

    /**
     * 客户端
     */
    private final HttpClient httpClient;

    /**
     * 构造方法
     *
     * @param baseUrl   基础地址
     * @param username  用户名
     * @param password  密码
     * @param timeoutMs 超时(毫秒)
     */
    private PrometheusClient(String baseUrl, String username, String password, int timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.basicAuth = buildBasicAuth(username, password);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * 创建 Builder
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构造 Basic Auth 头
     *
     * @param username 用户名
     * @param password 密码
     * @return Basic Auth 值, 无认证返回 null
     */
    private static String buildBasicAuth(String username, String password) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        String token = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 查询 ====================

    /**
     * 即时查询入口
     *
     * @param promql PromQL
     * @return QueryOperation
     */
    public QueryOperation query(String promql) {
        return new QueryOperation(promql);
    }

    /**
     * 范围查询入口
     *
     * @param promql PromQL
     * @return RangeQueryOperation
     */
    public RangeQueryOperation queryRange(String promql) {
        return new RangeQueryOperation(promql);
    }

    /**
     * 查询序列
     *
     * @return SeriesOperation
     */
    public SeriesOperation series() {
        return new SeriesOperation();
    }

    /**
     * 查询所有标签名
     *
     * @return 标签名列表
     */
    public List<String> labels() {
        JsonObject root = getJson(PATH_LABELS + "names");
        JsonArray data = root == null ? null : root.getJsonArray("data");
        List<String> out = new ArrayList<>();
        if (data != null) {
            data.forEach(o -> {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            });
        }
        return out;
    }

    /**
     * 查询指定标签的值
     *
     * @param label 标签名
     * @return 标签值列表
     */
    public List<String> labelValues(String label) {
        JsonObject root = getJson(PATH_LABELS + label + "/values");
        JsonArray data = root == null ? null : root.getJsonArray("data");
        List<String> out = new ArrayList<>();
        if (data != null) {
            data.forEach(o -> {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            });
        }
        return out;
    }

    /**
     * 查询抓取目标
     *
     * @return 目标列表
     */
    public List<PrometheusTarget> targets() {
        JsonObject root = getJson(PATH_TARGETS);
        JsonObject data = root == null ? null : root.getJsonObject("data");
        List<PrometheusTarget> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        JsonArray active = data.getJsonArray("activeTargets");
        if (active != null) {
            active.forEach(o -> {
                if (o instanceof JsonObject obj) {
                    PrometheusTarget t = new PrometheusTarget();
                    t.setScrapeUrl(str(obj.getObject("scrapeUrl")));
                    t.setHealth(str(obj.getObject("health")));
                    t.setLastError(str(obj.getObject("lastError")));
                    t.setLastScrape(longOrZero(obj.getObject("lastScrape")));
                    t.setScrapeDuration(doubleOrZero(obj.getObject("scrapeDuration")));
                    JsonObject labels = obj.getJsonObject("labels");
                    if (labels != null) {
                        t.setJob(str(labels.getObject("job")));
                        t.setInstance(str(labels.getObject("instance")));
                    }
                    out.add(t);
                }
            });
        }
        return out;
    }

    /**
     * 查询告警规则
     *
     * @return 规则列表
     */
    public List<PrometheusRule> rules() {
        JsonObject root = getJson(PATH_RULES);
        JsonObject data = root == null ? null : root.getJsonObject("data");
        List<PrometheusRule> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        JsonArray groups = data.getJsonArray("groups");
        if (groups == null) {
            return out;
        }
        groups.forEach(o -> {
            if (!(o instanceof JsonObject group)) {
                return;
            }
            String file = str(group.getObject("file"));
            JsonArray rules = group.getJsonArray("rules");
            if (rules == null) {
                return;
            }
            rules.forEach(r -> {
                if (r instanceof JsonObject ruleObj) {
                    PrometheusRule rule = new PrometheusRule();
                    rule.setName(str(ruleObj.getObject("name")));
                    rule.setQuery(str(ruleObj.getObject("query")));
                    rule.setType(str(ruleObj.getObject("type")));
                    rule.setState(str(ruleObj.getObject("state")));
                    rule.setHealth(str(ruleObj.getObject("health")));
                    rule.setLastError(str(ruleObj.getObject("lastError")));
                    rule.setDuration(doubleOrZero(ruleObj.getObject("duration")));
                    rule.setRuleFile(file);
                    out.add(rule);
                }
            });
        });
        return out;
    }

    /**
     * 查询当前告警
     *
     * @return 告警列表
     */
    public List<PrometheusAlert> alerts() {
        JsonObject root = getJson(PATH_ALERTS);
        JsonObject data = root == null ? null : root.getJsonObject("data");
        List<PrometheusAlert> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        JsonArray alerts = data.getJsonArray("alerts");
        if (alerts == null) {
            return out;
        }
        alerts.forEach(o -> {
            if (o instanceof JsonObject obj) {
                PrometheusAlert alert = new PrometheusAlert();
                alert.setState(str(obj.getObject("state")));
                alert.setActiveAt(longOrZero(obj.getObject("activeAt")));
                alert.setLabels(toStringMap(obj.getJsonObject("labels")));
                alert.setAnnotations(toStringMap(obj.getJsonObject("annotations")));
                out.add(alert);
            }
        });
        return out;
    }

    // ==================== 内部 HTTP ====================

    /**
     * 发送 GET 并解析为 JsonObject
     *
     * @param pathAndQuery 路径与查询串
     * @return JsonObject, 失败返回 null
     */
    private JsonObject getJson(String pathAndQuery) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + pathAndQuery))
                    .timeout(Duration.ofMillis(DEFAULT_TIMEOUT_MS))
                    .GET();
            if (basicAuth != null) {
                builder.header("Authorization", basicAuth);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[Prometheus] HTTP {} status={}", pathAndQuery, response.statusCode());
                return null;
            }
            return Json.getJsonObject(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[Prometheus] 请求失败 {}", pathAndQuery, e);
            return null;
        }
    }

    /**
     * 发送 GET 并解析为 JsonArray
     *
     * @param pathAndQuery 路径与查询串
     * @return JsonArray, 失败返回空
     */
    private JsonArray getJsonArray(String pathAndQuery) {
        JsonObject root = getJson(pathAndQuery);
        return root == null ? new JsonArray() : root.getJsonArray("data");
    }

    /**
     * 执行即时查询
     *
     * @param promql PromQL
     * @return QueryResult
     */
    private QueryResult doQuery(String promql) {
        String encoded = encode(promql);
        JsonObject root = getJson(PATH_QUERY + "?query=" + encoded);
        return parseQueryResult(root);
    }

    /**
     * 执行范围查询
     *
     * @param promql   PromQL
     * @param startSec 起始时间(秒)
     * @param endSec   结束时间(秒)
     * @param stepSec  步长(秒)
     * @return QueryResult(matrix)
     */
    private QueryResult doQueryRange(String promql, long startSec, long endSec, long stepSec) {
        String query = PATH_QUERY_RANGE
                + "?query=" + encode(promql)
                + "&start=" + startSec
                + "&end=" + endSec
                + "&step=" + stepSec;
        JsonObject root = getJson(query);
        return parseQueryResult(root);
    }

    /**
     * 解析即时查询结果
     *
     * @param root 根对象
     * @return QueryResult
     */
    private QueryResult parseQueryResult(JsonObject root) {
        QueryResult result = new QueryResult();
        if (root == null) {
            return result;
        }
        String status = str(root.getObject("status"));
        if (!"success".equals(status)) {
            return result;
        }
        JsonObject data = root.getJsonObject("data");
        if (data == null) {
            return result;
        }
        String resultType = str(data.getObject("resultType"));
        result.setResultType(resultType);
        JsonArray resultArr = data.getJsonArray("result");
        if (resultArr == null) {
            return result;
        }
        resultArr.forEach(itemObj -> {
            if (itemObj instanceof JsonObject item) {
                PrometheusMetric m = new PrometheusMetric();
                m.setMetric(toStringMap(item.getJsonObject("metric")));
                if ("vector".equals(resultType)) {
                    m.setValue(valueFromPair(item.getObject("value")));
                } else if ("matrix".equals(resultType)) {
                    m.setValues(parseSamples(item.getJsonArray("values")));
                }
                result.getResult().add(m);
            }
        });
        return result;
    }

    /**
     * 解析采样序列
     *
     * @param arr 值数组
     * @return 采样点列表
     */
    private List<PrometheusMetric.Sample> parseSamples(JsonArray arr) {
        List<PrometheusMetric.Sample> samples = new ArrayList<>();
        if (arr == null) {
            return samples;
        }
        arr.forEach(o -> {
            if (o instanceof JsonArray pair && pair.size() >= 2) {
                long ts = longOrZero(pair.get(0));
                Double v = valueFromPair(pair);
                if (v != null) {
                    samples.add(new PrometheusMetric.Sample(ts, v));
                }
            }
        });
        return samples;
    }

    /**
     * 从值对 [timestamp, "value"] 中提取数值
     *
     * @param pair 值对
     * @return 数值, 失败返回 null
     */
    private Double valueFromPair(Object pair) {
        if (!(pair instanceof java.util.Collection<?> col) || col.isEmpty()) {
            return null;
        }
        Object valueObj = col.toArray()[col.size() - 1];
        if (valueObj == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(valueObj));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * JsonObject 转 Map
     *
     * @param obj 对象
     * @return Map
     */
    private static Map<String, String> toStringMap(JsonObject obj) {
        Map<String, String> out = new LinkedHashMap<>();
        if (obj != null) {
            obj.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, String.valueOf(v));
                }
            });
        }
        return out;
    }

    /**
     * URL 编码
     *
     * @param value 值
     * @return 编码后
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 对象转字符串
     */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 对象转 long
     */
    private static long longOrZero(Object o) {
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 对象转 double
     */
    private static double doubleOrZero(Object o) {
        if (o == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 关闭客户端
     */
    @Override
    public void close() {
        // JDK HttpClient 无需显式关闭
    }

    // ==================== 链式操作 ====================

    /**
     * 即时查询操作
     *
     * @author CH
     * @since 4.0.0.42
     */
    public class QueryOperation {

        /**
         * PromQL
         */
        private final String promql;

        /**
         * 构造方法
         *
         * @param promql PromQL
         */
        QueryOperation(String promql) {
            this.promql = promql;
        }

        /**
         * 执行查询
         *
         * @return 查询结果
         */
        public QueryResult execute() {
            return doQuery(promql);
        }

        /**
         * 执行并返回首个值
         *
         * @return 首个值, 无数据返回 null
         */
        public Double firstValue() {
            QueryResult result = doQuery(promql);
            if (!result.hasData()) {
                return null;
            }
            return result.getResult().get(0).getValue();
        }

        /**
         * 执行并返回首个序列
         *
         * @return 首个序列, 无数据返回 null
         */
        public PrometheusMetric first() {
            QueryResult result = doQuery(promql);
            if (!result.hasData()) {
                return null;
            }
            return result.getResult().get(0);
        }
    }

    /**
     * 范围查询操作
     *
     * @author CH
     * @since 4.0.0.42
     */
    public class RangeQueryOperation {

        /**
         * PromQL
         */
        private final String promql;

        /**
         * 起始时间(秒), 默认 -1h
         */
        private long start = System.currentTimeMillis() / 1000 - 3600;

        /**
         * 结束时间(秒), 默认 now
         */
        private long end = System.currentTimeMillis() / 1000;

        /**
         * 步长(秒), 默认 60
         */
        private long step = 60;

        /**
         * 构造方法
         *
         * @param promql PromQL
         */
        RangeQueryOperation(String promql) {
            this.promql = promql;
        }

        /**
         * 设置时间范围
         *
         * @param startSec 起始(秒)
         * @param endSec   结束(秒)
         * @return this
         */
        public RangeQueryOperation range(long startSec, long endSec) {
            this.start = startSec;
            this.end = endSec;
            return this;
        }

        /**
         * 设置最近 N 分钟
         *
         * @param minutes 分钟
         * @return this
         */
        public RangeQueryOperation lastMinutes(long minutes) {
            long now = System.currentTimeMillis() / 1000;
            this.end = now;
            this.start = now - minutes * 60;
            return this;
        }

        /**
         * 设置步长
         *
         * @param stepSec 秒
         * @return this
         */
        public RangeQueryOperation step(long stepSec) {
            this.step = stepSec;
            return this;
        }

        /**
         * 执行查询
         *
         * @return 查询结果(matrix)
         */
        public QueryResult execute() {
            return doQueryRange(promql, start, end, step);
        }
    }

    /**
     * 序列查询操作
     *
     * @author CH
     * @since 4.0.0.42
     */
    public class SeriesOperation {

        /**
         * 匹配器
         */
        private String match;

        /**
         * 设置匹配器
         *
         * @param matcher PromQL 标签匹配器, 如 up
         * @return this
         */
        public SeriesOperation match(String matcher) {
            this.match = matcher;
            return this;
        }

        /**
         * 查询序列
         *
         * @return 序列列表(Map 形式)
         */
        public List<Map<String, String>> list() {
            String url = PATH_SERIES + "?match[]=" + encode(match == null ? "" : match);
            JsonArray data = getJsonArray(url);
            List<Map<String, String>> out = new ArrayList<>();
            data.forEach(o -> {
                if (o instanceof JsonObject obj) {
                    out.add(toStringMap(obj));
                }
            });
            return out;
        }
    }

    /**
     * Builder
     *
     * @since 4.0.0.42
     */
    public static class Builder {

        /**
         * 基础地址
         */
        private String baseUrl = "http://localhost:9090";

        /**
         * 用户名
         */
        private String username;

        /**
         * 密码
         */
        private String password;

        /**
         * 超时(毫秒)
         */
        private int timeoutMs = DEFAULT_TIMEOUT_MS;

        /**
         * 设置基础地址
         *
         * @param url 地址
         * @return this
         */
        public Builder baseUrl(String url) {
            this.baseUrl = url;
            return this;
        }

        /**
         * 设置 Basic Auth
         *
         * @param user 用户名
         * @param pwd  密码
         * @return this
         */
        public Builder basicAuth(String user, String pwd) {
            this.username = user;
            this.password = pwd;
            return this;
        }

        /**
         * 设置超时
         *
         * @param ms 毫秒
         * @return this
         */
        public Builder timeoutMs(int ms) {
            this.timeoutMs = ms;
            return this;
        }

        /**
         * 构建客户端
         *
         * @return PrometheusClient
         */
        public PrometheusClient build() {
            return new PrometheusClient(baseUrl, username, password, timeoutMs);
        }
    }
}