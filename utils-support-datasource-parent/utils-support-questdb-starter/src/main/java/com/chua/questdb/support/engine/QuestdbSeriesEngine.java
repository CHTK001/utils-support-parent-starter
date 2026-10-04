package com.chua.questdb.support.engine;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.series.SeriesEngine;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.questdb.client.Sender;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 基于 QuestDB 的时序存储实现（{@link SeriesEngine} SPI，别名 {@code questdb}）。
 *
 * <p><b>写入</b>：使用 QuestDB 官方 Java 客户端 {@link Sender}，以 InfluxDB Line Protocol
 * over HTTP 写入（默认 HTTP 9000 端口，内部走 {@code /write?precision=n}）。
 * measurement = {@code target}，tag = {@code monitor_id}，每个指标名一个数值列，
 * 设计时间戳列由 QuestDB 自动创建为 {@code timestamp}。每次 {@link #writePoint} 同步刷写，
 * 保证失败可见、不静默丢点。</p>
 *
 * <p><b>查询 / 指标发现 / 保留期清理</b>：经 QuestDB HTTP REST {@code /exec} 端点执行 SQL，
 * 结果按 JSON 解析（TIMESTAMP 列以 ISO-8601 字符串返回）。曲线在 Java 侧按统一窗口均值
 * 降采样（{@link SeriesEngine#downsample}），与 DataSource / Redis / InfluxDB 实现保持同一聚合语义。</p>
 *
 * <h2>SPI 配置</h2>
 * <pre>{@code
 * Properties props = new Properties();
 * props.setProperty("url", "http://127.0.0.1:9000");   // 必填，QuestDB HTTP 地址
 * props.setProperty("token", "xxx");                    // 可选，HTTP 鉴权 token
 * props.setProperty("username", "admin");               // 可选，与 password 成对使用
 * props.setProperty("password", "secret");
 * SeriesEngine engine = ServiceProvider.of(SeriesEngine.class).getNewExtension("questdb", props);
 * }</pre>
 *
 * <p>{@code database} 属性会被忽略：QuestDB 以表为存储单元，没有 InfluxDB 式的数据库概念。
 * 为兼容 monitor 的统一装配路径，保留该属性但不参与寻址。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("questdb")
public class QuestdbSeriesEngine implements SeriesEngine, AutoCloseable {

    /**
     * 引擎标识（与配置 {@code monitor.timeseries.engine} 对应）
     */
    private static final String ENGINE_NAME = "questdb";

    /**
     * QuestDB HTTP 服务默认端口
     */
    private static final int DEFAULT_HTTP_PORT = 9000;

    /**
     * 监控目标 tag 列名（QuestDB 将首列 symbol 视为 tag）
     */
    private static final String TAG_MONITOR_ID = "monitor_id";

    /**
     * ILP 自动创建的设计时间戳列名
     */
    private static final String DEFAULT_TIMESTAMP_COLUMN = "timestamp";

    /**
     * 单次曲线返回点数上限
     */
    private static final int MAX_SERIES_POINTS = 1440;

    /**
     * 查询/清理统一入口路径（追加 URL 编码后的 query 参数）
     */
    private static final String EXEC_PATH = "/exec?query=";

    /**
     * 判定「表不存在」的错误片段：此时按「无数据」处理，而不是后端故障
     */
    private static final String TABLE_NOT_EXIST = "does not exist";

    /**
     * QuestDB 数值列类型集合：只有数值列才可能是指标
     */
    private static final Set<String> NUMERIC_TYPES =
            Set.of("DOUBLE", "FLOAT", "LONG", "INT", "SHORT", "BYTE");

    /**
     * 请求超时时间
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 连接超时时间
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * QuestDB HTTP 基础地址（已去除末尾斜杠），未配置时为 null
     */
    private final String baseUrl;

    /**
     * QuestDB Sender 使用的 host:port 地址，未配置时为 null
     */
    private final String address;

    /**
     * HTTP 鉴权 token，可为空
     */
    private final String token;

    /**
     * HTTP 鉴权用户名，可为空
     */
    private final String username;

    /**
     * HTTP 鉴权密码，可为空
     */
    private final String password;

    /**
     * 是否已完成必要配置
     */
    private final boolean configured;

    /**
     * JDK HTTP 客户端，用于 /exec 查询与清理
     */
    private final HttpClient httpClient;

    /**
     * JSON 解析器（线程安全，仅读）
     */
    private final ObjectMapper objectMapper;

    /**
     * 写入串行锁：QuestDB Sender 非线程安全，且需与 close() 互斥
     */
    private final Object writeLock = new Object();

    /**
     * 惰性创建的 QuestDB Sender（HTTP 传输），可为空
     */
    private volatile Sender sender;

    /**
     * 是否已关闭：关闭后 isAvailable 必须为 false，避免向已释放资源继续提交读写
     */
    private volatile boolean closed;

    /**
     * 通过 SPI 配置构造引擎。
     *
     * @param properties 配置：url（必填）、token、username、password（可选）
     */
    public QuestdbSeriesEngine(Properties properties) {
        this(properties == null ? null : properties.getProperty("url"),
                properties == null ? null : properties.getProperty("token"),
                properties == null ? null : properties.getProperty("username"),
                properties == null ? null : properties.getProperty("password"));
    }

    /**
     * 通过完整参数构造引擎。
     *
     * @param url      QuestDB HTTP 地址，如 {@code http://127.0.0.1:9000}
     * @param token    HTTP 鉴权 token，可为空
     * @param username HTTP 鉴权用户名，可为空
     * @param password HTTP 鉴权密码，可为空
     */
    public QuestdbSeriesEngine(String url, String token, String username, String password) {
        this.token = emptyToNull(token);
        this.username = emptyToNull(username);
        this.password = emptyToNull(password);
        String normalized = normalizeBaseUrl(url);
        if (normalized == null) {
            this.baseUrl = null;
            this.address = null;
            this.configured = false;
            log.warn("[SeriesEngine] QuestdbSeriesEngine 不可用：url 未配置");
        } else {
            this.baseUrl = normalized;
            this.address = toAddress(normalized);
            this.configured = this.address != null;
            if (!this.configured) {
                log.warn("[SeriesEngine] QuestdbSeriesEngine 不可用：无法从 url 解析主机地址 url={}", url);
            }
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String engine() {
        return ENGINE_NAME;
    }

    @Override
    public boolean isAvailable() {
        return configured && !closed;
    }

    @Override
    public void writePoint(String target, Long monitorId, String metricName, double value, long timestamp) {
        if (target == null || monitorId == null || metricName == null) {
            throw new IllegalArgumentException("时序写入缺少必填参数: target/monitorId/metricName");
        }
        if (!SqlName.isWord(target) || !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法标识符 target=" + target + " metric=" + metricName);
        }
        if (!isAvailable()) {
            throw new IllegalStateException("QuestDB 引擎不可用（未配置或已关闭），拒绝写入 target="
                    + target + " metric=" + metricName);
        }
        try {
            synchronized (writeLock) {
                Sender current = sender();
                current.table(target)
                        .symbol(TAG_MONITOR_ID, String.valueOf(monitorId))
                        .doubleColumn(metricName, value)
                        .at(timestamp, ChronoUnit.MILLIS);
                // 逐点同步刷写：失败必须抛出，绝不静默丢弃采集点
                current.flush();
            }
        } catch (RuntimeException e) {
            log.error("[SeriesEngine] QuestDB 写入失败 target={} metric={}", target, metricName, e);
            resetSender();
            throw new IllegalStateException("QuestDB 时序写入失败 target=" + target
                    + " metric=" + metricName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> metrics(String target, Long monitorId) {
        if (target == null) {
            throw new IllegalArgumentException("时序指标发现缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (!isAvailable()) {
            return List.of();
        }
        JsonNode probe = probe(target, monitorId);
        if (probe == null) {
            return List.of();
        }
        List<String> found = numericColumns(probe, true);
        Collections.sort(found);
        return found;
    }

    @Override
    public List<List<Object>> series(String target, Long monitorId, String metricName, int hours, String window) {
        if (target == null || monitorId == null) {
            throw new IllegalArgumentException("时序查询缺少必填参数: target/monitorId");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        SeriesEngine.requirePositiveHours(hours);
        if (!isAvailable()) {
            return List.of();
        }
        // 指标维度是强约束：一条曲线只能对应一个指标；未显式给出时按唯一性规则推断，
        // 推断不唯一必须报错，绝不把多个指标混成一条无意义的曲线
        String metric = metricName != null ? metricName : resolveSingleMetric(target, monitorId);
        if (metric == null) {
            return List.of();
        }
        String timestampColumn = resolveTimestampColumn(target);
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) hours * 3600_000L;
        // identifier 已白名单校验，monitorId 为 Long，无注入面
        String sql = "SELECT \"" + timestampColumn + "\", \"" + metric + "\" FROM \"" + target
                + "\" WHERE \"" + TAG_MONITOR_ID + "\" = '" + monitorId + "' AND \"" + timestampColumn
                + "\" >= '" + toIso(startMs) + "' AND \"" + timestampColumn + "\" <= '" + toIso(nowMs) + "'";
        JsonNode response = execQuery(sql, true);
        if (response == null) {
            return List.of();
        }
        List<Object[]> points = toPoints(response);
        return SeriesEngine.downsample(points, SeriesEngine.windowMillis(window), MAX_SERIES_POINTS);
    }

    @Override
    public int purge(String target, Long monitorId, String metricName, long beforeMillis) {
        if (target == null) {
            throw new IllegalArgumentException("时序清理缺少必填参数: target");
        }
        if (!SqlName.isWord(target)) {
            throw new IllegalArgumentException("非法 target: " + target);
        }
        if (metricName != null && !SqlName.isWord(metricName)) {
            throw new IllegalArgumentException("非法 metricName: " + metricName);
        }
        if (!isAvailable()) {
            return 0;
        }
        String timestampColumn = resolveTimestampColumn(target);
        StringBuilder sql = new StringBuilder("DELETE FROM \"").append(target)
                .append("\" WHERE \"").append(timestampColumn).append("\" < '").append(toIso(beforeMillis)).append('\'');
        if (monitorId != null) {
            sql.append(" AND \"").append(TAG_MONITOR_ID).append("\" = '").append(monitorId).append('\'');
        }
        JsonNode response = execQuery(sql.toString(), true);
        if (response == null) {
            // 表不存在 = 没有可清理的数据
            return 0;
        }
        // QuestDB 的 DELETE 不返回受影响行数，只能如实回报 -1（已清理、无法精确统计），
        // 绝不能返回 0——那会让调用方误以为「后端不支持清理」而放弃保留期策略
        log.info("[SeriesEngine] QuestDB 保留期清理 target={} monitor={} metric={} before={}（删除条数不可统计）",
                target, monitorId, metricName, beforeMillis);
        return -1;
    }

    /**
     * 获取（惰性创建）QuestDB Sender。
     *
     * @return Sender 实例
     * @throws IllegalStateException 引擎已关闭
     */
    private Sender sender() {
        Sender current = this.sender;
        if (current != null) {
            return current;
        }
        synchronized (writeLock) {
            // 必须在锁内复检：close() 已持同一把锁，放锁后建连会向已释放的引擎继续写入
            if (closed) {
                throw new IllegalStateException("QuestdbSeriesEngine 已关闭，拒绝建立新连接");
            }
            if (this.sender == null) {
                Sender.LineSenderBuilder builder = Sender.builder(Sender.Transport.HTTP)
                        .address(address)
                        .autoFlushRows(1);
                if (token != null) {
                    builder.httpToken(token);
                } else if (username != null) {
                    builder.httpUsernamePassword(username, password == null ? "" : password);
                }
                this.sender = builder.build();
            }
            return this.sender;
        }
    }

    /**
     * 重置已损坏的 Sender（写入失败后），下次写入时重建。
     */
    private void resetSender() {
        synchronized (writeLock) {
            Sender current = this.sender;
            this.sender = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException e) {
                    log.debug("[SeriesEngine] QuestDB Sender 失败后关闭异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 探测某 target 下某监控目标的表结构样本。
     *
     * @param target    measurement/表名
     * @param monitorId 监控目标标识，{@code null} 表示不限定目标
     * @return /exec 响应根节点；表不存在时返回 {@code null}
     */
    private JsonNode probe(String target, Long monitorId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM \"").append(target).append('"');
        if (monitorId != null) {
            sql.append(" WHERE \"").append(TAG_MONITOR_ID).append("\" = '").append(monitorId).append('\'');
        }
        sql.append(" LIMIT 1");
        return execQuery(sql.toString(), true);
    }

    /**
     * 解析本次查询唯一对应的指标名。
     *
     * <p>显式给定 {@code metricName} 时由调用方负责；未给定时读该 {@code (target, monitorId)}
     * 下实际存在的指标列，只有「恰好一个」才允许继续，无指标表示无数据返回 {@code null}，
     * 多个指标则抛 {@link IllegalArgumentException} 并列出候选。</p>
     *
     * @param target    measurement/表名
     * @param monitorId 监控目标标识
     * @return 唯一指标名；无数据时返回 {@code null}
     */
    private String resolveSingleMetric(String target, Long monitorId) {
        List<String> found = metrics(target, monitorId);
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        throw new IllegalArgumentException("target=" + target + " monitor=" + monitorId + " 下存在多个指标 "
                + found + "，无法确定要查询哪条曲线；请显式传入 metricName");
    }

    /**
     * 解析 target 的设计时间戳列名。
     *
     * <p>优先读 /exec 响应中的 {@code timestamp} 下标；读不到时退回首个 TIMESTAMP 列；
     * 仍读不到则使用 ILP 默认列名 {@code timestamp}。表不存在时直接返回默认名，
     * 让后续查询按「表不存在」的既有语义处理。</p>
     *
     * @param target measurement/表名
     * @return 设计时间戳列名
     */
    private String resolveTimestampColumn(String target) {
        JsonNode response = execQuery("SELECT * FROM \"" + target + "\" LIMIT 1", true);
        if (response == null) {
            return DEFAULT_TIMESTAMP_COLUMN;
        }
        JsonNode columns = response.get("columns");
        JsonNode designated = response.get("timestamp");
        if (columns != null && designated != null && designated.isInt()) {
            int index = designated.asInt();
            if (index >= 0 && index < columns.size()) {
                JsonNode name = columns.get(index).get("name");
                if (name != null && !name.asText().isEmpty()) {
                    return name.asText();
                }
            }
        }
        if (columns != null) {
            for (JsonNode column : columns) {
                if ("TIMESTAMP".equalsIgnoreCase(column.path("type").asText())) {
                    return column.path("name").asText(DEFAULT_TIMESTAMP_COLUMN);
                }
            }
        }
        return DEFAULT_TIMESTAMP_COLUMN;
    }

    /**
     * 从 /exec 响应中提取指标列名。
     *
     * <p>指标列必须同时满足：类型为数值类型；当 {@code rowScoped} 为 true 且存在样本行时，
     * 该行对应列值非空（避免把该监控目标从未写入的列也报成指标）。</p>
     *
     * @param response  /exec 响应根节点
     * @param rowScoped 是否按样本行收窄
     * @return 指标列名列表
     */
    private List<String> numericColumns(JsonNode response, boolean rowScoped) {
        List<String> result = new ArrayList<>();
        JsonNode columns = response.get("columns");
        if (columns == null) {
            return result;
        }
        JsonNode dataset = response.get("dataset");
        JsonNode sampleRow = dataset != null && dataset.isArray() && dataset.size() > 0 ? dataset.get(0) : null;
        for (int i = 0; i < columns.size(); i++) {
            JsonNode column = columns.get(i);
            if (!NUMERIC_TYPES.contains(column.path("type").asText().toUpperCase())) {
                continue;
            }
            if (rowScoped && sampleRow != null && sampleRow.get(i) != null && sampleRow.get(i).isNull()) {
                continue;
            }
            JsonNode name = column.get("name");
            if (name != null && !name.asText().isEmpty()) {
                result.add(name.asText());
            }
        }
        return result;
    }

    /**
     * 将 /exec 查询结果的 {@code [时间戳, 指标值]} 两列数据集展平为点列表。
     *
     * @param response /exec 响应根节点
     * @return 点列表
     */
    private List<Object[]> toPoints(JsonNode response) {
        List<Object[]> points = new ArrayList<>();
        JsonNode dataset = response.get("dataset");
        if (dataset == null || !dataset.isArray()) {
            return points;
        }
        int dropped = 0;
        for (JsonNode row : dataset) {
            Long ts = row.size() > 0 ? toEpochMillis(row.get(0)) : null;
            JsonNode value = row.size() > 1 ? row.get(1) : null;
            if (ts != null && value != null && value.isNumber()) {
                points.add(new Object[]{ts, value.doubleValue()});
            } else {
                dropped++;
            }
        }
        // 坏点被静默丢弃会让曲线残缺却看起来像「没数据」，必须留下可观测痕迹
        if (dropped > 0) {
            log.warn("[SeriesEngine] 丢弃无法解析的 QuestDB 点 target={} 丢弃={} 保留={}",
                    response.path("query").asText(""), dropped, points.size());
        }
        return points;
    }

    /**
     * 执行 /exec 查询并解析 JSON。
     *
     * @param sql                  SQL 语句（identifiers 已白名单校验，数值参数为 Long）
     * @param tolerantMissingTable 表不存在时是否按无数据处理（返回 null）而非抛异常
     * @return 响应根节点；{@code tolerantMissingTable} 且表不存在时返回 null
     * @throws IllegalStateException 网络失败或后端返回错误
     */
    private JsonNode execQuery(String sql, boolean tolerantMissingTable) {
        String uri = baseUrl + EXEC_PATH + URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = parseJson(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (root != null && root.hasNonNull("error")) {
                    String error = root.get("error").asText();
                    if (tolerantMissingTable && error.contains(TABLE_NOT_EXIST)) {
                        return null;
                    }
                    throw new IllegalStateException("QuestDB 查询错误: " + error);
                }
                return root;
            }
            String error = root != null && root.hasNonNull("error")
                    ? root.get("error").asText()
                    : "HTTP " + response.statusCode();
            if (tolerantMissingTable && error.contains(TABLE_NOT_EXIST)) {
                return null;
            }
            throw new IllegalStateException("QuestDB 查询失败(HTTP " + response.statusCode() + "): " + error);
        } catch (IOException e) {
            log.warn("[SeriesEngine] QuestDB 请求失败: {}", e.getMessage());
            throw new IllegalStateException("QuestDB 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QuestDB 请求被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 JSON 文本，空响应返回 null。
     *
     * @param body 响应体
     * @return JSON 根节点，空或非 JSON 时返回 null
     * @throws IOException 解析失败
     */
    private JsonNode parseJson(String body) throws IOException {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    /**
     * 将时间单元格转换为毫秒时间戳。
     *
     * <p>/exec 的 TIMESTAMP 列以 ISO-8601 字符串返回；个别版本可能返回 epoch 微秒数字，
     * 此处一并兼容。</p>
     *
     * @param cell 单元格
     * @return 毫秒时间戳，无法识别返回 {@code null}
     */
    private Long toEpochMillis(JsonNode cell) {
        if (cell == null) {
            return null;
        }
        if (cell.isTextual()) {
            try {
                return Instant.parse(cell.asText()).toEpochMilli();
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (cell.isNumber()) {
            return cell.asLong() / 1000L;
        }
        return null;
    }

    /**
     * 关闭底层资源：先标记关闭态，再释放 Sender 与 HttpClient。
     */
    @Override
    public void close() {
        closed = true;
        resetSender();
        try {
            httpClient.close();
        } catch (RuntimeException e) {
            log.warn("[SeriesEngine] QuestDB HttpClient 关闭失败: {}", e.getMessage());
        }
    }

    /**
     * 归一化基础地址：补默认 scheme、去除末尾斜杠。
     *
     * @param url 原始地址
     * @return 归一化地址，为空时返回 null
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String normalized = url.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 从基础地址解析出 Sender 需要的 {@code host:port}。
     *
     * @param url 归一化后的基础地址
     * @return host:port，解析失败返回 null
     */
    private static String toAddress(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_HTTP_PORT;
            return host + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 将毫秒时间戳格式化为 QuestDB 可解析的 ISO-8601 字符串。
     *
     * @param millis 毫秒时间戳
     * @return ISO-8601 UTC 字符串
     */
    private static String toIso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    /**
     * 空串转 null，便于后续判空。
     *
     * @param value 原始值
     * @return 非空原值或 null
     */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
