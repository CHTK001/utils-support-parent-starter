package com.chua.skywalking.support.engine;

import com.chua.common.support.apm.OtnlEngine;
import com.chua.common.support.apm.OtnlMetricQuery;
import com.chua.common.support.apm.OtnlQuery;
import com.chua.common.support.apm.OtnlScope;
import com.chua.common.support.apm.OtnlTag;
import com.chua.common.support.apm.OtnlTraceState;
import com.chua.common.support.apm.model.OtnlAlarm;
import com.chua.common.support.apm.model.OtnlEndpoint;
import com.chua.common.support.apm.model.OtnlHealth;
import com.chua.common.support.apm.model.OtnlInstance;
import com.chua.common.support.apm.model.OtnlLogRecord;
import com.chua.common.support.apm.model.OtnlMetricDefinition;
import com.chua.common.support.apm.model.OtnlMetricSeries;
import com.chua.common.support.apm.model.OtnlProfileAnalysis;
import com.chua.common.support.apm.model.OtnlProfileTask;
import com.chua.common.support.apm.model.OtnlProfiledSegment;
import com.chua.common.support.apm.model.OtnlService;
import com.chua.common.support.apm.model.OtnlSpan;
import com.chua.common.support.apm.model.OtnlStatus;
import com.chua.common.support.apm.model.OtnlTopology;
import com.chua.common.support.apm.model.OtnlTrace;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.skywalking.support.client.SkywalkingOapClient;
import com.chua.skywalking.support.client.SkywalkingQueries;
import com.chua.skywalking.support.client.SkywalkingSettings;
import com.chua.skywalking.support.client.SkywalkingTimes;
import com.chua.skywalking.support.client.SkywalkingVersion;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Apache SkyWalking 的 {@link OtnlEngine} 实现（SPI 名 {@code skywalking}）。
 * <p>
 * 数据面全部来自 OAP 的 GraphQL 查询，语义与 SkyWalking 官方 UI 对齐：
 * </p>
 * <ul>
 *   <li>服务 / 实例 / 端点：metadata v2，自动回落 v1；</li>
 *   <li>链路：{@code queryBasicTraces} + {@code queryTrace}；</li>
 *   <li>指标：metrics-v2 {@code readMetricsValue(s)}，实体作用域按 {@link OtnlScope} 映射；</li>
 *   <li>拓扑：{@code getGlobalTopology} / {@code getServiceTopology(ByName)}，
 *       边上调用量与成功率用关系指标补齐；</li>
 *   <li>告警 / 日志：{@code getAlarm} / {@code queryLogs}。</li>
 * </ul>
 * <h3>降级约定</h3>
 * <p>
 * 任何后端异常都在方法内吞掉并返回空集合，原因通过 {@link #status()} 暴露。
 * 列表页的聚合列（调用量 / 平均耗时 / P99 / 错误率）需要逐实体查询，
 * 因此只为前 {@value #ENRICH_LIMIT} 条做补齐，避免一个大页面打几百个请求。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi("skywalking")
public class SkywalkingOtnlEngine implements OtnlEngine {

    /**
     * 列表页做实体会话补齐的最大条数
     */
    private static final int ENRICH_LIMIT = 20;

    /**
     * 拓扑边补齐指标的最大条数
     */
    private static final int TOPOLOGY_ENRICH_LIMIT = 40;

    /**
     * 服务目录缓存有效期（毫秒）
     */
    private static final long SERVICE_CACHE_TTL = 30_000L;

    /**
     * 状态探测缓存有效期（毫秒）：可用成功状态
     */
    private static final long STATUS_OK_TTL = 15_000L;

    /**
     * 状态探测缓存有效期（毫秒）：失败状态，避免前端轮询把 OAP 打穿
     */
    private static final long STATUS_FAIL_TTL = 5_000L;

    /**
     * SLA 指标满值（SkyWalking 用 10000 表示 100%）
     */
    private static final double SLA_FULL = 10_000D;

    /**
     * OAP {@code Entity} 的 normal 标记。
     * <p>
     * 真实 OAP 9.7.0 上：不带该字段（或 false）时，指标查询命中的是「非正常实体」，
     * 服务/端点指标一律返回 0 且不报错；带 {@code normal=true} 才返回真实值。
     * </p>
     */
    private static final String NORMAL = "normal";

    /**
     * 请求补齐时的并发度
     */
    private static final int ENRICH_PARALLELISM = 4;

    /**
     * 未知值占位（引擎不支持或未查询）
     */
    private static final double UNKNOWN = -1D;

    /**
     * 一次剖析分析最多提交的片段数（OAP 端逐段读磁盘，过多会拖垮查询）
     */
    private static final int PROFILE_ANALYZE_SEGMENTS = 10;

    /**
     * {@code readMetricsValues} 单次查询的窗口跨度上限（以步长计）。
     * <p>
     * OAP 侧是硬限制：超过就整条查询失败（不是截断）。实测真实 OAP 9.7.0：
     * 跨度正好 500 个步长可以，501 个就报
     * {@code Duration data error, the range between the start time and the end time can't exceed 500 minutes}；
     * SECOND / MINUTE / HOUR / DAY 表现一致 —— 限制的是<b>步长数</b>而非墙钟时间，
     * 报错文案里的「500 minutes」只是以分钟步长举例。
     * </p>
     * <p>
     * 注意点数是「跨度 + 1」，所以 500 个步长对应 501 个数据点。
     * 见 {@link #metricChunks(long, long, String)}。
     * </p>
     */
    private static final int METRIC_MAX_POINTS = 500;

    private volatile SkywalkingOapClient client;
    private volatile ExecutorService executor;

    /**
     * 服务目录缓存：名称 → ID
     */
    private volatile Map<String, String> serviceIds = Map.of();
    /**
     * 服务目录缓存：ID → 名称
     */
    private volatile Map<String, String> serviceNames = Map.of();
    private volatile long serviceCacheExpireAt;

    private volatile OtnlStatus cachedStatus;
    private volatile long statusExpireAt;

    /**
     * 解析后的 OAP 版本（能力判断用），与 {@link #cachedStatus} 同步刷新
     */
    private volatile SkywalkingVersion oapVersion = SkywalkingVersion.UNKNOWN;

    /**
     * 当前 OAP 版本能力。
     * <p>
     * 版本未知时按最低能力处理：宁可少发一个 11.x 专有查询（走 legacy 语句），
     * 也不要在老 OAP 上触发一串 GraphQL 校验错误。
     * </p>
     *
     * @return 版本能力
     */
    private SkywalkingVersion version() {
        if (oapVersion == SkywalkingVersion.UNKNOWN) {
            // 触发一次探测：status() 会把版本一并解析出来
            status();
        }
        return oapVersion;
    }

    @Override
    public String name() {
        return "skywalking";
    }

    @Override
    public List<String> capabilities() {
        // SkyWalking 9.x 起提供 Profile 协议；老版本调用失败只会返回空列表，不影响其它能力
        return List.of(CAP_SERVICES, CAP_INSTANCES, CAP_ENDPOINTS, CAP_TRACES, CAP_TOPOLOGY,
                CAP_METRICS, CAP_RUNTIME, CAP_ALARMS, CAP_ALERTS, CAP_LOGS, CAP_PROFILES);
    }

    @Override
    public OtnlStatus status() {
        long now = System.currentTimeMillis();
        OtnlStatus cached = cachedStatus;
        if (cached != null && now < statusExpireAt) {
            return cached;
        }
        if (!SkywalkingSettings.enabled()) {
            OtnlStatus disabled = new OtnlStatus(name(), "unknown", false, false,
                    SkywalkingSettings.url(), "remote", List.of(),
                    "SkyWalking 已通过配置关闭（skywalking.enabled=false）", now);
            cachedStatus = disabled;
            statusExpireAt = now + STATUS_FAIL_TTL;
            return disabled;
        }
        OtnlStatus fresh;
        try {
            String version = client().version();
            oapVersion = SkywalkingVersion.parse(version);
            fresh = OtnlStatus.available(name(), version, SkywalkingSettings.url(), "remote", capabilities());
            statusExpireAt = now + STATUS_OK_TTL;
        } catch (Exception e) {
            oapVersion = SkywalkingVersion.UNKNOWN;
            fresh = OtnlStatus.unavailable(name(), "SkyWalking OAP 不可达: " + rootMessage(e));
            statusExpireAt = now + STATUS_FAIL_TTL;
        }
        cachedStatus = fresh;
        return fresh;
    }

    @Override
    public List<OtnlService> services(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            List<ServiceRef> refs = listServiceRefs(window);
            List<OtnlService> result = new ArrayList<>(refs.size());
            for (ServiceRef ref : refs) {
                result.add(new OtnlService(ref.id, ref.name, ref.group, ref.layers,
                        0, 0, UNKNOWN_LONG, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, List.of()));
            }
            return enrichServices(result, window);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询服务列表失败: {}", rootMessage(e));
            return List.of();
        }
    }

    @Override
    public List<OtnlInstance> instances(String serviceName, OtnlQuery query) {
        Window window = Window.of(query);
        try {
            String serviceId = serviceId(serviceName);
            if (serviceId == null) {
                return List.of();
            }
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("duration", SkywalkingTimes.duration(window.start, window.end, "MINUTE"));
            vars.put("serviceId", serviceId);
            JsonNode data = queryWithFallback(SkywalkingQueries.INSTANCES, SkywalkingQueries.INSTANCES_LEGACY, vars);
            if (data == null) {
                return List.of();
            }
            List<OtnlInstance> result = new ArrayList<>();
            for (JsonNode node : data.path("listInstances").isMissingNode()
                    ? data.path("getServiceInstances") : data.path("listInstances")) {
                List<OtnlTag> attributes = new ArrayList<>();
                for (JsonNode attr : node.path("attributes")) {
                    attributes.add(OtnlTag.of(attr.path("name").asText(""), attr.path("value").asText("")));
                }
                result.add(new OtnlInstance(node.path("id").asText(""), serviceName,
                        node.path("name").asText(""), node.path("language").asText("UNKNOWN"),
                        0L, attributes));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询实例失败({}): {}", serviceName, rootMessage(e));
            return List.of();
        }
    }

    @Override
    public List<OtnlEndpoint> endpoints(String serviceName, OtnlQuery query) {
        Window window = Window.of(query);
        try {
            String serviceId = serviceId(serviceName);
            if (serviceId == null) {
                return List.of();
            }
            int limit = Math.max(20, Math.min(query.pageSizeOrTwenty() * 5, 200));
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("serviceId", serviceId);
            vars.put("keyword", query.keyword() == null ? "" : query.keyword());
            vars.put("limit", limit);
            JsonNode data = queryWithFallback(SkywalkingQueries.ENDPOINTS, SkywalkingQueries.ENDPOINTS_LEGACY, vars);
            if (data == null) {
                return List.of();
            }
            JsonNode array = data.has("findEndpoint") ? data.path("findEndpoint") : data.path("searchEndpoint");
            List<OtnlEndpoint> result = new ArrayList<>();
            for (JsonNode node : array) {
                result.add(new OtnlEndpoint(node.path("id").asText(""), serviceName,
                        node.path("name").asText(""), UNKNOWN_LONG, UNKNOWN, UNKNOWN, UNKNOWN,
                        UNKNOWN, List.of()));
            }
            return enrichEndpoints(result, serviceName, window);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询端点失败({}): {}", serviceName, rootMessage(e));
            return List.of();
        }
    }

    @Override
    public List<OtnlTrace> traces(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            Map<String, Object> condition = new LinkedHashMap<>(8);
            if (query.serviceName() != null && !query.serviceName().isBlank()) {
                String serviceId = serviceId(query.serviceName());
                if (serviceId == null) {
                    return List.of();
                }
                condition.put("serviceId", serviceId);
            }
            if (query.traceId() != null && !query.traceId().isBlank()) {
                condition.put("traceId", query.traceId());
            }
            condition.put("queryDuration", SkywalkingTimes.duration(window.start, window.end, "MINUTE"));
            condition.put("traceState", switch (query.stateOrAll()) {
                case SUCCESS -> "SUCCESS";
                case ERROR -> "ERROR";
                default -> "ALL";
            });
            condition.put("queryOrder", query.orderByDuration() ? "BY_DURATION" : "BY_START_TIME");
            Map<String, Object> paging = new LinkedHashMap<>(2);
            paging.put("pageNum", query.pageNumOrOne());
            paging.put("pageSize", query.pageSizeOrTwenty());
            condition.put("paging", paging);

            JsonNode data = client().data(SkywalkingQueries.BASIC_TRACES, Map.of("condition", condition));
            List<OtnlTrace> result = new ArrayList<>();
            for (JsonNode node : data.path("queryBasicTraces").path("traces")) {
                List<String> endpointNames = textList(node.path("endpointNames"));
                List<String> traceIds = textList(node.path("traceIds"));
                String traceId = traceIds.isEmpty() ? node.path("segmentId").asText("") : traceIds.get(0);
                long start = startTime(node);
                long duration = node.path("duration").asLong(0L);
                result.add(OtnlTrace.summary(traceId,
                        query.serviceName(),
                        endpointNames.isEmpty() ? null : endpointNames.get(0),
                        duration, start, start + duration,
                        node.path("isError").asBoolean(false), 0));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询链路列表失败: {}", rootMessage(e));
            return List.of();
        }
    }

    @Override
    public OtnlTrace trace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try {
            JsonNode data = client().data(SkywalkingQueries.TRACE, Map.of("traceId", traceId));
            List<OtnlSpan> spans = new ArrayList<>();
            String serviceName = null;
            String endpointName = null;
            long start = 0L;
            long end = 0L;
            boolean error = false;
            for (JsonNode node : data.path("queryTrace").path("spans")) {
                String spanService = decodeServiceName(node.path("serviceCode").asText(""));
                long spanStart = node.path("startTime").asLong(0L);
                long spanEnd = node.path("endTime").asLong(0L);
                boolean spanError = node.path("isError").asBoolean(false);
                error = error || spanError;
                start = start == 0L ? spanStart : Math.min(start, spanStart);
                end = Math.max(end, spanEnd);
                String parent = node.hasNonNull("parentSpanId") && node.path("parentSpanId").asLong(-1) > 0
                        ? String.valueOf(node.path("parentSpanId").asLong()) : null;
                String spanEndpoint = node.path("endpointName").asText("");
                if (parent == null) {
                    serviceName = serviceName == null ? spanService : serviceName;
                    endpointName = endpointName == null ? spanEndpoint : endpointName;
                }
                List<OtnlTag> tags = new ArrayList<>();
                for (JsonNode tag : node.path("tags")) {
                    tags.add(OtnlTag.of(tag.path("key").asText(""), tag.path("value").asText("")));
                }
                List<OtnlSpan.OtnlSpanLog> logs = new ArrayList<>();
                for (JsonNode logNode : node.path("logs")) {
                    List<OtnlTag> logTags = new ArrayList<>();
                    for (JsonNode tag : logNode.path("data")) {
                        logTags.add(OtnlTag.of(tag.path("key").asText(""), tag.path("value").asText("")));
                    }
                    logs.add(new OtnlSpan.OtnlSpanLog(logNode.path("time").asLong(0L), logTags));
                }
                spans.add(new OtnlSpan(traceId, node.path("segmentId").asText(""),
                        String.valueOf(node.path("spanId").asLong(0L)), parent,
                        spanService, node.path("serviceInstanceName").asText(""),
                        spanEndpoint, node.path("layer").asText(""), node.path("type").asText(""),
                        node.path("component").asText(""), node.path("peer").asText(""),
                        spanStart, spanEnd, Math.max(0L, spanEnd - spanStart), spanError, tags, logs));
            }
            if (spans.isEmpty()) {
                return null;
            }
            return new OtnlTrace(traceId, serviceName, endpointName, Math.max(0L, end - start),
                    start, end, error, spans.size(), spans);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询链路详情失败({}): {}", traceId, rootMessage(e));
            return null;
        }
    }

    @Override
    public OtnlTopology topology(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            Map<String, Object> duration = SkywalkingTimes.duration(window.start, window.end, "MINUTE");
            SkywalkingVersion version = version();
            JsonNode topology;
            if (query.serviceName() == null || query.serviceName().isBlank()) {
                // Node.layers 是 10.0.0 才有的字段，9.x 选了它整页拓扑就没数据
                String statement = version.supportsNodeLayers()
                        ? SkywalkingQueries.GLOBAL_TOPOLOGY
                        : SkywalkingQueries.GLOBAL_TOPOLOGY_LEGACY;
                topology = client().data(statement, Map.of("duration", duration))
                        .path("getGlobalTopology");
            } else {
                topology = null;
                if (version.supportsTopologyByName()) {
                    Map<String, Object> vars = new LinkedHashMap<>(4);
                    vars.put("service", Map.of("serviceName", query.serviceName()));
                    vars.put("duration", duration);
                    topology = client().data(SkywalkingQueries.SERVICE_TOPOLOGY_BY_NAME, vars)
                            .path("getServiceTopologyByName");
                }
                if (topology == null || topology.isMissingNode()) {
                    // 10.x 及更早只能按 serviceId 查
                    String serviceId = serviceId(query.serviceName());
                    if (serviceId == null) {
                        return OtnlTopology.empty();
                    }
                    String statement = version.supportsNodeLayers()
                            ? SkywalkingQueries.SERVICE_TOPOLOGY_BY_ID
                            : SkywalkingQueries.SERVICE_TOPOLOGY_BY_ID_LEGACY;
                    Map<String, Object> vars = new LinkedHashMap<>(4);
                    vars.put("serviceId", serviceId);
                    vars.put("duration", duration);
                    topology = client().data(statement, vars).path("getServiceTopology");
                }
            }
            if (topology == null || topology.isMissingNode()) {
                return OtnlTopology.empty();
            }
            return readTopology(topology, query.serviceName(), window);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询拓扑失败: {}", rootMessage(e));
            return OtnlTopology.empty();
        }
    }

    /**
     * 解析拓扑 JSON（全局 / 单服务 / 多服务共用）
     *
     * @param topology    拓扑节点
     * @param selfService 当前服务名（用于标记 isSelf），可为空
     * @param window      时间窗口
     * @return 拓扑
     */
    private OtnlTopology readTopology(JsonNode topology, String selfService, Window window) {
        Map<String, String> nameById = new LinkedHashMap<>();
        List<OtnlTopology.OtnlTopologyNode> nodes = new ArrayList<>();
        for (JsonNode node : topology.path("nodes")) {
            String id = node.path("id").asText("");
            String nodeName = node.path("name").asText("");
            boolean real = node.path("isReal").asBoolean(true);
            if (real) {
                // 只把「真实服务」放进映射：User 等虚拟节点没有 serviceName 实体，
                // 拿它去查关系指标只会得到 0，进而把边渲染成「0 次调用 / 100% 错误」。
                nameById.put(id, nodeName);
            }
            List<String> layers = textList(node.path("layers"));
            nodes.add(new OtnlTopology.OtnlTopologyNode(id, nodeName,
                    node.path("type").asText(""), node.path("isReal").asBoolean(true),
                    selfService != null && nodeName.equals(selfService),
                    layers.isEmpty() ? null : layers.get(0)));
        }
        List<OtnlTopology.OtnlTopologyCall> calls = new ArrayList<>();
        for (JsonNode node : topology.path("calls")) {
            List<String> points = textList(node.path("detectPoints"));
            calls.add(new OtnlTopology.OtnlTopologyCall(node.path("id").asText(""),
                    node.path("source").asText(""), node.path("target").asText(""),
                    points.isEmpty() ? "CLIENT" : points.get(0),
                    UNKNOWN_LONG, UNKNOWN, UNKNOWN));
        }
        return new OtnlTopology(nodes, enrichTopologyCalls(calls, nameById, window));
    }

    @Override
    public OtnlMetricSeries metrics(OtnlMetricQuery query) {
        if (query == null || query.name() == null || query.name().isBlank()) {
            return OtnlMetricSeries.empty(null, OtnlScope.SERVICE, null);
        }
        OtnlScope scope = query.scopeOrService();
        long start = query.startTimeOrWindow();
        long end = query.endTimeOrNow();
        String step = query.stepOrMinute();
        String label = switch (scope) {
            case ENDPOINT -> query.endpointName();
            case SERVICE_INSTANCE -> query.instanceName();
            case SERVICE, SERVICE_RELATION, ENDPOINT_RELATION -> query.serviceName();
            case ALL -> null;
        };
        try {
            Map<String, Object> entity = new LinkedHashMap<>(4);
            switch (scope) {
                case SERVICE, SERVICE_RELATION -> entity.put("serviceName", query.serviceName());
                case SERVICE_INSTANCE -> {
                    entity.put("serviceName", query.serviceName());
                    entity.put("serviceInstanceName", query.instanceName());
                }
                case ENDPOINT, ENDPOINT_RELATION -> {
                    entity.put("serviceName", query.serviceName());
                    entity.put("endpointName", query.endpointName());
                }
                default -> entity.put("scope", "All");
            }
            // OAP 的 Entity 默认取 normal=false（即"非正常实体"），必须显式 normal=true
            // 才会命中真实服务/端点的指标；缺这个字段不会报错，只会安静地返回 0，
            // 表现是"服务列表调用数全 0、错误率恒 100%"。已在真实 OAP 9.7.0 上复现。
            if (scope != OtnlScope.ALL) {
                entity.put("normal", true);
            }
            Map<String, Object> condition = new LinkedHashMap<>(2);
            condition.put("name", query.name());
            condition.put("entity", entity);

            String actualStep = SkywalkingTimes.normalizeStep(step, start, end);
            // OAP 对单次 readMetricsValues 有点数硬上限，超了就整条报错（见 METRIC_MAX_POINTS），
            // 因此按窗口切片查询再合并，而不是把超长窗口直接发出去
            Map<Long, Double> byTime = new LinkedHashMap<>();
            for (long[] chunk : metricChunks(start, end, actualStep)) {
                Map<String, Object> vars = new LinkedHashMap<>(4);
                vars.put("condition", condition);
                vars.put("duration", SkywalkingTimes.duration(chunk[0], chunk[1], actualStep));
                JsonNode data = client().data(SkywalkingQueries.METRICS_VALUES, vars);
                for (JsonNode point : data.path("readMetricsValues").path("values").path("values")) {
                    long time = SkywalkingTimes.parse(point.path("id").asText(""), actualStep);
                    if (time > 0) {
                        // 相邻切片在边界上可能重叠一个点，取后查到的值覆盖
                        byTime.put(time, point.path("value").asDouble(0D));
                    }
                }
            }
            List<OtnlMetricSeries.OtnlMetricPoint> points = new ArrayList<>(byTime.size());
            byTime.forEach((time, value) -> points.add(new OtnlMetricSeries.OtnlMetricPoint(time, value)));
            points.sort(Comparator.comparingLong(OtnlMetricSeries.OtnlMetricPoint::time));
            return new OtnlMetricSeries(query.name(), scope, label, unitOf(query.name(), scope), points);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询指标失败({}): {}", query.name(), rootMessage(e));
            return OtnlMetricSeries.empty(query.name(), scope, label);
        }
    }

    /**
     * 把时间窗口切成若干个「点数不超上限」的片段。
     * <p>
     * <b>为什么必须切。</b>OAP 的 {@code readMetricsValues} 对单次查询的点数有硬上限，
     * 超出时返回的是
     * {@code Duration data error, the range between the start time and the end time can't exceed 500 minutes}，
     * 并让整条查询失败 —— 拿不到任何数据点，不是「只截断到 500 个」。而
     * {@code readMetricsValue}（单值，列表页聚合用）没有这个限制。
     * </p>
     * <p>
     * 实测（真实 OAP 9.7.0）：跨度正好 500 个步长可以，501 个就报错，
     * SECOND / MINUTE / HOUR / DAY 四种步长表现一致 —— 上限是<b>点数</b>，不是墙钟时间，
     * 报错文案里的「500 minutes」只是以分钟步长举例。
     * </p>
     * <p>
     * 前端的「最近 24 小时 + 分钟级」就是 1440 个点，必然超过上限，这是最常见的触发场景。
     * </p>
     *
     * @param start 起始毫秒
     * @param end   结束毫秒
     * @param step  步长
     * @return 片段数组，每项为 {@code [start, end]}；窗口不超限时只有一段
     */
    private List<long[]> metricChunks(long start, long end, String step) {
        long stepMillis = SkywalkingTimes.stepMillis(step);
        if (stepMillis <= 0 || end <= start) {
            return List.of(new long[]{start, end});
        }
        long maxSpan = METRIC_MAX_POINTS * stepMillis;
        if (end - start <= maxSpan) {
            return List.of(new long[]{start, end});
        }
        List<long[]> chunks = new ArrayList<>();
        long cursor = start;
        while (true) {
            // 每段跨度不超过 maxSpan
            long chunkEnd = Math.min(cursor + maxSpan, end);
            chunks.add(new long[]{cursor, chunkEnd});
            if (chunkEnd >= end) {
                break;
            }
            // 下一段从上一段结束后的一个步长开始：边界点已由上一段取到，不重复
            cursor = chunkEnd + stepMillis;
            if (cursor > end) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 批量指标时序（多实体对比）
     * <p>
     * <b>没有可用的批量入口。</b>OAP 的 metrics-v2 里 {@code readMetricsValues}
     * 只接受单个 {@code condition}，<b>不存在</b> {@code conditions: [...]} 这种复数签名
     * （9.7 / 10.2 / 11.0 各版本都确认过）。因此这里不做「先发一次批量请求、
     * 失败再逐条重试」——那次批量请求注定失败，只会给 OAP 日志灌一条假错误。
     * </p>
     * <p>
     * 直接走 {@link OtnlEngine#metricsBatch} 的默认实现：逐条查询，单条失败只丢那一条，
     * 对比页不会因为某个实例刚下线就整张图空白。
     * </p>
     *
     * @param queries 查询列表
     * @return 时序列表，与入参顺序一致
     */
    @Override
    public List<OtnlMetricSeries> metricsBatch(List<OtnlMetricQuery> queries) {
        return OtnlEngine.super.metricsBatch(queries);
    }

    /**
     * 曲线展示名
     *
     * @param query 查询条件
     * @param scope 作用域
     * @return 标签
     */
    private String scopeLabel(OtnlMetricQuery query, OtnlScope scope) {
        return switch (scope) {
            case ENDPOINT -> query.endpointName();
            case SERVICE_INSTANCE -> query.instanceName();
            case SERVICE, SERVICE_RELATION, ENDPOINT_RELATION -> query.serviceName();
            case ALL -> null;
        };
    }

    /**
     * 多服务拓扑（一次请求拿多个服务的关系，供对比页使用）
     *
     * @param serviceNames 服务名列表
     * @param query        时间窗口
     * @return 合并拓扑
     */
    @Override
    public OtnlTopology multiTopology(List<String> serviceNames, OtnlQuery query) {
        if (serviceNames == null || serviceNames.isEmpty()) {
            return OtnlTopology.empty();
        }
        OtnlQuery safe = query == null ? OtnlQuery.empty() : query;
        if (!version().supportsTopologyByName()) {
            // 11.0.0 之前没有多服务拓扑入口，只能逐个服务查再合并
            return OtnlEngine.super.multiTopology(serviceNames, safe);
        }
        Window window = Window.of(safe);
        try {
            List<Map<String, Object>> services = new ArrayList<>(serviceNames.size());
            for (String name : serviceNames) {
                if (name != null && !name.isBlank()) {
                    services.add(Map.of("serviceName", name));
                }
            }
            if (services.isEmpty()) {
                return OtnlTopology.empty();
            }
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("services", services);
            vars.put("duration", SkywalkingTimes.duration(window.start, window.end, "MINUTE"));
            JsonNode topology = client().data(SkywalkingQueries.SERVICES_TOPOLOGY_BY_NAMES, vars)
                    .path("getServicesTopologyByNames");
            if (topology.isMissingNode() || topology.isNull()) {
                return OtnlEngine.super.multiTopology(serviceNames, safe);
            }
            return readTopology(topology, null, window);
        } catch (Exception e) {
            log.debug("[SkyWalking] 多服务拓扑查询失败，逐个重试: {}", rootMessage(e));
            return OtnlEngine.super.multiTopology(serviceNames, safe);
        }
    }

    @Override
    public List<OtnlAlarm> alarms(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            if (version().supportsQueryAlarms()) {
                return alarmsV2(query, window);
            }
            return alarmsLegacy(query, window);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询告警失败: {}", rootMessage(e));
            return List.of();
        }
    }

    /**
     * 新版告警查询（11.0.0 的 queryAlarms）：支持实体、规则名、层过滤
     *
     * @param query  查询条件
     * @param window 时间窗口
     * @return 告警列表
     */
    private List<OtnlAlarm> alarmsV2(OtnlQuery query, Window window) {
        Map<String, Object> condition = new LinkedHashMap<>(8);
        condition.put("duration", SkywalkingTimes.duration(window.start, window.end, query.step()));
        condition.put("paging", paging(query));
        if (query.serviceName() != null && !query.serviceName().isBlank()) {
            condition.put("entities", List.of(Map.of("serviceName", query.serviceName(), NORMAL, true)));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            condition.put("keyword", query.keyword());
        }
        JsonNode data = client().data(SkywalkingQueries.ALARMS_V2, Map.of("condition", condition));
        return readAlarms(data.path("queryAlarms").path("msgs"));
    }

    /**
     * 老版告警查询（getAlarm，9.x ~ 10.x 唯一入口）
     * <p>
     * {@code scope} 传 null 而不是写死 {@code Service}：写死会把实例 / 端点作用域的
     * 告警全部过滤掉，而前端恰恰要按实体聚合。
     * </p>
     *
     * @param query  查询条件
     * @param window 时间窗口
     * @return 告警列表
     */
    private List<OtnlAlarm> alarmsLegacy(OtnlQuery query, Window window) {
        Map<String, Object> vars = new LinkedHashMap<>(4);
        vars.put("duration", SkywalkingTimes.duration(window.start, window.end, query.step()));
        vars.put("scope", null);
        vars.put("keyword", query.keyword() == null ? "" : query.keyword());
        vars.put("paging", paging(query));
        JsonNode data = client().data(SkywalkingQueries.ALARMS, vars);
        return readAlarms(data.path("getAlarm").path("msgs"));
    }

    /**
     * 分页变量
     *
     * @param query 查询条件
     * @return 分页 Map
     */
    private Map<String, Object> paging(OtnlQuery query) {
        Map<String, Object> paging = new LinkedHashMap<>(2);
        paging.put("pageNum", query.pageNumOrOne());
        paging.put("pageSize", query.pageSizeOrTwenty());
        return paging;
    }

    /**
     * 解析告警消息数组（{@code getAlarm} 与 {@code queryAlarms} 返回结构相同）
     * <p>
     * 字段语义以 OAP 的 {@code RunningRule#buildAlarmMessage} 为准，别按字面猜：
     * </p>
     * <ul>
     *   <li>{@code name} —— <b>触发告警的实体名</b>，写的是 {@code AlarmEntity#getName()}：
     *       Service 作用域就是服务名，Instance 是「实例 of 服务」，Endpoint 是「端点 in 服务」。
     *       注意这个字段 <b>10.2.0 才进 schema</b>，老版本查不到，只能从事件来源反推；</li>
     *   <li>{@code message} —— 规则模板格式化后的文案（如
     *       {@code Successful rate of endpoint X is lower than 75%}）；</li>
     *   <li>{@code tags} —— <b>规则上自定义的标签</b>（alarm-settings.yml 的 {@code tags:}），
     *       不是实体标签，因此不能拿来取服务名；</li>
     *   <li>{@code events} —— {@code [Event!]!}，每项有 name / message / type / source。</li>
     * </ul>
     * <p>
     * {@code ruleName} 在 query-protocol 的 {@code AlarmMessage} 里<b>没有暴露</b>：
     * 存储层有 rule_name 列，但它只作为 {@code queryAlarms} 的 {@code ruleNames}
     * <b>过滤条件</b>可用，查询结果里拿不回来。所以这里留空 —— 宁可前端回落，
     * 也不把实体名冒充规则名。
     * </p>
     *
     * @param msgs 消息数组
     * @return 告警列表
     */
    private List<OtnlAlarm> readAlarms(JsonNode msgs) {
        List<OtnlAlarm> result = new ArrayList<>();
        for (JsonNode node : msgs) {
            List<OtnlTag> tags = new ArrayList<>();
            for (JsonNode tag : node.path("tags")) {
                tags.add(OtnlTag.of(tag.path("key").asText(""), tag.path("value").asText("")));
            }
            List<String> events = new ArrayList<>();
            String eventService = null;
            String eventInstance = null;
            for (JsonNode event : node.path("events")) {
                String name = event.path("name").asText("");
                String text = event.path("message").asText("");
                String label = name.isEmpty() ? text : (text.isEmpty() ? name : name + ": " + text);
                if (!label.isEmpty()) {
                    events.add(label);
                }
                if (eventService == null) {
                    JsonNode source = event.path("source");
                    String service = source.path("service").asText("");
                    if (!service.isEmpty()) {
                        eventService = service;
                        String instance = source.path("serviceInstance").asText("");
                        eventInstance = instance.isEmpty() ? null : instance;
                    }
                }
            }
            // 10.2.0 起直接给实体名；更早的版本靠事件来源拼：
            // 实例作用域拼成「实例 of 服务」，与服务作用域的口径保持一致。
            String entity = node.path("name").asText("");
            if (entity.isEmpty()) {
                entity = eventService == null ? "" : (eventInstance == null ? eventService : eventInstance + " of " + eventService);
            }
            result.add(new OtnlAlarm(node.path("id").asText(""),
                    entity,
                    node.path("message").asText(""),
                    node.path("startTime").asLong(0L),
                    node.path("scope").asText(""),
                    eventService,
                    "",
                    events, tags));
        }
        return result;
    }

    @Override
    public List<OtnlLogRecord> logs(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            Map<String, Object> condition = new LinkedHashMap<>(8);
            if (query.serviceName() != null && !query.serviceName().isBlank()) {
                String serviceId = serviceId(query.serviceName());
                if (serviceId != null) {
                    condition.put("serviceId", serviceId);
                }
            }
            condition.put("queryDuration", SkywalkingTimes.duration(window.start, window.end, query.step()));
            Map<String, Object> paging = new LinkedHashMap<>(2);
            paging.put("pageNum", query.pageNumOrOne());
            paging.put("pageSize", query.pageSizeOrTwenty());
            condition.put("paging", paging);
            condition.put("queryOrder", "DES");
            boolean keyword = query.keyword() != null && !query.keyword().isBlank();
            if (keyword) {
                condition.put("keywordsOfContent", List.of(query.keyword()));
            }
            JsonNode data;
            try {
                data = client().data(SkywalkingQueries.LOGS, Map.of("condition", condition));
            } catch (Exception e) {
                if (!keyword) {
                    throw e;
                }
                // 存储层不支持全文检索时退回无关键字查询
                condition.remove("keywordsOfContent");
                data = client().data(SkywalkingQueries.LOGS, Map.of("condition", condition));
            }
            List<OtnlLogRecord> result = new ArrayList<>();
            for (JsonNode node : data.path("queryLogs").path("logs")) {
                List<OtnlTag> tags = new ArrayList<>();
                String level = "INFO";
                for (JsonNode tag : node.path("tags")) {
                    String key = tag.path("key").asText("");
                    String value = tag.path("value").asText("");
                    tags.add(OtnlTag.of(key, value));
                    if (level.equals("INFO") && ("level".equalsIgnoreCase(key) || "log.level".equalsIgnoreCase(key))) {
                        level = value;
                    }
                }
                result.add(new OtnlLogRecord(node.path("timestamp").asLong(0L),
                        node.path("serviceName").asText(""),
                        node.path("serviceInstanceName").asText(""),
                        node.path("endpointName").asText(""),
                        node.path("traceId").asText(""),
                        level,
                        node.path("contentType").asText(""),
                        node.path("content").asText(""),
                        tags));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询日志失败: {}", rootMessage(e));
            return List.of();
        }
    }

    @Override
    public OtnlHealth health(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            List<ServiceRef> refs = listServiceRefs(window);
            int alarmCount = alarms(query).size();
            // 全局 P99：SkyWalking 的 All 作用域指标（部分存储/版本未启用，取不到按 0 处理）
            double p99 = metricValueSilently("all_p99", new LinkedHashMap<>(Map.of("scope", "All")), window);
            String level = alarmCount > 20 ? "SATURATED" : alarmCount > 0 ? "DEGRADED" : "HEALTHY";
            return new OtnlHealth(level, 0D, 0D, 0D, Math.max(0D, p99),
                    refs.size(), 0, alarmCount,
                    "端点数与 QPS 需按服务下钻查看（SkyWalking 无全局端点/QPS 指标）");
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询健康度失败: {}", rootMessage(e));
            return OtnlHealth.empty("SkyWalking OAP 不可达: " + rootMessage(e));
        }
    }

    /**
     * 剖析任务列表（Profile Task）
     * <p>
     * OAP 的 {@code getProfileTaskList} 只按 serviceId / endpointName 过滤，
     * 服务名需要先换成 ID；服务名为空时逐个服务查询成本太高，因此
     * 「全局任务列表」在本实现里退化为空 —— 前端本页默认按服务查。
     * </p>
     *
     * @param query 查询条件
     * @return 任务列表
     */
    @Override
    public List<OtnlProfileTask> profileTasks(OtnlQuery query) {
        if (query == null || query.serviceName() == null || query.serviceName().isBlank()) {
            return List.of();
        }
        try {
            String serviceId = serviceId(query.serviceName());
            Map<String, Object> vars = new LinkedHashMap<>(4);
            if (serviceId != null) {
                vars.put("serviceId", serviceId);
            }
            String endpoint = query.endpointName();
            if (endpoint != null && !endpoint.isBlank()) {
                vars.put("endpointName", endpoint);
            }
            JsonNode data = client().data(SkywalkingQueries.PROFILE_TASKS, vars);
            List<OtnlProfileTask> result = new ArrayList<>();
            for (JsonNode node : data.path("getProfileTaskList")) {
                List<String> instances = new ArrayList<>();
                for (JsonNode log : node.path("logs")) {
                    String instanceName = log.path("instanceName").asText("");
                    if (!instanceName.isEmpty() && !instances.contains(instanceName)) {
                        instances.add(instanceName);
                    }
                }
                result.add(new OtnlProfileTask(node.path("id").asText(""),
                        node.path("serviceId").asText(""),
                        node.path("serviceName").asText(query.serviceName()),
                        node.path("endpointName").asText(""),
                        node.path("startTime").asLong(0L),
                        node.path("duration").asInt(0),
                        node.path("minDurationThreshold").asInt(0),
                        node.path("dumpPeriod").asInt(0),
                        node.path("maxSamplingCount").asInt(0),
                        instances.size(), List.copyOf(instances)));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询剖析任务失败({}): {}", query.serviceName(), rootMessage(e));
            return List.of();
        }
    }

    /**
     * 剖析任务采样到的链路片段
     *
     * @param taskId 任务 ID
     * @return 片段列表
     */
    @Override
    public List<OtnlProfiledSegment> profiledSegments(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        try {
            JsonNode data = client().data(SkywalkingQueries.PROFILE_SEGMENTS, Map.of("taskId", taskId));
            List<OtnlProfiledSegment> result = new ArrayList<>();
            for (JsonNode node : data.path("getProfileTaskSegments")) {
                List<String> endpoints = textList(node.path("endpointNames"));
                String segmentId = "";
                int spanCount = 0;
                for (JsonNode span : node.path("spans")) {
                    spanCount++;
                    if (segmentId.isEmpty()) {
                        segmentId = span.path("segmentId").asText("");
                    }
                }
                result.add(new OtnlProfiledSegment(node.path("traceId").asText(""),
                        segmentId,
                        node.path("instanceId").asText(""),
                        node.path("instanceName").asText(""),
                        endpoints,
                        node.path("duration").asLong(0L),
                        SkywalkingTimes.parse(node.path("start").asText(""), "MINUTE"),
                        spanCount));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询剖析片段失败({}): {}", taskId, rootMessage(e));
            return List.of();
        }
    }

    /**
     * 剖析结果分析：取任务下全部片段，交给 OAP 聚合成栈树
     *
     * @param taskId 任务 ID
     * @return 分析结果
     */
    @Override
    public OtnlProfileAnalysis profileAnalysis(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return OtnlProfileAnalysis.empty(taskId, "缺少剖析任务 ID");
        }
        List<OtnlProfiledSegment> segments = profiledSegments(taskId);
        if (segments.isEmpty()) {
            return OtnlProfileAnalysis.empty(taskId, "该任务没有采样到慢请求（可能是阈值过高或任务尚未开始）");
        }
        try {
            List<Map<String, Object>> queries = new ArrayList<>();
            for (OtnlProfiledSegment segment : segments) {
                if (segment.segmentId() == null || segment.segmentId().isBlank()) {
                    continue;
                }
                // 单段分析窗口：以采样到的起点为中心取 1 秒，OAP 按段内 Span 时间过滤
                Map<String, Object> range = new LinkedHashMap<>(2);
                range.put("start", segment.startTime());
                range.put("end", segment.startTime() + Math.max(1000L, segment.durationMs()));
                Map<String, Object> item = new LinkedHashMap<>(2);
                item.put("segmentId", segment.segmentId());
                item.put("timeRange", range);
                queries.add(item);
                if (queries.size() >= PROFILE_ANALYZE_SEGMENTS) {
                    break;
                }
            }
            if (queries.isEmpty()) {
                return OtnlProfileAnalysis.empty(taskId, "采样的片段缺少 segmentId，无法分析");
            }
            JsonNode data = client().data(SkywalkingQueries.PROFILE_ANALYZE, Map.of("queries", queries));
            JsonNode analyzation = data.path("getSegmentsProfileAnalyze");
            List<OtnlProfileAnalysis.OtnlProfileStack> trees = new ArrayList<>();
            for (JsonNode tree : analyzation.path("trees")) {
                Map<String, List<OtnlProfileAnalysis.OtnlProfileStack>> childrenByParent = new LinkedHashMap<>();
                List<JsonNode> elements = new ArrayList<>();
                for (JsonNode element : tree.path("elements")) {
                    elements.add(element);
                }
                // 先建索引再递归：OAP 的元素顺序不保证是拓扑序
                for (JsonNode element : elements) {
                    String parentId = element.path("parentId").asText("");
                    childrenByParent.computeIfAbsent(parentId, key -> new ArrayList<>());
                }
                for (JsonNode element : elements) {
                    String parentId = element.path("parentId").asText("");
                    childrenByParent.get(parentId).add(new OtnlProfileAnalysis.OtnlProfileStack(
                            element.path("id").asText(""),
                            parentId,
                            element.path("codeSignature").asText(""),
                            element.path("duration").asInt(0),
                            element.path("durationChildExcluded").asInt(0),
                            element.path("count").asInt(0),
                            List.of()));
                }
                for (JsonNode element : elements) {
                    String id = element.path("id").asText("");
                    List<OtnlProfileAnalysis.OtnlProfileStack> children = childrenByParent.get(id);
                    if (children == null || children.isEmpty()) {
                        continue;
                    }
                    String parentId = element.path("parentId").asText("");
                    List<OtnlProfileAnalysis.OtnlProfileStack> siblings = childrenByParent.get(parentId);
                    if (siblings != null) {
                        siblings.replaceAll(stack -> stack.id().equals(id)
                                ? new OtnlProfileAnalysis.OtnlProfileStack(stack.id(), stack.parentId(),
                                stack.codeSignature(), stack.durationMs(), stack.selfDurationMs(),
                                stack.count(), List.copyOf(children))
                                : stack);
                    }
                }
                for (OtnlProfileAnalysis.OtnlProfileStack root : childrenByParent.getOrDefault("", List.of())) {
                    trees.add(root);
                }
            }
            return new OtnlProfileAnalysis(taskId, analyzation.path("tip").asText(""), trees);
        } catch (Exception e) {
            log.debug("[SkyWalking] 剖析结果分析失败({}): {}", taskId, rootMessage(e));
            return OtnlProfileAnalysis.empty(taskId, "剖析分析失败: " + rootMessage(e));
        }
    }

    /**
     * 列表页补齐服务级聚合指标
     *
     * @param services 服务列表
     * @param window   时间窗口
     * @return 补齐后的列表
     */
    private List<OtnlService> enrichServices(List<OtnlService> services, Window window) {
        if (services.isEmpty() || services.size() > ENRICH_LIMIT) {
            return services;
        }
        long minutes = Math.max(1L, (window.end - window.start) / 60_000L);
        List<OtnlService> result = new ArrayList<>(services.size());
        List<Future<OtnlService>> futures = new ArrayList<>(services.size());
        for (OtnlService service : services) {
            futures.add(executor().submit(() -> enrichService(service, window, minutes)));
        }
        for (int i = 0; i < services.size(); i++) {
            try {
                result.add(futures.get(i).get(SkywalkingSettings.timeout() * 2, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                result.add(services.get(i));
            }
        }
        return result;
    }

    /**
     * 补齐单个服务的聚合指标
     *
     * @param service 服务
     * @param window  时间窗口
     * @param minutes 窗口分钟数
     * @return 补齐后的服务
     */
    private OtnlService enrichService(OtnlService service, Window window, long minutes) {
        Map<String, Object> entity = new LinkedHashMap<>(2);
        entity.put("serviceName", service.name());
        entity.put(NORMAL, true);
        double cpm = metricValue(SkywalkingQueries.METRIC_SERVICE_CPM, entity, window);
        double resp = metricValue(SkywalkingQueries.METRIC_SERVICE_RESP_TIME, entity, window);
        double p99 = metricValue(SkywalkingQueries.METRIC_SERVICE_P99, entity, window);
        double sla = metricValue(SkywalkingQueries.METRIC_SERVICE_SLA, entity, window);
        // 分位值默认不开：OAP 未配置百分位采样时 service_p99 恒为 0（不是查不到，是没样本）。
        // 0ms 的 P99 对列表毫无意义，按「未知」返回，前端显示 —，避免误判成性能极好。
        if (p99 <= 0D) {
            p99 = UNKNOWN;
        }
        return new OtnlService(service.id(), service.name(), service.group(), service.layers(),
                service.instances(), service.endpoints(),
                cpm < 0 ? UNKNOWN_LONG : Math.round(cpm * minutes),
                resp < 0 ? UNKNOWN : resp,
                UNKNOWN,
                p99 < 0 ? UNKNOWN : p99,
                sla < 0 ? UNKNOWN : Math.max(0D, 100D - sla / SLA_FULL * 100D),
                service.tags());
    }

    /**
     * 列表页补齐端点级聚合指标
     *
     * @param endpoints   端点列表
     * @param serviceName 服务名
     * @param window      时间窗口
     * @return 补齐后的列表
     */
    private List<OtnlEndpoint> enrichEndpoints(List<OtnlEndpoint> endpoints, String serviceName, Window window) {
        if (endpoints.isEmpty() || endpoints.size() > ENRICH_LIMIT) {
            return endpoints;
        }
        long minutes = Math.max(1L, (window.end - window.start) / 60_000L);
        List<OtnlEndpoint> result = new ArrayList<>(endpoints.size());
        List<Future<OtnlEndpoint>> futures = new ArrayList<>(endpoints.size());
        for (OtnlEndpoint endpoint : endpoints) {
            futures.add(executor().submit(() -> {
                Map<String, Object> entity = new LinkedHashMap<>(2);
                entity.put("serviceName", serviceName);
                entity.put("endpointName", endpoint.name());
                entity.put(NORMAL, true);
                double cpm = metricValue(SkywalkingQueries.METRIC_ENDPOINT_CPM, entity, window);
                double resp = metricValue(SkywalkingQueries.METRIC_ENDPOINT_RESP_TIME, entity, window);
                double sla = metricValue(SkywalkingQueries.METRIC_ENDPOINT_SLA, entity, window);
                return new OtnlEndpoint(endpoint.id(), endpoint.serviceName(), endpoint.name(),
                        cpm < 0 ? UNKNOWN_LONG : Math.round(cpm * minutes),
                        resp < 0 ? UNKNOWN : resp,
                        UNKNOWN, UNKNOWN,
                        sla < 0 ? UNKNOWN : Math.max(0D, 100D - sla / SLA_FULL * 100D),
                        endpoint.tags());
            }));
        }
        for (int i = 0; i < endpoints.size(); i++) {
            try {
                result.add(futures.get(i).get(SkywalkingSettings.timeout() * 2, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                result.add(endpoints.get(i));
            }
        }
        return result;
    }

    /**
     * 拓扑边补齐调用量与成功率
     *
     * @param calls      边
     * @param nameById   节点 ID → 名称
     * @param window     时间窗口
     * @return 补齐后的边
     */
    private List<OtnlTopology.OtnlTopologyCall> enrichTopologyCalls(List<OtnlTopology.OtnlTopologyCall> calls,
                                                                   Map<String, String> nameById, Window window) {
        if (calls.isEmpty() || calls.size() > TOPOLOGY_ENRICH_LIMIT) {
            return calls;
        }
        long minutes = Math.max(1L, (window.end - window.start) / 60_000L);
        List<OtnlTopology.OtnlTopologyCall> result = new ArrayList<>(calls.size());
        List<Future<OtnlTopology.OtnlTopologyCall>> futures = new ArrayList<>(calls.size());
        for (OtnlTopology.OtnlTopologyCall call : calls) {
            futures.add(executor().submit(() -> {
                String source = nameById.get(call.source());
                String target = nameById.get(call.target());
                if (source == null || target == null) {
                    return call;
                }
                Map<String, Object> entity = new LinkedHashMap<>(2);
                entity.put("serviceName", source);
                entity.put("destServiceName", target);
                entity.put(NORMAL, true);
                entity.put("destNormal", true);
                double cpm = metricValue(SkywalkingQueries.METRIC_RELATION_CLIENT_CPM, entity, window);
                double sla = metricValue(SkywalkingQueries.METRIC_RELATION_CLIENT_SLA, entity, window);
                return new OtnlTopology.OtnlTopologyCall(call.id(), call.source(), call.target(),
                        call.detectPoint(),
                        cpm < 0 ? UNKNOWN_LONG : Math.round(cpm * minutes),
                        sla < 0 ? UNKNOWN : Math.max(0D, 100D - sla / SLA_FULL * 100D),
                        UNKNOWN);
            }));
        }
        for (int i = 0; i < calls.size(); i++) {
            try {
                result.add(futures.get(i).get(SkywalkingSettings.timeout() * 2, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                result.add(calls.get(i));
            }
        }
        return result;
    }

    /**
     * 查询单个指标聚合值
     *
     * @param name   指标名
     * @param entity 实体
     * @param window 时间窗口
     * @return 值，失败返回 {@link #UNKNOWN}
     */
    private double metricValue(String name, Map<String, Object> entity, Window window) {
        try {
            Map<String, Object> condition = new LinkedHashMap<>(2);
            condition.put("name", name);
            condition.put("entity", entity);
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("condition", condition);
            vars.put("duration", SkywalkingTimes.duration(window.start, window.end, "MINUTE"));
            JsonNode data = client().data(SkywalkingQueries.METRICS_VALUE, vars);
            JsonNode value = data.path("readMetricsValue");
            return value.isMissingNode() || value.isNull() ? UNKNOWN : value.asDouble(UNKNOWN);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /**
     * 查询单个指标聚合值（失败静默为 0）
     *
     * @param name   指标名
     * @param entity 实体
     * @param window 时间窗口
     * @return 值
     */
    private double metricValueSilently(String name, Map<String, Object> entity, Window window) {
        double value = metricValue(name, entity, window);
        return value < 0 ? 0D : value;
    }

    /**
     * 读取服务目录（带缓存）
     *
     * @param window 时间窗口
     * @return 服务引用列表
     */
    private List<ServiceRef> listServiceRefs(Window window) {
        long now = System.currentTimeMillis();
        List<ServiceRef> cached = cachedServiceRefs;
        if (cached != null && now < serviceCacheExpireAt) {
            return cached;
        }
        synchronized (this) {
            if (cachedServiceRefs != null && now < serviceCacheExpireAt) {
                return cachedServiceRefs;
            }
            List<ServiceRef> refs = new ArrayList<>();
            Map<String, String> ids = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            try {
                JsonNode data;
                try {
                    data = client().data(SkywalkingQueries.SERVICES, new LinkedHashMap<>());
                    if (!data.has("listServices")) {
                        throw new IllegalStateException("listServices 不可用");
                    }
                } catch (Exception e) {
                    // 8.x 没有 listServices，用 getAllServices 兜底
                    data = client().data(SkywalkingQueries.SERVICES_LEGACY,
                            Map.of("duration", SkywalkingTimes.duration(window.start, window.end, "MINUTE")));
                    data = data.has("getAllServices") ? data : null;
                }
                if (data != null) {
                    JsonNode array = data.has("listServices") ? data.path("listServices") : data.path("getAllServices");
                    for (JsonNode node : array) {
                        String id = node.path("id").asText("");
                        String serviceName = node.path("name").asText("");
                        String group = node.path("group").asText("");
                        List<String> layers = textList(node.path("layers"));
                        refs.add(new ServiceRef(id, serviceName, group, layers));
                        ids.put(serviceName, id);
                        names.put(id, serviceName);
                    }
                }
            } catch (Exception e) {
                log.debug("[SkyWalking] 读取服务目录失败: {}", rootMessage(e));
            }
            cachedServiceRefs = refs;
            serviceIds = ids;
            serviceNames = names;
            serviceCacheExpireAt = now + SERVICE_CACHE_TTL;
            return refs;
        }
    }

    private volatile List<ServiceRef> cachedServiceRefs;

    /**
     * 服务名 → 服务 ID
     *
     * @param serviceName 服务名
     * @return 服务 ID，找不到返回 null
     */
    private String serviceId(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }
        Map<String, String> ids = serviceIds;
        if (ids.containsKey(serviceName)) {
            return ids.get(serviceName);
        }
        listServiceRefs(Window.of(null));
        return serviceIds.get(serviceName);
    }

    /**
     * 执行查询，主语句失败时回落兜底语句
     *
     * @param primary  主语句
     * @param fallback 兜底语句
     * @param vars     变量
     * @return data 节点，两次都失败返回 null
     */
    private JsonNode queryWithFallback(String primary, String fallback, Map<String, Object> vars) {
        try {
            return client().data(primary, vars);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SkyWalking] 主查询失败，尝试兜底语句: {}", rootMessage(e));
            }
        }
        try {
            return client().data(fallback, vars);
        } catch (Exception e) {
            log.debug("[SkyWalking] 兜底查询同样失败: {}", rootMessage(e));
            return null;
        }
    }

    /**
     * 指标单位
     *
     * @param name  指标名
     * @param scope 作用域
     * @return 单位
     */
    private String unitOf(String name, OtnlScope scope) {
        for (OtnlMetricDefinition definition : metricDefinitions(scope)) {
            if (definition.name().equals(name)) {
                return definition.unit();
            }
        }
        return null;
    }

    /**
     * 链路起始时间。
     * <p>
     * {@code BasicTrace.start} 在 OAP 的 schema 里是 {@code Long!}，返回的是
     * <b>epoch 毫秒</b>（如 {@code 1793841737406}）；而 metrics 的 {@code id} 才是
     * {@code yyyyMMddHHmmss_entity} 这种紧凑时间桶。两者不能共用同一个解析器：
     * 把 epoch 毫秒当时间桶解析会得到 0，表现是「链路列表时间全变成 1970」。
     * </p>
     * <p>
     * 这里优先按数值读（兼容 10~13 位的 epoch 秒/毫秒），非数值时才退回时间桶解析，
     * 以兼容少数把 start 输出成 Duration 字符串的定制版本。
     * </p>
     *
     * @param node {@code BasicTrace}
     * @return epoch 毫秒
     */
    private static long startTime(JsonNode node) {
        JsonNode start = node.path("start");
        if (start.isNumber()) {
            long value = start.asLong(0L);
            return value > 0L && value < 100_000_000_000L ? value * 1_000L : value;
        }
        String text = start.asText("");
        if (!text.isBlank() && text.chars().allMatch(Character::isDigit)) {
            long value = Long.parseLong(text);
            return value > 0L && value < 100_000_000_000L ? value * 1_000L : value;
        }
        return SkywalkingTimes.parse(text, "MINUTE");
    }

    /**
     * SkyWalking 的 serviceCode 是 {@code base64(服务名) + "." + layerId}，这里还原服务名
     *
     * @param code 服务编码
     * @return 服务名，无法解码时原样返回
     */
    private static String decodeServiceName(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        int index = code.lastIndexOf('.');
        String base = index > 0 ? code.substring(0, index) : code;
        for (Base64.Decoder decoder : new Base64.Decoder[]{Base64.getDecoder(), Base64.getUrlDecoder()}) {
            try {
                byte[] raw = decoder.decode(pad(base));
                String name = new String(raw, StandardCharsets.UTF_8);
                if (!name.isBlank() && name.chars().allMatch(ch -> ch >= 0x20)) {
                    return name;
                }
            } catch (Exception ignore) {
                // 换一种解码器继续尝试
            }
        }
        return code;
    }

    /**
     * 补齐 base64 填充符
     *
     * @param text 原文
     * @return 补齐后的文本
     */
    private static String pad(String text) {
        int mod = text.length() % 4;
        if (mod == 0) {
            return text;
        }
        return text + "=".repeat(4 - mod);
    }

    /**
     * 取字符串数组
     *
     * @param node 节点
     * @return 字符串列表
     */
    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            result.add(item.asText(""));
        }
        return result;
    }

    /**
     * 取异常根因消息
     *
     * @param throwable 异常
     * @return 消息
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 惰性获取 OAP 客户端
     *
     * @return 客户端
     */
    private SkywalkingOapClient client() {
        SkywalkingOapClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = new SkywalkingOapClient();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * 惰性获取补齐用的线程池
     *
     * @return 线程池
     */
    private ExecutorService executor() {
        ExecutorService current = executor;
        if (current == null) {
            synchronized (this) {
                current = executor;
                if (current == null) {
                    AtomicInteger index = new AtomicInteger();
                    ThreadFactory factory = runnable -> {
                        Thread thread = new Thread(runnable, "apm-skywalking-" + index.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    };
                    current = Executors.newFixedThreadPool(ENRICH_PARALLELISM, factory);
                    executor = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            executor = null;
        }
        SkywalkingOapClient currentClient = client;
        if (currentClient != null) {
            currentClient.close();
            client = null;
        }
        cachedServiceRefs = null;
        serviceCacheExpireAt = 0L;
        cachedStatus = null;
        statusExpireAt = 0L;
    }

    /**
     * 未知数值的整型占位
     */
    private static final long UNKNOWN_LONG = -1L;

    /**
     * 服务引用（内部结构，避免多次查 GraphQL 拿不到 ID）
     *
     * @param id     服务 ID
     * @param name   服务名
     * @param group  服务组
     * @param layers 分层
     */
    private record ServiceRef(String id, String name, String group, List<String> layers) {
    }

    /**
     * 归一化后的查询时间窗口
     */
    private static final class Window {
        private final long start;
        private final long end;

        private Window(long start, long end) {
            this.start = start;
            this.end = end;
        }

        /**
         * 从查询条件推导窗口（缺省最近 30 分钟）
         *
         * @param query 查询条件，可为 null
         * @return 窗口
         */
        private static Window of(OtnlQuery query) {
            long end = query == null || query.endTime() <= 0 ? System.currentTimeMillis() : query.endTime();
            long start = query == null || query.startTime() <= 0 ? end - 30 * 60_000L : query.startTime();
            if (start >= end) {
                start = end - 30 * 60_000L;
            }
            return new Window(start, end);
        }
    }

}
