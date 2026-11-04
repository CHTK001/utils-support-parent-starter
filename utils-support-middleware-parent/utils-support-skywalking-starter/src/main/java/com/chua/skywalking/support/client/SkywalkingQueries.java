package com.chua.skywalking.support.client;

/**
 * SkyWalking OAP GraphQL 查询语句常量。
 * <p>
 * 语句与 <a href="https://github.com/apache/skywalking-query-protocol">apache/skywalking-query-protocol</a>
 * 对齐，只取「各版本都稳定」的字段，必要处保留一套 legacy 兜底语句：
 * </p>
 * <ul>
 *   <li>{@code listServices} / {@code listInstances} / {@code findEndpoint} 为 9.x 起的 v2 metadata，
 *       8.x 则用 {@code getAllServices} / {@code getServiceInstances} / {@code searchEndpoint}；</li>
 *   <li>指标统一走 metrics-v2 的 {@code readMetricsValue(s)}（9.5 之后官方推荐 MQE，但 v2 仍保留，
 *       且 v2 的 expression 更简单、跨版本可用）；</li>
 *   <li>告警用 {@code getAlarm}（10.x 起被 {@code queryAlarms} 取代但仍兼容）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SkywalkingQueries {

    /**
     * 版本
     */
    public static final String VERSION = "query { version }";

    /**
     * 健康检查
     */
    public static final String HEALTH = "query { checkHealth { score details } }";

    /**
     * 服务列表（metadata v2）
     */
    public static final String SERVICES = """
            query listServices($layer: String) {
              listServices(layer: $layer) { id name group shortName layers normal }
            }""";

    /**
     * 服务列表（metadata v1 兜底）
     */
    public static final String SERVICES_LEGACY = """
            query getAllServices($duration: Duration!) {
              getAllServices(duration: $duration) { id name group }
            }""";

    /**
     * 服务实例（metadata v2）
     */
    public static final String INSTANCES = """
            query listInstances($duration: Duration!, $serviceId: ID!) {
              listInstances(duration: $duration, serviceId: $serviceId) {
                id name language instanceUUID attributes { name value }
              }
            }""";

    /**
     * 服务实例（metadata v1 兜底）
     */
    public static final String INSTANCES_LEGACY = """
            query getServiceInstances($duration: Duration!, $serviceId: ID!) {
              getServiceInstances(duration: $duration, serviceId: $serviceId) {
                id name language instanceUUID attributes { name value }
              }
            }""";

    /**
     * 端点（metadata v2）
     */
    public static final String ENDPOINTS = """
            query findEndpoint($serviceId: ID!, $keyword: String, $limit: Int!) {
              findEndpoint(serviceId: $serviceId, keyword: $keyword, limit: $limit) { id name }
            }""";

    /**
     * 端点（metadata v1 兜底）
     */
    public static final String ENDPOINTS_LEGACY = """
            query searchEndpoint($serviceId: ID!, $keyword: String, $limit: Int!) {
              searchEndpoint(serviceId: $serviceId, keyword: $keyword, limit: $limit) { id name }
            }""";

    /**
     * 链路列表
     */
    public static final String BASIC_TRACES = """
            query queryBasicTraces($condition: TraceQueryCondition) {
              queryBasicTraces(condition: $condition) {
                traces { segmentId endpointNames duration start isError traceIds }
              }
            }""";

    /**
     * 链路详情（含 Span 明细）
     */
    public static final String TRACE = """
            query queryTrace($traceId: ID!) {
              queryTrace(traceId: $traceId) {
                spans {
                  traceId segmentId spanId parentSpanId
                  serviceCode serviceInstanceName
                  startTime endTime endpointName type peer component isError layer
                  tags { key value }
                  logs { time data { key value } }
                }
              }
            }""";

    /**
     * 全局拓扑
     */
    public static final String GLOBAL_TOPOLOGY = """
            query getGlobalTopology($duration: Duration!) {
              getGlobalTopology(duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 服务拓扑（按服务名，10.x 起）
     */
    public static final String SERVICE_TOPOLOGY_BY_NAME = """
            query getServiceTopologyByName($service: ServiceCondition!, $duration: Duration!) {
              getServiceTopologyByName(service: $service, duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 服务拓扑（按服务 ID，9.x 及更早）
     */
    public static final String SERVICE_TOPOLOGY_BY_ID = """
            query getServiceTopology($serviceId: ID!, $duration: Duration!) {
              getServiceTopology(serviceId: $serviceId, duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 指标时序
     */
    public static final String METRICS_VALUES = """
            query readMetricsValues($condition: MetricsCondition!, $duration: Duration!) {
              readMetricsValues(condition: $condition, duration: $duration) {
                label
                values { values { id value isEmptyValue } }
              }
            }""";

    /**
     * 指标单值（用于列表页聚合列）
     */
    public static final String METRICS_VALUE = """
            query readMetricsValue($condition: MetricsCondition!, $duration: Duration!) {
              readMetricsValue(condition: $condition, duration: $duration)
            }""";

    /**
     * 告警
     */
    public static final String ALARMS = """
            query getAlarm($duration: Duration!, $scope: Scope, $keyword: String, $paging: Pagination!) {
              getAlarm(duration: $duration, scope: $scope, keyword: $keyword, paging: $paging) {
                msgs { id name message startTime recoveryTime scope tags { key value } }
              }
            }""";

    /**
     * 日志
     */
    public static final String LOGS = """
            query queryLogs($condition: LogQueryCondition) {
              queryLogs(condition: $condition) {
                errorReason
                logs {
                  serviceName serviceInstanceName endpointName traceId
                  timestamp contentType content tags { key value }
                }
              }
            }""";

    /**
     * 服务级指标名：吞吐量（每分钟调用数）
     */
    public static final String METRIC_SERVICE_CPM = "service_cpm";

    /**
     * 服务级指标名：平均响应耗时
     */
    public static final String METRIC_SERVICE_RESP_TIME = "service_resp_time";

    /**
     * 服务级指标名：成功率（10000 = 100%）
     */
    public static final String METRIC_SERVICE_SLA = "service_sla";

    /**
     * 服务级指标名：P99 响应耗时
     */
    public static final String METRIC_SERVICE_P99 = "service_p99";

    /**
     * 端点级指标名：吞吐量
     */
    public static final String METRIC_ENDPOINT_CPM = "endpoint_cpm";

    /**
     * 端点级指标名：平均响应耗时
     */
    public static final String METRIC_ENDPOINT_RESP_TIME = "endpoint_resp_time";

    /**
     * 端点级指标名：成功率
     */
    public static final String METRIC_ENDPOINT_SLA = "endpoint_sla";

    /**
     * 服务关系指标名：客户端调用量
     */
    public static final String METRIC_RELATION_CLIENT_CPM = "service_relation_client_cpm";

    /**
     * 服务关系指标名：客户端成功率
     */
    public static final String METRIC_RELATION_CLIENT_SLA = "service_relation_client_call_sla";

    private SkywalkingQueries() {
    }
}
