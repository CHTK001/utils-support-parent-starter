package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import lombok.Data;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 依赖图 处理器 — 聚合传输事件生成节点-边图。
 *
 * <p>节点 = Endpoint（host:port + protocol + software）；
 * 边 = dependencyedge（源 → Target + call数量 + avg持续时间）。
 * 数据来源：</p>
 * <ul>
 *   <li>订阅 TransmissionHandler 的 TransmissionRecord（Socket 层）</li>
 *   <li>未来订阅 ZkClientHandler / RedisClientHandler（应用语义层）</li>
 * </ul>
 *
 * <p>调用方可通过 {@link #getEdges()} 获取当前依赖图谱。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DependencyGraphHandler implements Plugin {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(DependencyGraphHandler.class.getName());
    /**
     * 插件名称
     */
    private static final String HANDLER_NAME = "dependency-graph-handler";

    /**
     * 插件版本
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置属性 键
     */
    private static final String PROP_DEP_ENABLED = "dependency.enabled";

    /**
     * 默认启用值
     */
    private static final String DEFAULT_ENABLED = "true";

    /**
     * 边集合（edgeid → dependencyedge）
     */
    private final Map<String, DependencyEdge> edges;

    /**
     * 节点集合（节点id → 端点）
     */
    private final Map<String, Endpoint> nodes;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /** 创建 dependency图计算处理器 实例 */
    public DependencyGraphHandler() {
        this.edges = new ConcurrentHashMap<>();
        this.nodes = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** 名称 */
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    /** 版本 */
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_DEP_ENABLED, DEFAULT_ENABLED));
        LOG.log(Level.INFO, String.format("DependencyGraphHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        started.set(true);
        LOG.log(Level.INFO, "DependencyGraphHandler 启动完成，依赖关系追踪已启用");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        started.set(false);
        LOG.log(Level.INFO, "DependencyGraphHandler 停止");
    }

    @Override
    /** 状态 */
    public String status() {
        return String.format("DependencyGraphHandler[enabled=%s, edges=%d, nodes=%d]",
                enabled, edges.size(), nodes.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 记录一次传输，更新依赖图。
     *
     * @param source   源端点
     * @param target   目标端点
     * @param protocol 协议
     * @param software 软件栈
     * @param duration 本次耗时（毫秒）
     * @param isError  是否错误
     * @param error    错误信息（可空）
     */
    public void record(Endpoint source, Endpoint target, Protocol protocol, Software software,
                       long duration, boolean isError, String error) {
        if (!enabled || source == null || target == null) {
            return;
        }
        nodes.putIfAbsent(source.nodeId(), source);
        nodes.putIfAbsent(target.nodeId(), target);
        String edgeId = source.nodeId() + " -> " + target.nodeId();
        DependencyEdge edge = edges.computeIfAbsent(edgeId, k -> DependencyEdge.builder()
                .source(source)
                .target(target)
                .protocol(protocol)
                .software(software)
                .build());
        edge.record(duration, isError, error);
 // 持久化：每次事件单独落盘（不共享 edge 对象，避免 storage 累计覆盖 处理器 内存态）
        try {
            com.chua.runtime.apm.storage.StorageManager.get().appendDependency(
                    DependencyEdge.builder()
                            .source(source)
                            .target(target)
                            .protocol(protocol)
                            .software(software)
                            .callCount(1L)
                            .totalDuration(duration)
                            .errorCount(isError ? 1L : 0L)
                            .lastError(error)
                            .lastCallTime(System.currentTimeMillis())
                            .build());
        } catch (Exception e) {
            LOG.log(Level.FINE, "appendDependency 异常: " + e.getMessage());
        }
        LOG.log(Level.FINE, String.format("[Dependency] %s -> %s (%sms, calls=%s, errors=%s)", source.displayLabel(), target.displayLabel(), duration, edge.getCallCount(), edge.getErrorCount()));
    }

    /**
     * 获取所有依赖边。
     *
     * @return 不可修改的边列表
     */
    public List<DependencyEdge> getEdges() {
        return Collections.unmodifiableList(new ArrayList<>(edges.values()));
    }

    /**
     * 获取所有节点。
     *
     * @return 不可修改的节点列表
     */
    public List<Endpoint> getNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    /**
     * 清空依赖图。
     */
    public void clear() {
        edges.clear();
        nodes.clear();
    }
}