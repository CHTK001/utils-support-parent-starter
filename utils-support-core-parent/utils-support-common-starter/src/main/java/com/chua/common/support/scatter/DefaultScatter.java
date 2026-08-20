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
        discovery.start();

        // 节点服务端：帧处理由 discovery 承担（REQ 拉取/PUSH 合并）
        nodeServer = builder.buildNodeServer(discovery);
        nodeServer.start();
        setting.setPort(nodeServer.getPort());

        // 注册自身（端口回填后）
        discovery.registerSelf();
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
            discovery.close();
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
