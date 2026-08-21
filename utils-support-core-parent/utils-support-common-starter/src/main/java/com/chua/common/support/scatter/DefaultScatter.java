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
    private AbstractScatterDiscovery discovery;
    private ScatterNodeServer nodeServer;

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
        setting.setPort(nodeServer.getPort());

        // ② 先注册自身，再启动 discovery（避免首轮定时任务在端口未确认前触发）
        discovery.registerSelf();
        discovery.start();

        log.info("Scatter 已启动: node={} protocol={} @ {}:{} mode={}",
                setting.getNodeId(), setting.getProtocol(),
                setting.effectiveHost(), setting.getPort(),
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
        return nodeServer != null ? nodeServer.getPort() : setting.getPort();
    }
}
