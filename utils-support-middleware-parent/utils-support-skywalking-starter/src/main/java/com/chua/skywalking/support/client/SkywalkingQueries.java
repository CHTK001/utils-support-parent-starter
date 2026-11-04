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
 *   <li>告警用 {@code getAlarm}（9.x ~ 10.x 唯一入口），11.0.0 起才有 {@code queryAlarms}；</li>
 *   <li>拓扑节点 {@code layers}（10.0.0）、告警 {@code name}（10.2.0）等新增字段单独留一套
 *       不带该字段的语句，按 {@link SkywalkingVersion} 选择。</li>
 * </ul>
 * <p>
 * <b>新增字段前先确认它从哪个版本开始存在。</b>GraphQL 校验是整条查询全有或全无：
 * 选了老版本没有的字段，整条查询直接报错，表现是「这个页面一条数据都没有」，
 * 而不是「少显示一列」——这种 bug 在开发机上很难被发现，因为开发机的 OAP 通常是最新的。
 * </p>
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
     * <p>
     * <b>{@code keyword} 必须是 {@code String!}（NonNull）。</b>v1 的 {@code searchEndpoint}
     * 与 v2 的 {@code findEndpoint} 签名不同：v1 要求 NonNull，v2 允许可空。
     * 声明成 {@code String} 会让变量类型校验失败（{@code VariableTypeMismatch}），
     * 整条查询直接报错——而在 9.x 上这条 v1 语句正是唯一可用的端点查询，
     * 表现为「端点页只有首页能出数据，点进去服务后全空」。
     * </p>
     * <p>
     * 已在真实 OAP 9.7.0 上验证：{@code String} → 400 {@code VariableTypeMismatch}；
     * {@code String!} → 200 正常返回。
     * </p>
     */
    public static final String ENDPOINTS_LEGACY = """
            query searchEndpoint($serviceId: ID!, $keyword: String!, $limit: Int!) {
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
     * <p>
     * {@code Node.layers} 是 10.0.0 才有的字段，9.x 的 {@code Node}
     * 只有 {@code id / name / type / isReal}。因为 GraphQL 校验是整条查询全有或全无，
     * 在 9.x 上选了 {@code layers} 就等于「全局拓扑整页空白」，所以保留两套语句。
     * </p>
     */
    public static final String GLOBAL_TOPOLOGY = """
            query getGlobalTopology($duration: Duration!) {
              getGlobalTopology(duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 全局拓扑（9.x 兜底：节点没有 {@code layers}）
     */
    public static final String GLOBAL_TOPOLOGY_LEGACY = """
            query getGlobalTopology($duration: Duration!) {
              getGlobalTopology(duration: $duration) {
                nodes { id name type isReal }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 服务拓扑（按服务名，11.0.0 起）
     */
    public static final String SERVICE_TOPOLOGY_BY_NAME = """
            query getServiceTopologyByName($service: ServiceCondition!, $duration: Duration!) {
              getServiceTopologyByName(service: $service, duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 服务拓扑（按服务 ID，9.x ~ 10.x）
     */
    public static final String SERVICE_TOPOLOGY_BY_ID = """
            query getServiceTopology($serviceId: ID!, $duration: Duration!) {
              getServiceTopology(serviceId: $serviceId, duration: $duration) {
                nodes { id name type isReal layers }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 服务拓扑（按服务 ID，9.x 兜底：节点没有 {@code layers}）
     */
    public static final String SERVICE_TOPOLOGY_BY_ID_LEGACY = """
            query getServiceTopology($serviceId: ID!, $duration: Duration!) {
              getServiceTopology(serviceId: $serviceId, duration: $duration) {
                nodes { id name type isReal }
                calls { id source target detectPoints }
              }
            }""";

    /**
     * 多服务拓扑（按服务名，11.0.0 起，一次请求合并多实体，供对比页使用）
     */
    public static final String SERVICES_TOPOLOGY_BY_NAMES = """
            query getServicesTopologyByNames($services: [ServiceCondition!]!, $duration: Duration!) {
              getServicesTopologyByNames(services: $services, duration: $duration) {
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
     * 告警（{@code getAlarm}，9.x ~ 10.x；11.0 起标记 deprecated 但仍可用）
     * <p>
     * <b>这里只能用 9.7.0 就存在的字段。</b>GraphQL 的校验是全有或全无：
     * 只要有一个字段在目标 OAP 的 schema 里不存在，整条查询直接报错，
     * 而且是「一条告警都返回不了」，不是「少个字段」。因此：
     * </p>
     * <ul>
     *   <li>{@code name} —— 10.2.0 才加进 {@code AlarmMessage}，老版本没有；</li>
     *   <li>{@code recoveryTime} —— {@code AlarmMessage} 上是 11.0.0 才加的；</li>
     *   <li>{@code events { ... }} —— 9.7.0 就有，且 {@code Event.source.service}
     *       三个版本一致，用它按事件反查服务名是唯一跨版本可靠的办法。</li>
     * </ul>
     *
     * @see #ALARMS_V2
     */
    public static final String ALARMS = """
            query getAlarm($duration: Duration!, $scope: Scope, $keyword: String, $paging: Pagination!) {
              getAlarm(duration: $duration, scope: $scope, keyword: $keyword, paging: $paging) {
                msgs {
                  id message startTime scope
                  events { name message type startTime endTime source { service serviceInstance endpoint } }
                  tags { key value }
                }
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

    /**
     * 剖析任务列表
     */
    public static final String PROFILE_TASKS = """
            query getProfileTaskList($serviceId: ID, $endpointName: String) {
              getProfileTaskList(serviceId: $serviceId, endpointName: $endpointName) {
                id serviceId serviceName endpointName startTime duration
                minDurationThreshold dumpPeriod maxSamplingCount
                logs { id instanceId instanceName operationType operationTime }
              }
            }""";

    /**
     * 剖析任务采样到的链路片段
     */
    public static final String PROFILE_SEGMENTS = """
            query getProfileTaskSegments($taskId: ID!) {
              getProfileTaskSegments(taskID: $taskId) {
                traceId instanceId instanceName endpointNames duration start
                spans { spanId endpointName startTime endTime }
              }
            }""";

    /**
     * 剖析结果分析（多段一起分析，OAP 负责拼栈树）
     */
    public static final String PROFILE_ANALYZE = """
            query getSegmentsProfileAnalyze($queries: [SegmentProfileAnalyzeQuery!]!) {
              getSegmentsProfileAnalyze(queries: $queries) {
                tip
                trees {
                  elements { id parentId codeSignature duration durationChildExcluded count }
                }
              }
            }""";

    /**
     * 告警（11.0.0 起的 queryAlarms，支持实体 / 规则名 / 层过滤）
     * <p>
     * 同样只能选 11.0.0 就有的字段：{@code name}（11.0.0 起随 {@code AlarmMessage} 暴露）
     * 与 {@code recoveryTime}（同版本引入）可以选，但更早的版本没有 {@code queryAlarms}
     * 这个入口，由 {@link #ALARMS} 兜底，因此这里的字段集合与兜底查询分开维护。
     * </p>
     */
    public static final String ALARMS_V2 = """
            query queryAlarms($condition: AlarmQueryCondition!) {
              queryAlarms(condition: $condition) {
                msgs {
                  id name message startTime recoveryTime scope
                  events { name message type startTime endTime source { service serviceInstance endpoint } }
                  tags { key value }
                }
              }
            }""";

    private SkywalkingQueries() {
    }
}
