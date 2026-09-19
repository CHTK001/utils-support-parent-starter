package com.chua.prometheus.support.client;

import com.chua.common.support.exception.RemoteExecutionException;
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
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Prometheus 链式客户端
 * <p>
 * 基于 JDK HTTP客户端 封装 Prometheus HTTP API, 支持即时查询、范围查询、
 * 序列/标签发现、目标/规则/告警查询。
 * </p>
 * <p>
 * 失败语义：远程调用失败（连接超时、HTTP 非 2xx、响应非 JSON、Prometheus 返回
 * {@code status=error}）、响应字段类型或数值非法（缺少 {@code data}、值对元素不足、
 * 时间/数值字段无法解析）均抛出 {@link RemoteExecutionException}；入参不合法
 * （地址、超时、promql、标签名、时间范围）抛出 {@link IllegalArgumentException}；
 * 能力不支持的结果类型抛出 {@link UnsupportedOperationException}。
 * 只有语义上允许为空的字段（远端未返回可选字段、查询无匹配序列）才返回 0 或空集合。
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
 * List<PrometheusTarget> targets = client.targets();
 * client.close();
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
     * 结果类型: 向量
     */
    private static final String TYPE_VECTOR = "vector";

    /**
     * 结果类型: 矩阵
     */
    private static final String TYPE_MATRIX = "matrix";

    /**
     * 结果类型: 标量
     */
    private static final String TYPE_SCALAR = "scalar";

    /**
     * 标签名合法字符集: 首字符为字母或下划线, 其余为字母/数字/下划线
     */
    private static final Pattern LABEL_NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

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
     * 单次请求超时(毫秒), 取自构建器配置
     */
    private final int timeoutMs;

    /**
     * 关闭标记, 用于关闭后调用守卫与幂等关闭
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造方法
     *
     * @param baseUrl   基础地址
     * @param username  用户名
     * @param password  密码
     * @param timeoutMs 超时(毫秒)
     */
    private PrometheusClient(String baseUrl, String username, String password, int timeoutMs) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeoutMs = requireTimeout(timeoutMs);
        this.basicAuth = buildBasicAuth(username, password);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    /**
     * 创建 构建器
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 规整基础地址: 校验协议并去掉全部尾部斜杠, 避免与固定路径拼接出双斜杠
     *
     * @param url 原始地址
     * @return 规整后的地址
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Prometheus baseUrl 不能为空");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Prometheus baseUrl 不合法: " + url);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Prometheus baseUrl 不是合法 URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Prometheus baseUrl 仅支持 http/https 协议: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Prometheus baseUrl 缺少主机名: " + url);
        }
        return trimmed;
    }

    /**
     * 校验超时参数
     *
     * @param timeoutMs 超时(毫秒)
     * @return 校验通过的超时(毫秒)
     */
    private static int requireTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Prometheus timeoutMs 必须大于 0, 当前: " + timeoutMs);
        }
        return timeoutMs;
    }

    /**
     * 构造 基础 认证 头
     *
     * @param username 用户名
     * @param password 密码
     * @return Basic 认证 值, 无认证返回 空
     */
    private static String buildBasicAuth(String username, String password) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        String token = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 基础地址
     *
     * @return 规整后的基础地址
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 是否已关闭
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    // ==================== 查询 ====================

    /**
     * 即时查询入口
     *
     * @param promql promql
     * @return QueryOperation
     */
    public QueryOperation query(String promql) {
        return new QueryOperation(promql);
    }

    /**
     * 范围查询入口
     *
     * @param promql promql
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
     * @return 标签名列表, 远端无数据时为空列表
     */
    public List<String> labels() {
        return readStringList(getJson(PATH_LABELS + "names"), "label/names");
    }

    /**
     * 查询指定标签的值
     *
     * @param label 标签名, 必须匹配 {@code [a-zA-Z_][a-zA-Z0-9_]*}
     * @return 标签值列表, 远端无数据时为空列表
     */
    public List<String> labelValues(String label) {
        String name = requireLabelName(label);
        // 标签名位于路径段: 先按字符集白名单显式拒绝, 再按路径段编码;
        // 百分号编码不会转义 "." 与 "..", 仅靠编码挡不住 "/api/v1/label/../values" 这类路径穿越
        JsonObject root = getJson(PATH_LABELS + encodePathSegment(name) + "/values");
        return readStringList(root, "label/" + name + "/values");
    }

    /**
     * 查询抓取目标
     *
     * @return 目标列表
     */
    public List<PrometheusTarget> targets() {
        JsonObject root = getJson(PATH_TARGETS);
        JsonObject data = requireData(root, PATH_TARGETS);
        List<PrometheusTarget> out = new ArrayList<>();
        // activeTargets 缺失按"无目标"处理, 存在却不是数组则属于响应非法
        JsonArray active = optionalArray(data, "activeTargets", PATH_TARGETS);
        if (active == null) {
            return out;
        }
        for (int i = 0; i < active.size(); i++) {
            JsonObject obj = requireObject(active.get(i), PATH_TARGETS, i);
            PrometheusTarget t = new PrometheusTarget();
            t.setScrapeUrl(str(obj.get("scrapeUrl")));
            t.setHealth(str(obj.get("health")));
            t.setLastError(str(obj.get("lastError")));
            // 远端 lastScrape 为 RFC3339 时间字符串, 必须按时间解析, 不能按数值解析
            t.setLastScrape(epochMillis(obj.get("lastScrape"), "lastScrape", PATH_TARGETS));
            // 远端字段名为 lastScrapeDuration, 单位是秒
            t.setScrapeDuration(doubleValue(obj.get("lastScrapeDuration"), "lastScrapeDuration", PATH_TARGETS));
            JsonObject labels = asObject(obj.get("labels"));
            if (labels != null) {
                t.setJob(str(labels.get("job")));
                t.setInstance(str(labels.get("instance")));
            }
            out.add(t);
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
        JsonObject data = requireData(root, PATH_RULES);
        List<PrometheusRule> out = new ArrayList<>();
        // groups 缺失按"无规则"处理, 存在却不是数组则属于响应非法
        JsonArray groups = optionalArray(data, "groups", PATH_RULES);
        if (groups == null) {
            return out;
        }
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = requireObject(groups.get(i), PATH_RULES, i);
            String file = str(group.get("file"));
            String groupName = str(group.get("name"));
            JsonArray rules = optionalArray(group, "rules", PATH_RULES + ".groups[" + i + "]");
            if (rules == null) {
                continue;
            }
            for (int j = 0; j < rules.size(); j++) {
                JsonObject ruleObj = requireObject(rules.get(j), PATH_RULES + ".groups[" + i + "].rules", j);
                PrometheusRule rule = new PrometheusRule();
                rule.setName(str(ruleObj.get("name")));
                rule.setQuery(str(ruleObj.get("query")));
                rule.setType(str(ruleObj.get("type")));
                rule.setState(str(ruleObj.get("state")));
                rule.setHealth(str(ruleObj.get("health")));
                rule.setLastError(str(ruleObj.get("lastError")));
                // duration 为 for 阈值的秒数, evaluationTime 为上次评估耗时秒数
                rule.setDuration(doubleValue(ruleObj.get("duration"), "duration", PATH_RULES));
                rule.setEvaluationTime(doubleValue(ruleObj.get("evaluationTime"), "evaluationTime", PATH_RULES));
                // lastEvaluation 为 RFC3339 时间字符串, 转换为 epoch 毫秒
                rule.setLastEvaluation(epochMillis(ruleObj.get("lastEvaluation"), "lastEvaluation", PATH_RULES));
                rule.setRuleGroup(groupName);
                rule.setRuleFile(file);
                rule.setLabels(toStringMap(asObject(ruleObj.get("labels"))));
                rule.setAnnotations(toStringMap(asObject(ruleObj.get("annotations"))));
                out.add(rule);
            }
        }
        return out;
    }

    /**
     * 查询当前告警
     *
     * @return 告警列表
     */
    public List<PrometheusAlert> alerts() {
        JsonObject root = getJson(PATH_ALERTS);
        JsonObject data = requireData(root, PATH_ALERTS);
        List<PrometheusAlert> out = new ArrayList<>();
        // alerts 缺失按"当前无告警"处理, 存在却不是数组则属于响应非法
        JsonArray alerts = optionalArray(data, "alerts", PATH_ALERTS);
        if (alerts == null) {
            return out;
        }
        for (int i = 0; i < alerts.size(); i++) {
            JsonObject obj = requireObject(alerts.get(i), PATH_ALERTS, i);
            PrometheusAlert alert = new PrometheusAlert();
            alert.setState(str(obj.get("state")));
            alert.setValue(str(obj.get("value")));
            // activeAt 为 RFC3339 时间字符串, 转换为 epoch 毫秒
            alert.setActiveAt(epochMillis(obj.get("activeAt"), "activeAt", PATH_ALERTS));
            alert.setLabels(toStringMap(asObject(obj.get("labels"))));
            alert.setAnnotations(toStringMap(asObject(obj.get("annotations"))));
            out.add(alert);
        }
        return out;
    }

    // ==================== 内部 HTTP ====================

    /**
     * 关闭后调用守卫
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PrometheusClient 已关闭, 不可再发起请求: " + baseUrl);
        }
    }

    /**
     * 发送 获取 并解析为 json对象
     * <p>
     * 失败(网络异常、非 2xx、响应非 JSON、缺少 status 字段)一律抛出
     * {@link RemoteExecutionException}, 由调用方感知查询失败, 不再返回空值。
     * </p>
     *
     * @param pathAndQuery 路径与查询串
     * @return JsonObject, 非 null
     */
    private JsonObject getJson(String pathAndQuery) {
        ensureOpen();
        URI uri = toUri(baseUrl + pathAndQuery);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
        if (basicAuth != null) {
            builder.header("Authorization", basicAuth);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            // 恢复中断标记后显式抛出, 不吞中断信号
            Thread.currentThread().interrupt();
            throw new RemoteExecutionException("[Prometheus] 请求被中断: " + pathAndQuery, e);
        } catch (IOException e) {
            throw new RemoteExecutionException("[Prometheus] 请求失败: " + uri + ", 原因: " + e.getMessage(), e);
        }
        // BodyHandlers.ofString 已在返回前读完并释放连接, 不存在响应体流泄漏
        int status = response.statusCode();
        String body = response.body();
        if (status < 200 || status >= 300) {
            throw new RemoteExecutionException("[Prometheus] HTTP " + status + " " + pathAndQuery
                    + ", 响应: " + brief(body));
        }
        JsonObject root = Json.getJsonObject(body);
        if (root == null || root.isEmpty() || !root.containsKey("status")) {
            throw new RemoteExecutionException("[Prometheus] 响应不是合法的 Prometheus API JSON: " + brief(body));
        }
        checkApiStatus(root, pathAndQuery);
        return root;
    }

    /**
     * 校验 Prometheus API 的 status 字段
     *
     * @param root         响应根对象
     * @param pathAndQuery 请求路径, 用于异常信息
     */
    private static void checkApiStatus(JsonObject root, String pathAndQuery) {
        String status = str(root.get("status"));
        if ("success".equals(status)) {
            return;
        }
        throw new RemoteExecutionException("[Prometheus] " + pathAndQuery + " 返回 status=" + status
                + ", errorType=" + str(root.get("errorType")) + ", error=" + str(root.get("error")));
    }

    /**
     * 取出响应中的 data 对象
     *
     * @param root  响应根对象
     * @param path  请求路径, 用于异常信息
     * @return data 对象, 非 null
     */
    private static JsonObject requireData(JsonObject root, String path) {
        JsonObject data = asObject(root.get("data"));
        if (data == null) {
            throw new RemoteExecutionException("[Prometheus] " + path + " 响应缺少 data 对象: " + brief(root.toJSONString()));
        }
        return data;
    }

    /**
     * 构造请求 URI, 语法非法时给出明确异常
     *
     * @param rawUrl 完整地址
     * @return URI
     */
    private static URI toUri(String rawUrl) {
        try {
            return new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new RemoteExecutionException("[Prometheus] 请求地址非法: " + rawUrl + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 取出必需的数组字段
     *
     * @param parent 父对象
     * @param key    字段名
     * @param api    接口路径, 用于异常信息
     * @return JsonArray, 非 null
     */
    private static JsonArray requireArray(JsonObject parent, String key, String api) {
        Object value = parent.get(key);
        if (value == null) {
            throw new RemoteExecutionException("[Prometheus] " + api + " 响应缺少 " + key + " 数组: "
                    + brief(parent.toJSONString()));
        }
        JsonArray array = asArray(value);
        if (array == null) {
            throw new RemoteExecutionException("[Prometheus] " + api + " 响应的 " + key + " 不是数组: " + brief(str(value)));
        }
        return array;
    }

    /**
     * 取出可选的数组字段: 字段缺失返回 null 由调用方按空处理, 存在但类型不符视为响应非法
     *
     * @param parent 父对象
     * @param key    字段名
     * @param api    接口路径, 用于异常信息
     * @return JsonArray 或 null
     */
    private static JsonArray optionalArray(JsonObject parent, String key, String api) {
        Object value = parent.get(key);
        if (value == null) {
            return null;
        }
        JsonArray array = asArray(value);
        if (array == null) {
            throw new RemoteExecutionException("[Prometheus] " + api + " 响应的 " + key + " 不是数组: " + brief(str(value)));
        }
        return array;
    }

    /**
     * 取出必须为 JSON 对象的数组元素
     *
     * @param value 元素原始值
     * @param api   接口路径, 用于异常信息
     * @param index 元素下标, 用于异常信息
     * @return JsonObject, 非 null
     */
    private static JsonObject requireObject(Object value, String api, int index) {
        JsonObject obj = asObject(value);
        if (obj == null) {
            throw new RemoteExecutionException("[Prometheus] " + api + "[" + index + "] 不是 JSON 对象: "
                    + brief(str(value)));
        }
        return obj;
    }

    /**
     * 校验标签名: 标签名会拼进请求路径段, 不合法时显式拒绝而非编码放行
     *
     * @param label 标签名
     * @return 去除首尾空白后的标签名
     */
    private static String requireLabelName(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        String name = label.trim();
        if (!LABEL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("标签名不合法, 需匹配 [a-zA-Z_][a-zA-Z0-9_]*, 实际: " + name);
        }
        return name;
    }

    /**
     * 读取标签类接口的字符串数组结果
     *
     * @param root 响应根对象
     * @param api  接口路径, 用于异常信息
     * @return 字符串列表, 远端返回空数组时为空列表
     */
    private static List<String> readStringList(JsonObject root, String api) {
        List<String> out = new ArrayList<>();
        JsonArray data = requireArray(root, "data", api);
        for (int i = 0; i < data.size(); i++) {
            Object item = data.get(i);
            if (item == null) {
                throw new RemoteExecutionException("[Prometheus] " + api + " 的 data[" + i + "] 为 null");
            }
            out.add(String.valueOf(item));
        }
        return out;
    }

    /**
     * 执行即时查询
     *
     * @param promql  promql
     * @param timeSec 评估时间点(秒), {@code null} 表示不下发该参数(由服务端取当前时间)
     * @return QueryResult
     */
    private QueryResult doQuery(String promql, Long timeSec) {
        requirePromql(promql);
        StringBuilder path = new StringBuilder(PATH_QUERY).append("?query=").append(encode(promql));
        if (timeSec != null) {
            path.append("&time=").append(timeSec.longValue());
        }
        return parseQueryResult(getJson(path.toString()), PATH_QUERY);
    }

    /**
     * 执行范围查询
     *
     * @param promql   promql
     * @param startSec 起始时间(秒)
     * @param endSec   结束时间(秒)
     * @param stepSec  步长(秒)
     * @return QueryResult(matrix)
     */
    private QueryResult doQueryRange(String promql, long startSec, long endSec, long stepSec) {
        requirePromql(promql);
        if (stepSec <= 0) {
            throw new IllegalArgumentException("范围查询 step 必须大于 0, 当前: " + stepSec);
        }
        if (startSec > endSec) {
            throw new IllegalArgumentException("范围查询 start 不能晚于 end, start=" + startSec + ", end=" + endSec);
        }
        String path = PATH_QUERY_RANGE
                + "?query=" + encode(promql)
                + "&start=" + startSec
                + "&end=" + endSec
                + "&step=" + stepSec;
        return parseQueryResult(getJson(path), PATH_QUERY_RANGE);
    }

    /**
     * 校验 promql 非空
     *
     * @param promql promql
     */
    private static void requirePromql(String promql) {
        if (promql == null || promql.trim().isEmpty()) {
            throw new IllegalArgumentException("promql 不能为空");
        }
    }

    /**
     * 解析查询结果
     * <p>
     * 逐条按下标遍历, 不使用 {@code JsonArray#forEach}, 因为其内部使用 SafeConsumer
     * 会吞掉消费逻辑抛出的异常, 导致解析失败被静默丢弃。
     * </p>
     *
     * @param root 根对象
     * @param path 请求路径, 用于异常信息
     * @return QueryResult
     */
    private QueryResult parseQueryResult(JsonObject root, String path) {
        JsonObject data = requireData(root, path);
        String resultType = str(data.get("resultType"));
        if (resultType == null) {
            throw new RemoteExecutionException("[Prometheus] " + path + " 的 data 缺少 resultType: " + brief(data.toJSONString()));
        }
        QueryResult result = new QueryResult();
        result.setResultType(resultType);
        Object raw = data.get("result");
        if (TYPE_SCALAR.equals(resultType)) {
            // 标量结果的 result 是 [时间戳, "值"] 数对, 而非对象数组
            JsonArray pair = asArray(raw);
            if (pair == null) {
                throw new RemoteExecutionException("[Prometheus] " + path + " 的 scalar 结果不是值对: " + brief(str(raw)));
            }
            PrometheusMetric metric = new PrometheusMetric();
            metric.setTimestamp(epochSeconds(pair.get(0), "result 时间位", path)); // [P3C 四十一 豁免] <原因: JsonArray 元素下标访问，非 java.util.List>
            metric.setValue(parseSampleValue(pair));
            result.getResult().add(metric);
            return result;
        }
        if (!TYPE_VECTOR.equals(resultType) && !TYPE_MATRIX.equals(resultType)) {
            throw new UnsupportedOperationException("[Prometheus] 暂不支持的 resultType: " + resultType
                    + ", 当前仅支持 vector/matrix/scalar, 请勿据此构造空结果");
        }
        JsonArray items = asArray(raw);
        if (items == null) {
            throw new RemoteExecutionException("[Prometheus] " + path + " 的 " + resultType + " 结果不是数组: " + brief(str(raw)));
        }
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = requireObject(items.get(i), path, i);
            PrometheusMetric m = new PrometheusMetric();
            // metric 缺失按"无标签维度"处理, 匿名序列的 metric 本身就是空对象
            m.setMetric(toStringMap(asObject(item.get("metric"))));
            if (TYPE_VECTOR.equals(resultType)) {
                JsonArray pair = requireArray(item, "value", path + ".result[" + i + "]");
                m.setTimestamp(epochSeconds(pair.get(0), "value 时间位", path)); // [P3C 四十一 豁免] <原因: JsonArray 元素下标访问，非 java.util.List>
                m.setValue(parseSampleValue(pair));
            } else {
                m.setValues(parseSamples(requireArray(item, "values", path + ".result[" + i + "]"), path));
            }
            result.getResult().add(m);
        }
        return result;
    }

    /**
     * 解析采样序列
     *
     * @param arr 值数组, 非 null
     * @param api 接口路径, 用于异常信息
     * @return 采样点列表
     */
    private List<PrometheusMetric.Sample> parseSamples(JsonArray arr, String api) {
        List<PrometheusMetric.Sample> samples = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonArray pair = asArray(arr.get(i));
            if (pair == null) {
                throw new RemoteExecutionException("[Prometheus] " + api + " 的 values[" + i + "] 不是 [时间戳, 值] 数组");
            }
            samples.add(new PrometheusMetric.Sample(
                    epochSeconds(pair.get(0), "values[" + i + "] 时间位", api), parseSampleValue(pair)));
        }
        return samples;
    }

    /**
     * 从值对 [时间戳, "值"] 中提取数值
     * <p>
     * Prometheus 将数值以字符串返回, 且可能是 {@code NaN}/{@code +Inf}/{@code -Inf};
     * 解析失败直接抛出, 不静默丢弃采样点。
     * </p>
     *
     * @param pair 值对
     * @return 数值
     */
    private static double parseSampleValue(JsonArray pair) {
        if (pair.size() < 2) {
            throw new RemoteExecutionException("[Prometheus] 值对元素不足, 期望 [时间戳, 值], 实际: " + brief(pair.toJSONString()));
        }
        Object valueObj = pair.get(1); // [P3C 四十一 豁免] <原因: JsonArray 元素下标访问，非 java.util.List>
        if (valueObj == null) {
            throw new RemoteExecutionException("[Prometheus] 值对的数值位为 null: " + brief(pair.toJSONString()));
        }
        String text = String.valueOf(valueObj).trim();
        switch (text) {
            case "NaN" -> {
                return Double.NaN;
            }
            case "+Inf" -> {
                return Double.POSITIVE_INFINITY;
            }
            case "-Inf" -> {
                return Double.NEGATIVE_INFINITY;
            }
            default -> {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new RemoteExecutionException("[Prometheus] 无法解析采样值: " + text, e);
                }
            }
        }
    }

    /**
     * json对象 转 映射
     *
     * @param obj 对象, 可为空
     * @return Map, 非 null
     */
    private static Map<String, String> toStringMap(JsonObject obj) {
        Map<String, String> out = new LinkedHashMap<>();
        if (obj != null) {
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                Object v = entry.getValue();
                if (v != null) {
                    out.put(entry.getKey(), String.valueOf(v));
                }
            }
        }
        return out;
    }

    /**
     * 值转 JsonObject, 类型不符返回空
     *
     * @param value 原始值
     * @return JsonObject 或 null
     */
    private static JsonObject asObject(Object value) {
        if (value instanceof JsonObject obj) {
            return obj;
        }
        if (value instanceof Map) {
            return Json.createJsonObject((Map) value);
        }
        return null;
    }

    /**
     * 值转 JsonArray, 类型不符返回空
     *
     * @param value 原始值
     * @return JsonArray 或 null
     */
    private static JsonArray asArray(Object value) {
        if (value instanceof JsonArray arr) {
            return arr;
        }
        if (value instanceof Collection) {
            return Json.createJsonArray((Collection) value);
        }
        return null;
    }

    /**
     * URL 查询参数编码
     *
     * @param value 值
     * @return 编码后
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * URL 路径段编码
     *
     * @param value 值
     * @return 编码后, 空格使用 %20 而非 +
     */
    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 对象转字符串
     *
     * @param o o
     * @return str的结果
     */
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 数值字段取值
     * <p>
     * 字段缺失(null/空串)按 0 处理; 值存在却不是数值时显式抛出, 不再静默归零掩盖映射错误。
     * </p>
     *
     * @param o     原始值
     * @param field 字段名, 用于异常信息
     * @param api   接口路径, 用于异常信息
     * @return 数值
     */
    private static double doubleValue(Object o, String field, String api) {
        if (o == null) {
            return 0.0;
        }
        if (o instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(o).trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new RemoteExecutionException("[Prometheus] " + api + " 的字段 " + field + " 不是合法数值: " + brief(text), e);
        }
    }

    /**
     * 时间字段转 epoch 秒
     * <p>
     * Prometheus 返回的 epoch 为浮点秒(如 {@code 1701261657.078}), 按整数字符串解析必然失败;
     * 此处同时兼容浮点秒、整秒与 RFC3339 字符串。字段缺失(null/空串)按 0 处理表示时间未知;
     * 值存在却无法解析时显式抛出, 不再静默归零把损坏的响应伪装成 1970 年。
     * </p>
     *
     * @param o     时间值
     * @param field 字段名, 用于异常信息
     * @param api   接口路径, 用于异常信息
     * @return epoch 秒, 字段缺失返回 0
     */
    private static long epochSeconds(Object o, String field, String api) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(o).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException numericError) {
            Instant instant = parseInstant(text);
            if (instant != null) {
                return instant.getEpochSecond();
            }
            throw new RemoteExecutionException("[Prometheus] " + api + " 的字段 " + field
                    + " 不是合法时间: " + brief(text), numericError);
        }
    }

    /**
     * 时间字段转 epoch 毫秒
     * <p>
     * 兼容远端两种写法: RFC3339 字符串(如 {@code 2024-01-01T00:00:00.123Z})与 epoch 秒数值。
     * 字段缺失(null/空串)按 0 处理; 值存在却无法解析时显式抛出, 不再静默归零。
     * </p>
     *
     * @param o     时间值
     * @param field 字段名, 用于异常信息
     * @param api   接口路径, 用于异常信息
     * @return epoch 毫秒, 字段缺失返回 0
     */
    private static long epochMillis(Object o, String field, String api) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number number) {
            return Math.round(number.doubleValue() * 1000D);
        }
        String text = String.valueOf(o).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        Instant instant = parseInstant(text);
        if (instant != null) {
            return instant.toEpochMilli();
        }
        try {
            return Math.round(Double.parseDouble(text) * 1000D);
        } catch (NumberFormatException e) {
            throw new RemoteExecutionException("[Prometheus] " + api + " 的字段 " + field + " 不是合法时间: " + brief(text), e);
        }
    }

    /**
     * 解析 RFC3339 / ISO8601 时间字符串
     *
     * @param text 时间文本
     * @return Instant, 解析失败返回 null
     */
    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试带偏移量的格式
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            log.debug("[Prometheus] 无法解析时间: {}", text);
            return null;
        }
    }

    /**
     * 截断过长的响应体, 避免异常信息过大
     *
     * @param body 响应体
     * @return 摘要文本
     */
    private static String brief(String body) {
        if (body == null) {
            return "<空响应>";
        }
        int max = 512;
        return body.length() <= max ? body : body.substring(0, max) + "...(共 " + body.length() + " 字符)";
    }

    /**
     * 关闭客户端
     * <p>
     * JDK HttpClient(JDK21+) 内部持有选择器线程与默认执行器, 不释放会泄漏线程;
     * 此处采用 orderly shutdown, 不阻塞等待在途请求, 以免拖慢容器销毁。
     * 方法幂等, 重复调用无效。
     * </p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            httpClient.shutdown();
        } catch (RuntimeException e) {
            log.warn("[Prometheus] 关闭 HttpClient 失败: {}", baseUrl, e);
        }
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
         * promql
         */
        private final String promql;

        /**
         * 评估时间点(秒), {@code null} 表示不下发 time 参数, 由服务端取当前时间
         */
        private Long time;

        /**
         * 构造方法
         *
         * @param promql promql
         */
        QueryOperation(String promql) {
            this.promql = promql;
        }

        /**
         * 设置评估时间点(时间旅行查询)
         *
         * @param epochSec 秒级时间戳, 允许任意取值(含 1970 年前), 只要调用即会下发
         * @return this
         */
        public QueryOperation time(long epochSec) {
            this.time = epochSec;
            return this;
        }

        /**
         * 执行查询
         *
         * @return 查询结果
         */
        public QueryResult execute() {
            return doQuery(promql, time);
        }

        /**
         * 执行并返回首个值
         *
         * @return 首个值, 无数据返回 空
         */
        public Double firstValue() {
            PrometheusMetric first = first();
            return first == null ? null : first.getValue();
        }

        /**
         * 执行并返回首个序列
         *
         * @return 首个序列, 无数据返回 空
         */
        public PrometheusMetric first() {
            QueryResult result = doQuery(promql, time);
            if (!result.hasData()) {
                return null;
            }
            return result.getResult().getFirst();
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
         * promql
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
         * @param promql promql
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
            if (startSec > endSec) {
                throw new IllegalArgumentException("范围查询 start 不能晚于 end, start=" + startSec + ", end=" + endSec);
            }
            this.start = startSec;
            this.end = endSec;
            return this;
        }

        /**
         * 设置最近 N 分钟
         *
         * @param minutes 分钟, 必须大于 0 且不导致秒数溢出
         * @return this
         */
        public RangeQueryOperation lastMinutes(long minutes) {
            if (minutes <= 0) {
                throw new IllegalArgumentException("lastMinutes 必须大于 0, 当前: " + minutes);
            }
            if (minutes > Long.MAX_VALUE / 60) {
                throw new IllegalArgumentException("lastMinutes 超出可表示范围, 当前: " + minutes);
            }
            long now = System.currentTimeMillis() / 1000;
            this.end = now;
            this.start = now - minutes * 60;
            return this;
        }

        /**
         * 设置步长
         *
         * @param stepSec 秒, 必须大于 0
         * @return this
         */
        public RangeQueryOperation step(long stepSec) {
            if (stepSec <= 0) {
                throw new IllegalArgumentException("范围查询 step 必须大于 0, 当前: " + stepSec);
            }
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
         * @param matcher promql 标签匹配器, 如 up
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
            if (match == null || match.trim().isEmpty()) {
                throw new IllegalArgumentException("series 查询必须指定 match 匹配器");
            }
            String path = PATH_SERIES + "?match[]=" + encode(match.trim());
            JsonArray data = requireArray(getJson(path), "data", PATH_SERIES);
            List<Map<String, String>> out = new ArrayList<>(data.size());
            for (int i = 0; i < data.size(); i++) {
                out.add(toStringMap(requireObject(data.get(i), PATH_SERIES, i)));
            }
            return out;
        }
    }

    /**
     * 构建器
     *
     * @since 4.0.0.42
     * @author CH
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
         * 设置 基础 认证
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
         * @param ms 毫秒, 必须大于 0
         * @return this
         */
        public Builder timeoutMs(int ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("Prometheus timeoutMs 必须大于 0, 当前: " + ms);
            }
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
