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
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
     * 请求补齐时的并发度
     */
    private static final int ENRICH_PARALLELISM = 4;

    /**
     * 未知值占位（引擎不支持或未查询）
     */
    private static final double UNKNOWN = -1D;

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

    @Override
    public String name() {
        return "skywalking";
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
            fresh = OtnlStatus.available(name(), version, SkywalkingSettings.url(), "remote", capabilities());
            statusExpireAt = now + STATUS_OK_TTL;
        } catch (Exception e) {
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
                long start = SkywalkingTimes.parse(node.path("start").asText(""), "MINUTE");
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
            JsonNode topology;
            if (query.serviceName() == null || query.serviceName().isBlank()) {
                topology = client().data(SkywalkingQueries.GLOBAL_TOPOLOGY, Map.of("duration", duration))
                        .path("getGlobalTopology");
            } else {
                String serviceId = serviceId(query.serviceName());
                topology = null;
                try {
                    Map<String, Object> vars = new LinkedHashMap<>(4);
                    vars.put("service", Map.of("serviceName", query.serviceName()));
                    vars.put("duration", duration);
                    topology = client().data(SkywalkingQueries.SERVICE_TOPOLOGY_BY_NAME, vars)
                            .path("getServiceTopologyByName");
                } catch (Exception ignore) {
                    // 10.0 之前没有 ByName 版本，回落 ID 形式
                }
                if ((topology == null || topology.isMissingNode()) && serviceId != null) {
                    Map<String, Object> vars = new LinkedHashMap<>(4);
                    vars.put("serviceId", serviceId);
                    vars.put("duration", duration);
                    topology = client().data(SkywalkingQueries.SERVICE_TOPOLOGY_BY_ID, vars)
                            .path("getServiceTopology");
                }
            }
            if (topology == null || topology.isMissingNode()) {
                return OtnlTopology.empty();
            }

            Map<String, String> nameById = new LinkedHashMap<>();
            List<OtnlTopology.OtnlTopologyNode> nodes = new ArrayList<>();
            for (JsonNode node : topology.path("nodes")) {
                String id = node.path("id").asText("");
                String nodeName = node.path("name").asText("");
                nameById.put(id, nodeName);
                List<String> layers = textList(node.path("layers"));
                nodes.add(new OtnlTopology.OtnlTopologyNode(id, nodeName,
                        node.path("type").asText(""), node.path("isReal").asBoolean(true),
                        nodeName.equals(query.serviceName()),
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
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询拓扑失败: {}", rootMessage(e));
            return OtnlTopology.empty();
        }
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
            Map<String, Object> condition = new LinkedHashMap<>(2);
            condition.put("name", query.name());
            condition.put("entity", entity);
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("condition", condition);
            vars.put("duration", SkywalkingTimes.duration(start, end, step));
            JsonNode data = client().data(SkywalkingQueries.METRICS_VALUES, vars);

            String actualStep = SkywalkingTimes.normalizeStep(step, start, end);
            List<OtnlMetricSeries.OtnlMetricPoint> points = new ArrayList<>();
            for (JsonNode point : data.path("readMetricsValues").path("values").path("values")) {
                long time = SkywalkingTimes.parse(point.path("id").asText(""), actualStep);
                double value = point.path("value").asDouble(0D);
                if (time > 0) {
                    points.add(new OtnlMetricSeries.OtnlMetricPoint(time, value));
                }
            }
            return new OtnlMetricSeries(query.name(), scope, label, unitOf(query.name(), scope), points);
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询指标失败({}): {}", query.name(), rootMessage(e));
            return OtnlMetricSeries.empty(query.name(), scope, label);
        }
    }

    @Override
    public List<OtnlAlarm> alarms(OtnlQuery query) {
        Window window = Window.of(query);
        try {
            Map<String, Object> single = new LinkedHashMap<>(2);
            single.put("pageNum", query.pageNumOrOne());
            single.put("pageSize", query.pageSizeOrTwenty());
            Map<String, Object> vars = new LinkedHashMap<>(4);
            vars.put("duration", SkywalkingTimes.duration(window.start, window.end, query.step()));
            vars.put("scope", "Service");
            vars.put("keyword", query.keyword() == null ? "" : query.keyword());
            vars.put("paging", single);
            JsonNode data = client().data(SkywalkingQueries.ALARMS, vars);
            List<OtnlAlarm> result = new ArrayList<>();
            for (JsonNode node : data.path("getAlarm").path("msgs")) {
                List<OtnlTag> tags = new ArrayList<>();
                for (JsonNode tag : node.path("tags")) {
                    tags.add(OtnlTag.of(tag.path("key").asText(""), tag.path("value").asText("")));
                }
                result.add(new OtnlAlarm(node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("message").asText(""),
                        node.path("startTime").asLong(0L),
                        node.path("scope").asText(""),
                        node.path("name").asText(""),
                        null, List.of(), tags));
            }
            return result;
        } catch (Exception e) {
            log.debug("[SkyWalking] 查询告警失败: {}", rootMessage(e));
            return List.of();
        }
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
        Map<String, Object> entity = Map.of("serviceName", service.name());
        double cpm = metricValue(SkywalkingQueries.METRIC_SERVICE_CPM, entity, window);
        double resp = metricValue(SkywalkingQueries.METRIC_SERVICE_RESP_TIME, entity, window);
        double p99 = metricValue(SkywalkingQueries.METRIC_SERVICE_P99, entity, window);
        double sla = metricValue(SkywalkingQueries.METRIC_SERVICE_SLA, entity, window);
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
