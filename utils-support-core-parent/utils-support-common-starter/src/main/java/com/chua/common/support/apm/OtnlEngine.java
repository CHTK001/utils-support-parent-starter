package com.chua.common.support.apm;

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
import com.chua.common.support.apm.orm.OtnlLambdaDeleteWrapper;
import com.chua.common.support.apm.orm.OtnlLambdaQueryWrapper;
import com.chua.common.support.apm.orm.OtnlLambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Otnl（Open Telemetry Native Layer）引擎 SPI。
 * <p>
 * 这是整个仓库唯一的「可观测数据访问」契约：SkyWalking / OpenTelemetry / 自研
 * hotspot agent 三种数据源各实现一次，上层的 spring apm 模块与前端页面只认这一套
 * 方法名与返回结构，因此换后端不需要改页面。
 * </p>
 * <h3>为什么 extends Engine</h3>
 * <p>
 * Otnl 引擎同时是 ORM 通道的宿主（{@link Engine} 的 {@code query(Class)}）。
 * 可观测数据本身是「实体集合」（服务、实例、端点、链路、Span、指标点、告警、日志、
 * 拓扑节点与调用边），把它们当实体看，业务侧就能用与数据库一致的 Lambda 链式写法
 * 取数，而不是每个查询各记一个方法签名：
 * </p>
 * <pre>{@code
 * List<OtnlService> slow = engine.query(OtnlService.class)
 *         .ge(OtnlService::errorRate, 1.0)
 *         .orderByDesc(OtnlService::errorRate)
 *         .limit(20)
 *         .list();
 * }</pre>
 * <p>
 * APM 引擎是<b>只读</b>的：没有数据源、没有 DDL、不接受写入。因此
 * {@link #addDataSource} / {@link #store} / {@link #insert} 一律抛
 * {@link UnsupportedOperationException}，而 {@link #update(Class)} /
 * {@link #delete(Class)} 返回的包装器在执行写入时同样拒绝 —— 显式拒绝比静默无操作安全。
 * </p>
 * <h3>实现约定</h3>
 * <ul>
 *   <li>实现类用 {@code @Spi("名称")} 标注，并在
 *       {@code META-INF/extensions/com.chua.common.support.apm.OtnlEngine} 中登记；</li>
 *   <li>任何后端错误都不得向上抛异常，统一降级为空列表 + 通过 {@link #status()} 暴露原因
 *       （页面宁可显示「暂无数据/后端不可达」，也不能整页 500）；</li>
 *   <li>不支持的查询返回 {@link List#of()}，不要把「不支持」伪装成空数据以外的形状；</li>
 *   <li>ORM 通道按需覆盖 {@link #dataset(Class)}，未覆盖的实体类型返回空列表；
 *       实现只需关心「某种实体的一批行」，过滤/排序/分页由 {@link #query(Class)} 负责；</li>
 *   <li>时间统一毫秒时间戳，耗时统一毫秒。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface OtnlEngine extends Engine {

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
     * 能力标识：告警分析（与 {@link #CAP_ALARMS} 同源，前端单独一页时用它判断）
     */
    String CAP_ALERTS = "alerts";

    /**
     * 能力标识：日志
     */
    String CAP_LOGS = "logs";

    /**
     * 能力标识：性能剖析（Profile / 线程栈分析）
     */
    String CAP_PROFILES = "profiles";

    /**
     * ORM 通道单次取数的默认行数上限（避免一次把整库实体读进内存）
     */
    int ORM_FETCH_LIMIT = 2000;

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
                CAP_TOPOLOGY, CAP_METRICS, CAP_RUNTIME, CAP_ALARMS, CAP_ALERTS, CAP_LOGS);
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
     * 批量指标时序（多实体对比页用）。
     * <p>
     * 默认实现逐条调用 {@link #metrics(OtnlMetricQuery)}：单条失败只丢那一条曲线，
     * 不会让整张对比图空白 —— 对比页最常见的情况就是「某个实例刚下线」。
     * </p>
     * <p>
     * 后端若真有批量入口（例如一次请求返回多条曲线）可覆写为一次请求；
     * 但目前对接过的 APM 都没有这种入口（SkyWalking 的 {@code readMetricsValues}
     * 只接受单个 condition），所以这是<b>唯一</b>实现，不是兜底路径。
     * </p>
     *
     * @param queries 查询列表
     * @return 时序列表，与入参顺序一致（失败项返回空时序）
     */
    default List<OtnlMetricSeries> metricsBatch(List<OtnlMetricQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        List<OtnlMetricSeries> result = new ArrayList<>(queries.size());
        for (OtnlMetricQuery query : queries) {
            try {
                result.add(metrics(query));
            } catch (Exception e) {
                result.add(OtnlMetricSeries.empty(query == null ? null : query.name(),
                        query == null ? OtnlScope.SERVICE : query.scopeOrService(), null));
            }
        }
        return result;
    }

    /**
     * 多服务拓扑（多实体对比页用）。
     * <p>
     * 默认实现逐个服务取拓扑再合并节点与调用边（按 ID 去重）。
     * SkyWalking 有 {@code getServicesTopologyByNames} 时覆写为一次请求。
     * </p>
     *
     * @param serviceNames 服务名列表
     * @param query        时间窗口等条件
     * @return 合并后的拓扑
     */
    default OtnlTopology multiTopology(List<String> serviceNames, OtnlQuery query) {
        if (serviceNames == null || serviceNames.isEmpty() || query == null) {
            return OtnlTopology.empty();
        }
        Map<String, OtnlTopology.OtnlTopologyNode> nodes = new LinkedHashMap<>();
        Map<String, OtnlTopology.OtnlTopologyCall> calls = new LinkedHashMap<>();
        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            OtnlTopology topology = topology(query.withService(serviceName));
            if (topology == null) {
                continue;
            }
            if (topology.nodes() != null) {
                for (OtnlTopology.OtnlTopologyNode node : topology.nodes()) {
                    nodes.putIfAbsent(node.id(), node);
                }
            }
            if (topology.calls() != null) {
                for (OtnlTopology.OtnlTopologyCall call : topology.calls()) {
                    calls.putIfAbsent(call.id() == null || call.id().isEmpty()
                            ? call.source() + "->" + call.target() : call.id(), call);
                }
            }
        }
        return new OtnlTopology(new ArrayList<>(nodes.values()), new ArrayList<>(calls.values()));
    }

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
     * 性能剖析任务列表（SkyWalking Profile）
     * <p>
     * 不支持剖析的引擎返回空列表即可 —— 前端据此渲染「引擎不支持剖析」，
     * 而不是显示一个永远空白的表格。
     * </p>
     *
     * @param query 查询条件（{@link OtnlQuery#serviceName()} / {@link OtnlQuery#endpointName()} 过滤）
     * @return 剖析任务列表
     */
    default List<OtnlProfileTask> profileTasks(OtnlQuery query) {
        return List.of();
    }

    /**
     * 剖析任务采样到的链路片段
     *
     * @param taskId 剖析任务 ID
     * @return 片段列表，不支持时返回空列表
     */
    default List<OtnlProfiledSegment> profiledSegments(String taskId) {
        return List.of();
    }

    /**
     * 剖析结果分析（线程栈树）
     *
     * @param taskId 剖析任务 ID
     * @return 分析结果，无数据时返回空结果（非 null）
     */
    default OtnlProfileAnalysis profileAnalysis(String taskId) {
        return OtnlProfileAnalysis.empty(taskId, "当前引擎不支持性能剖析");
    }

    /**
     * 取出某类实体的原始行（ORM 通道的数据面）。
     * <p>
     * 这是 {@link #query(Class)} 的唯一依赖：实现只负责「给我某种实体的一批行」，
     * 条件过滤、排序、分页一律由 {@link OtnlLambdaQueryWrapper} 在内存中完成，
     * 因此新增一种实体不需要重写一遍链式 API。
     * </p>
     * <p>
     * 默认实现按实体类型分发到已有的集合方法（链路走 {@link #traces(OtnlQuery)}，
     * Span 取 {@link #trace(String)} 的明细之外的全部链路），未识别的类型返回空列表。
     * </p>
     *
     * @param entityClass 实体类型
     * @param <T>         实体类型参数
     * @return 实体行（非 null）
     */
    @SuppressWarnings("unchecked")
    default <T> List<T> dataset(Class<T> entityClass) {
        if (entityClass == null) {
            return List.of();
        }
        OtnlQuery window = OtnlQuery.empty().withPage(1, ORM_FETCH_LIMIT);
        if (entityClass == OtnlService.class) {
            return (List<T>) services(window);
        }
        if (entityClass == OtnlTrace.class) {
            return (List<T>) traces(window);
        }
        if (entityClass == OtnlAlarm.class) {
            return (List<T>) alarms(window);
        }
        if (entityClass == OtnlLogRecord.class) {
            return (List<T>) logs(window);
        }
        if (entityClass == OtnlProfileTask.class) {
            return (List<T>) profileTasks(window);
        }
        if (entityClass == OtnlInstance.class) {
            List<T> all = new ArrayList<>();
            for (OtnlService service : services(window)) {
                all.addAll((List<T>) instances(service.name(), window));
            }
            return all;
        }
        if (entityClass == OtnlEndpoint.class) {
            List<T> all = new ArrayList<>();
            for (OtnlService service : services(window)) {
                all.addAll((List<T>) endpoints(service.name(), window));
            }
            return all;
        }
        if (entityClass == OtnlSpan.class) {
            List<T> all = new ArrayList<>();
            for (OtnlTrace summary : traces(window)) {
                OtnlTrace detail = trace(summary.traceId());
                if (detail != null && detail.spans() != null) {
                    all.addAll((List<T>) detail.spans());
                }
            }
            return all;
        }
        return List.of();
    }

    /**
     * 创建 Lambda 查询包装器（ORM 通道）。
     * <p>
     * 与 {@link Engine#query(Class)} 的语义一致，只是执行端换成本引擎的
     * {@link #dataset(Class)}：条件、排序、分页都在内存中作用于实体行。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询包装器
     */
    @Override
    default <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new OtnlLambdaQueryWrapper<>(this, entityClass);
    }

    /**
     * 创建 Lambda 更新包装器（只读引擎：执行时显式拒绝）。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 更新包装器
     */
    @Override
    default <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new OtnlLambdaUpdateWrapper<>(entityClass);
    }

    /**
     * 创建 Lambda 删除包装器（只读引擎：执行时显式拒绝）。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 删除包装器
     */
    @Override
    default <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new OtnlLambdaDeleteWrapper<>(entityClass);
    }

    /**
     * APM 引擎不管理数据源。
     *
     * @param name       数据源名称
     * @param dataSource 数据源
     * @param <T>        底层源类型
     * @return 永不返回
     */
    @Override
    default <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        throw new UnsupportedOperationException("APM 引擎是只读观测通道，不支持注册数据源: " + name());
    }

    /**
     * APM 引擎不提供内存表写入。
     *
     * @param name 存储名称
     * @param data 数据
     * @param <T>  数据类型
     * @return 永不返回
     */
    @Override
    default <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException("APM 引擎是只读观测通道，不支持写入内存表: " + name());
    }

    /**
     * 默认数据源名对只读引擎无意义，保留为无操作。
     *
     * @param name 数据源名称
     * @return this
     */
    @Override
    default Engine setDefaultDataSourceName(String name) {
        return this;
    }

    /**
     * 无 SQL 执行器
     *
     * @param dataSourceName 数据源名称
     * @return 恒为 null
     */
    @Override
    default SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    /**
     * 无 SQL 执行器
     *
     * @return 恒为 null
     */
    @Override
    default SqlExecutor getExecutor() {
        return null;
    }

    /**
     * 无数据源
     *
     * @param name 数据源名称
     * @param <T>  底层源类型
     * @return 恒为 null
     */
    @Override
    default <T> EngineDataSource<T> getDataSource(String name) {
        return null;
    }

    /**
     * 无数据源
     *
     * @param <T> 底层源类型
     * @return 恒为 null
     */
    @Override
    default <T> EngineDataSource<T> getDataSource() {
        return null;
    }

    /**
     * 无方言
     *
     * @param dataSourceName 数据源名称
     * @return 恒为 null
     */
    @Override
    default Dialect getDialect(String dataSourceName) {
        return null;
    }

    /**
     * 只读引擎不支持原生语句
     *
     * @return 恒为 false
     */
    @Override
    default boolean supportsSql() {
        return false;
    }

    /**
     * 释放底层资源（HTTP 连接池等），默认无动作
     */
    @Override
    default void close() {
        // 默认无需释放
    }
}
