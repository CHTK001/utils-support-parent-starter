package com.chua.common.support.apm;

import com.chua.common.support.apm.model.OtnlAlarm;
import com.chua.common.support.apm.model.OtnlEndpoint;
import com.chua.common.support.apm.model.OtnlHealth;
import com.chua.common.support.apm.model.OtnlInstance;
import com.chua.common.support.apm.model.OtnlLogRecord;
import com.chua.common.support.apm.model.OtnlMetricDefinition;
import com.chua.common.support.apm.model.OtnlMetricSeries;
import com.chua.common.support.apm.model.OtnlService;
import com.chua.common.support.apm.model.OtnlStatus;
import com.chua.common.support.apm.model.OtnlTopology;
import com.chua.common.support.apm.model.OtnlTrace;

import java.util.List;
import java.util.Map;

/**
 * Otnl（Open Telemetry Native Layer）引擎 SPI。
 * <p>
 * 这是整个仓库唯一的「可观测数据访问」契约：SkyWalking / OpenTelemetry / 自研
 * hotspot agent 三种数据源各实现一次，上层的 spring apm 模块与前端页面只认这一套
 * 方法名与返回结构，因此换后端不需要改页面。
 * </p>
 * <h3>实现约定</h3>
 * <ul>
 *   <li>实现类用 {@code @Spi("名称")} 标注，并在
 *       {@code META-INF/extensions/com.chua.common.support.apm.OtnlEngine} 中登记；</li>
 *   <li>任何后端错误都不得向上抛异常，统一降级为空列表 + 通过 {@link #status()} 暴露原因
 *       （页面宁可显示「暂无数据/后端不可达」，也不能整页 500）；</li>
 *   <li>不支持的查询返回 {@link List#of()}，不要把「不支持」伪装成空数据以外的形状；</li>
 *   <li>时间统一毫秒时间戳，耗时统一毫秒。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface OtnlEngine extends AutoCloseable {

    /**
     * 能力标识：服务目录
     */
    String CAP_SERVICES = "services";

    /**
     * 能力标识：服务实例
     */
    String CAP_INSTANCES = "instances";

    /**
     * 能力标识：端点
     */
    String CAP_ENDPOINTS = "endpoints";

    /**
     * 能力标识：链路
     */
    String CAP_TRACES = "traces";

    /**
     * 能力标识：拓扑
     */
    String CAP_TOPOLOGY = "topology";

    /**
     * 能力标识：指标
     */
    String CAP_METRICS = "metrics";

    /**
     * 能力标识：JVM/运行时指标
     */
    String CAP_RUNTIME = "runtime";

    /**
     * 能力标识：告警
     */
    String CAP_ALARMS = "alarms";

    /**
     * 能力标识：日志
     */
    String CAP_LOGS = "logs";

    /**
     * 能力标识：告警（{@code /alerts} 接口）
     */
    String CAP_ALERTS = "alerts";

    /**
     * 能力标识：性能剖析（{@code /profiles} 系列接口）
     */
    String CAP_PROFILES = "profiles";

    /**
     * 引擎标识，与配置 {@code plugin.apm.type} 对应
     *
     * @return 名称，如 {@code skywalking} / {@code opentelemetry} / {@code hotspot}
     */
    String name();

    /**
     * 引擎状态（可用性 + 后端地址 + 失败原因）
     *
     * @return 状态
     */
    OtnlStatus status();

    /**
     * 能力清单，默认全支持，实现按需收窄
     *
     * @return 能力标识集合
     */
    default List<String> capabilities() {
        return List.of(CAP_SERVICES, CAP_INSTANCES, CAP_ENDPOINTS, CAP_TRACES,
                CAP_TOPOLOGY, CAP_METRICS, CAP_RUNTIME, CAP_ALARMS, CAP_LOGS);
    }

    /**
     * 是否支持某能力
     *
     * @param capability 能力标识
     * @return 是否支持
     */
    default boolean supports(String capability) {
        return capabilities().contains(capability);
    }

    /**
     * 服务目录
     *
     * @param query 查询条件
     * @return 服务列表
     */
    List<OtnlService> services(OtnlQuery query);

    /**
     * 服务实例
     *
     * @param serviceName 服务名
     * @param query       查询条件
     * @return 实例列表
     */
    List<OtnlInstance> instances(String serviceName, OtnlQuery query);

    /**
     * 服务端点
     *
     * @param serviceName 服务名
     * @param query       查询条件（{@link OtnlQuery#keyword()} 用于端点名过滤）
     * @return 端点列表
     */
    List<OtnlEndpoint> endpoints(String serviceName, OtnlQuery query);

    /**
     * 链路列表
     *
     * @param query 查询条件
     * @return 链路摘要列表
     */
    List<OtnlTrace> traces(OtnlQuery query);

    /**
     * 链路详情
     *
     * @param traceId 链路 ID
     * @return 含 Span 明细的链路；不存在返回 null
     */
    OtnlTrace trace(String traceId);

    /**
     * 服务拓扑
     *
     * @param query 查询条件（{@link OtnlQuery#serviceName()} 为空表示全局拓扑）
     * @return 拓扑
     */
    OtnlTopology topology(OtnlQuery query);

    /**
     * 指标时序
     *
     * @param query 指标查询
     * @return 时序
     */
    OtnlMetricSeries metrics(OtnlMetricQuery query);

    /**
     * 指标定义（前端渲染指标下拉）
     *
     * @param scope 作用域
     * @return 指标定义列表
     */
    default List<OtnlMetricDefinition> metricDefinitions(OtnlScope scope) {
        OtnlScope target = scope == null ? OtnlScope.SERVICE : scope;
        return switch (target) {
            case ENDPOINT -> OtnlMetricDefinition.ENDPOINT_DEFAULTS;
            case SERVICE_INSTANCE -> OtnlMetricDefinition.INSTANCE_DEFAULTS;
            case ALL -> OtnlMetricDefinition.ALL_DEFAULTS;
            default -> OtnlMetricDefinition.SERVICE_DEFAULTS;
        };
    }

    /**
     * 告警列表
     *
     * @param query 查询条件
     * @return 告警列表
     */
    List<OtnlAlarm> alarms(OtnlQuery query);

    /**
     * 日志列表
     *
     * @param query 查询条件
     * @return 日志列表
     */
    List<OtnlLogRecord> logs(OtnlQuery query);

    /**
     * 健康度
     *
     * @param query 查询条件
     * @return 健康度
     */
    OtnlHealth health(OtnlQuery query);

    /**
     * 多服务合并拓扑（用于对比页合并多个服务的拓扑）
     *
     * @param serviceNames 需要合并的服务名列表
     * @param query        查询条件
     * @return 拓扑列表
     */
    default List<OtnlTopology> multiTopology(List<String> serviceNames, OtnlQuery query) {
        return List.of();
    }

    /**
     * 性能剖析任务列表
     *
     * @param query 查询条件
     * @return 任务列表
     */
    default List<Map<String, Object>> profileTasks(OtnlQuery query) {
        return List.of();
    }

    /**
     * 工作区剖析任务采样到的代码片段
     *
     * @param taskId 任务 ID
     * @return 片段列表
     */
    default List<Map<String, Object>> profiledSegments(String taskId) {
        return List.of();
    }

    /**
     * 剖析任务的结果（线程栈树）
     *
     * @param taskId 任务 ID
     * @return 剖析结果
     */
    default Map<String, Object> profileAnalysis(String taskId) {
        return Map.of();
    }

    /**
     * 批量指标（多服务×多指标先构建好查询，返回时序）
     *
     * @param queries 指标查询列表
     * @return 时序
     */
    default OtnlMetricSeries metricsBatch(List<OtnlMetricQuery> queries) {
        return OtnlMetricSeries.empty("batch", OtnlScope.SERVICE, "batch");
    }

    /**
     * 释放底层资源（HTTP 连接池等），默认无动作
     */
    @Override
    default void close() {
        // 默认无需释放
    }
}
