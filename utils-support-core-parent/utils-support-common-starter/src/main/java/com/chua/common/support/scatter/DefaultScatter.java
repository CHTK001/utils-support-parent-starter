package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 默认 Scatter 对等节点。
 * <p>一个节点 = 服务发现(hash 表) + 自身即 TCP 代理服务器：
 * 同一端口承载数据同步与心跳，HTTP/TCP 请求按 discovery(groupId + 协议) 动态转发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultScatter implements Scatter {

    /**
     * 服务发现
     */
    private final DefaultScatterServiceDiscovery discovery;

    /**
     * TCP 代理服务器
     */
    private final TcpProxyServer proxy;

    /**
     * 节点配置
     */
    private final ScatterSetting setting;

    /**
     * 业务分组
     */
    private final String groupId;

    /**
     * 构造 Scatter 节点。
     *
     * @param setting 节点配置
     */
    public DefaultScatter(ScatterSetting setting) {
        this.setting = setting == null ? new ScatterSetting() : setting;
        this.groupId = this.setting.effectiveGroupId();
        this.discovery = new DefaultScatterServiceDiscovery(this.setting);

        // 自身即 TCP 代理:按 discovery(groupId + tcp 协议 + weight 策略)解析目标节点并转发
        ServerSetting proxySetting = ServerSetting.defaults();
        proxySetting.setHost(this.setting.getHost());
        proxySetting.setPort(this.setting.getPort());
        this.proxy = new TcpProxyServer(proxySetting,
                new DiscoveryProxyTargetResolver(this.discovery, this.setting.getServicePath(),
                        this.groupId, this.setting.getBalance()));
    }

    /**
     * 启动节点（同时启动 discovery 与 TCP 代理，共用本节点端口）。
     */
    @Override
    public void start() throws Exception {
        discovery.start();
        proxy.start();
        log.info("Scatter 节点已启动: {}:{} (discovery + tcp-proxy 共用端口, groupId={})",
                setting.getHost(), proxy.getPort(), groupId);
    }

    /**
     * 停止节点。
     */
    @Override
    public void stop() throws Exception {
        try {
            proxy.close();
        } catch (Exception ignored) {
        }
        discovery.stop();
        log.info("Scatter 节点已停止");
    }

    /**
     * 获取服务发现（节点表/路由能力）。
     *
     * @return 服务发现实例
     */
    @Override
    public ScatterServiceDiscovery discovery() {
        return discovery;
    }

    /**
     * 获取通信端口。
     *
     * @return 端口
     */
    @Override
    public int getPort() {
        return proxy.getPort();
    }

    /**
     * 便捷：注册本节点对外提供的服务路径（供集群内其他节点发现，按 groupId 分组）。
     *
     * @param paths 服务路径列表
     */
    public void registerServicePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        for (String path : paths) {
            Discovery self = Discovery.builder()
                    .serverId(setting.getNodeId())
                    .scatterId(groupId)
                    .protocol(setting.getProtocol())
                    .host(setting.getHost())
                    .port(getPort())
                    .weight(1)
                    .build();
            discovery.registerService(path, self);
        }
        log.info("Scatter 服务已注册: paths={}, groupId={}", paths, groupId);
    }

    /**
     * 关闭节点，委托 stop。
     */
    @Override
    public void close() throws Exception {
        stop();
    }
}
