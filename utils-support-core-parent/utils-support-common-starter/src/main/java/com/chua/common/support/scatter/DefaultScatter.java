package com.chua.common.support.scatter;

import com.chua.common.support.scatter.discovery.AbstractScatterDiscovery;
import com.chua.common.support.scatter.node.ScatterNodeServer;
import lombok.extern.slf4j.Slf4j;

/**
 * scatter 聚合实现：组装发现服务 + 节点服务端，统一生命周期。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultScatter implements Scatter {

    private final ScatterBuilder<?> builder;
    private final ScatterSetting setting;
    /**
     * discovery
    */
    private AbstractScatterDiscovery discovery;
    /**
     * 节点服务端
    */
    private ScatterNodeServer nodeServer;

    /**
     * 构造方法，创建 DefaultScatter 实例。
     *
     * @param builder 方法入参 builder
     */
    public DefaultScatter(ScatterBuilder<?> builder) {
        this.builder = builder;
        this.setting = builder.setting;
    }

    @Override
    public void start() throws Exception {
        discovery = builder.buildDiscovery();

        // ① 先启动 nodeServer，绑定端口（port=0 时由系统分配）
        nodeServer = builder.buildNodeServer(discovery);
        nodeServer.start();
        // 记录 scatter 通信端口，不覆盖原始业务端口（保持 port 为业务端口）
        setting.setScatterPort(nodeServer.getPort());

        // ② 注册自身（使用正确的 scatter 通信端口作为 seed 地址）
        discovery.registerSelf();
        discovery.start();
        // 等待第一轮 discoveryRound 完成，确保本节点信息已写入本地 hash 表，
        // 其他节点连接时能立即查到本节点的服务条目
        Thread.sleep(Math.min(setting.getAutoDiscoveryIntervalMillis(), 500L));

        log.info("Scatter 已启动: node={} protocol={} @ {}:{} mode={}",
                setting.getNodeId(), setting.getProtocol(),
                setting.effectiveHost(), setting.getScatterPort(),
                setting.getSubnet() != null && !setting.getSubnet().isBlank() ? "route" : "seed");
    }

    @Override
    public void stop() throws Exception {
        if (nodeServer != null) {
            nodeServer.stop();
        }
        if (discovery != null) {
            // 优雅关闭：等待当前一轮完成，确保 removeFromCache 不会因中断而跳过
            ((AbstractScatterDiscovery) discovery).gracefulClose();
        }
        log.info("Scatter 已停止: node={}", setting.getNodeId());
    }

    @Override
    public ScatterServiceDiscovery discovery() {
        return discovery;
    }

    @Override
    public int getPort() {
        return nodeServer != null ? nodeServer.getPort() : setting.getScatterPort();
    }
}
