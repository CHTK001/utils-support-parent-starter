package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.Event;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.discovery.ServiceDiscoveryListener;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 基于现有 ServiceDiscovery 的节点发现支持。
 * <p>负责节点注册、心跳、故障容忍及服务订阅。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ScatterGatherDiscoverySupport {

    /**
     * 服务发现实例
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting;

    /**
     * 运行状态
     */
    private final AtomicBoolean isStart = new AtomicBoolean(false);

    /**
     * 心跳执行器
     */
    private ScheduledExecutorService heartbeatExecutor;

    /**
     * 故障容忍控制器缓存
     */
    private final Map<String, ScatterGatherFaultTolerance> faultTolerances = new ConcurrentHashMap<>();

    /**
     * 构造发现支持。
     *
     * @param serviceDiscovery 服务发现实例
     * @param setting          节点配置
     */
    public ScatterGatherDiscoverySupport(ServiceDiscovery serviceDiscovery, ScatterGatherSetting setting) {
        this.serviceDiscovery = Objects.requireNonNull(serviceDiscovery, "服务发现不能为空");
        this.setting = Objects.requireNonNull(setting, "配置不能为空");
    }

    /**
     * 注册TCP节点。
     */
    public void registerTcpNode() {
        Discovery discovery = Discovery.builder()
                .id(setting.getNodeId())
                .serverId(setting.getNodeId())
                .protocol("tcp")
                .host(setting.getHost())
                .port(setting.getTcpPort())
                .timeout((int) setting.getTimeoutMillis())
                .weight(1D)
                .metadata(Map.of(
                        "heartbeatInterval", String.valueOf(setting.getHeartbeatIntervalMillis()),
                        "servicePath", setting.getServicePath()
                ))
                .build();
        serviceDiscovery.registerService(setting.getServicePath(), discovery);
    }

    /**
     * 注册HTTP API节点。
     */
    public void registerHttpApi() {
        if (!setting.isHttpApiEnabled() || setting.getHttpPort() <= 0) {
            return;
        }
        serviceDiscovery.registerService(setting.getApiPath(), Discovery.builder()
                .id(setting.getNodeId() + "-http")
                .serverId(setting.getNodeId())
                .protocol("http")
                .host(setting.getHost())
                .port(setting.getHttpPort())
                .timeout((int) setting.getTimeoutMillis())
                .weight(1D)
                .build());
    }

    /**
     * 启用心跳。
     */
    public void startHeartbeat() {
        if (!setting.isHeartbeatEnabled() || isStart.get()) {
            return;
        }
        isStart.set(true);
        heartbeatExecutor = ThreadUtils.newSingleThreadScheduledExecutor(
                ThreadUtils.newThreadFactory("scatter-gather-heartbeat"));
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat,
                setting.getHeartbeatIntervalMillis(),
                setting.getHeartbeatIntervalMillis(),
                TimeUnit.MILLISECONDS);
        log.info("心跳服务已启动，间隔: {}ms", setting.getHeartbeatIntervalMillis());
    }

    /**
     * 停止心跳。
     */
    public void stopHeartbeat() {
        isStart.set(false);
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            heartbeatExecutor = null;
        }
    }

    /**
     * 发送心跳。
     */
    private void sendHeartbeat() {
        try {
            serviceDiscovery.registerService(setting.getServicePath(), Discovery.builder()
                    .id(setting.getNodeId())
                    .serverId(setting.getNodeId())
                    .protocol("tcp")
                    .host(setting.getHost())
                    .port(setting.getTcpPort())
                    .timeout((int) setting.getTimeoutMillis())
                    .weight(1D)
                    .metadata(Map.of(
                            "heartbeatInterval", String.valueOf(setting.getHeartbeatIntervalMillis()),
                            "servicePath", setting.getServicePath(),
                            "lastHeartbeat", String.valueOf(System.currentTimeMillis())
                    ))
                    .build());
        } catch (Exception e) {
            log.warn("心跳发送失败: {}", e.getMessage());
        }
    }

    /**
     * 获取故障容忍控制器。
     *
     * @param nodeId 节点ID
     * @return 故障容忍控制器
     */
    public ScatterGatherFaultTolerance getFaultTolerance(String nodeId) {
        return faultTolerances.computeIfAbsent(nodeId,
                k -> new ScatterGatherFaultTolerance(setting.getFailureThreshold(), setting.getRecoveryThreshold()));
    }

    /**
     * 获取故障容忍控制器。
     *
     * @param nodeId   节点ID
     * @param handler  故障处理器
     * @return 故障容忍控制器
     */
    public ScatterGatherFaultTolerance getFaultTolerance(String nodeId, ScatterGatherFaultHandler handler) {
        return faultTolerances.computeIfAbsent(nodeId,
                k -> new ScatterGatherFaultTolerance(setting.getFailureThreshold(), setting.getRecoveryThreshold(), handler));
    }

    /**
     * 获取所有有效节点（过滤故障节点）。
     *
     * @return 有效节点列表
     */
    public List<ScatterGatherNode> peers() {
        Set<Discovery> discoveries = serviceDiscovery.getServiceAll(setting.getServicePath());
        log.debug("peers: 查询服务 {}，发现 {} 个服务", setting.getServicePath(), discoveries == null ? 0 : discoveries.size());
        if (discoveries == null || discoveries.isEmpty()) {
            return new ArrayList<>();
        }
        List<ScatterGatherNode> allNodes = discoveries.stream()
                .filter(Objects::nonNull)
                .filter(discovery -> "tcp".equalsIgnoreCase(discovery.getProtocol()))
                .filter(discovery -> !isSelf(discovery))
                .map(ScatterGatherNode::from)
                .collect(Collectors.toList());

        if (allNodes.isEmpty()) {
            return allNodes;
        }

        // 过滤故障节点
        List<ScatterGatherNode> healthyNodes = allNodes.stream()
                .filter(node -> {
                    ScatterGatherFaultTolerance ft = faultTolerances.get(node.getNodeId());
                    boolean healthy = ft == null || !ft.isFaulty(node.getNodeId());
                    log.debug("节点 {} 健康状态: {}", node.getNodeId(), healthy);
                    return healthy;
                })
                .collect(Collectors.toList());

        // 根据 balance 配置选择节点
        return selectByBalance(healthyNodes);
    }

    /**
     * 获取所有有效节点（兼容旧API）。
     *
     * @return 有效节点集合
     */
    public Set<ScatterGatherNode> peersSet() {
        return new LinkedHashSet<>(peers());
    }

    /**
     * 根据负载均衡策略选择节点。
     *
     * @param nodes 所有节点
     * @return 选中的节点
     */
    private List<ScatterGatherNode> selectByBalance(List<ScatterGatherNode> nodes) {
        if (nodes.isEmpty()) {
            return nodes;
        }

        String balance = setting.getBalance();
        if (balance == null || balance.isEmpty()) {
            return nodes;
        }

        switch (balance.toLowerCase()) {
            case "random":
                Collections.shuffle(nodes);
                return nodes;
            case "roundrobin":
            case "round-robin":
                return nodes;
            case "weight":
                return nodes.stream()
                        .sorted((a, b) -> {
                            double weightA = getNodeWeight(a);
                            double weightB = getNodeWeight(b);
                            return Double.compare(weightB, weightA);
                        })
                        .collect(Collectors.toList());
            default:
                return nodes;
        }
    }

    private double getNodeWeight(ScatterGatherNode node) {
        String weightStr = node.getMetadata().get("weight");
        if (weightStr != null) {
            try {
                return Double.parseDouble(weightStr);
            } catch (NumberFormatException e) {
                return 1.0;
            }
        }
        return 1.0;
    }

    /**
     * 订阅服务发现变更。
     *
     * @param listener 变更监听器
     */
    public void subscribe(ServiceDiscoveryListener listener) {
        if (serviceDiscovery.isSupportSubscribe()) {
            serviceDiscovery.subscribe(setting.getServicePath(), listener);
        }
    }

    private boolean isSelf(Discovery discovery) {
        if (setting.getNodeId().equals(discovery.getId()) || setting.getNodeId().equals(discovery.getServerId())) {
            return true;
        }
        return setting.getHost().equals(discovery.getHost()) && setting.getTcpPort() == discovery.getPort();
    }

    /**
     * 关闭资源。
     */
    public void close() {
        stopHeartbeat();
        faultTolerances.clear();
    }
}
