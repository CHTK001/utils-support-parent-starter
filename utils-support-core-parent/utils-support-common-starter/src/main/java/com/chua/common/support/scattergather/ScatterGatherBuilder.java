package com.chua.common.support.scattergather;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Arrays;
import java.util.List;

/**
 * Scatter-Gather 链式构建器。
 * <p>以链式 API 方式配置远程客户端与节点服务，内部使用 {@link ScatterGatherSetting} 承载配置。</p>
 *
 * @param <B> 构建器子类型
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("unchecked")
public class ScatterGatherBuilder<B extends ScatterGatherBuilder<B>> {

    /**
     * 默认传输协议：tcp
     */
    private static final String DEFAULT_TRANSPORT_PROTOCOL = "tcp";

    /**
     * 配置对象
     */
    protected final ScatterGatherSetting setting;

    /**
     * 默认构造，使用默认配置。
     */
    public ScatterGatherBuilder() {
        this(new ScatterGatherSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 配置对象
     */
    public ScatterGatherBuilder(ScatterGatherSetting setting) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器实例
     */
    public static ScatterGatherBuilder<?> builder() {
        return new ScatterGatherBuilder<>();
    }

    /**
     * 设置节点ID。
     *
     * @param nodeId 节点ID
     * @return 当前构建器
     */
    public B nodeId(String nodeId) {
        setting.setNodeId(nodeId);
        return (B) this;
    }

    /**
     * 设置主机地址。
     *
     * @param host 主机
     * @return 当前构建器
     */
    public B host(String host) {
        setting.setHost(host);
        return (B) this;
    }

    /**
     * 设置端口。
     *
     * @param port 端口
     * @return 当前构建器
     */
    public B port(int port) {
        setting.setTcpPort(port);
        return (B) this;
    }

    /**
     * 设置传输协议。
     *
     * @param protocol tcp / udp
     * @return 当前构建器
     */
    public B protocol(String protocol) {
        setting.setTransportProtocol(protocol);
        return (B) this;
    }

    /**
     * 设置 TCP 模式。
     *
     * @param mode seed / auto
     * @return 当前构建器
     */
    public B tcpMode(String mode) {
        setting.setTcpMode(mode);
        return (B) this;
    }

    /**
     * 设置 seed 节点地址列表。
     *
     * @param addresses seed 地址（host 或 host:port）
     * @return 当前构建器
     */
    public B seeds(String... addresses) {
        setting.setSeedAddresses(Arrays.asList(addresses));
        return (B) this;
    }

    /**
     * 设置 seed 节点地址列表。
     *
     * @param addresses seed 地址列表
     * @return 当前构建器
     */
    public B seeds(List<String> addresses) {
        setting.setSeedAddresses(addresses);
        return (B) this;
    }

    /**
     * 设置默认全局端口。
     *
     * @param defaultPort 默认端口
     * @return 当前构建器
     */
    public B defaultPort(int defaultPort) {
        setting.setDefaultPort(defaultPort);
        return (B) this;
    }

    /**
     * 启用 UDP 广播。
     *
     * @return 当前构建器
     */
    public B udpBroadcast() {
        setting.setUdpBroadcast(true);
        return (B) this;
    }

    /**
     * 设置 UDP 广播地址。
     *
     * @param address 广播地址
     * @return 当前构建器
     */
    public B udpBroadcastAddress(String address) {
        setting.setUdpBroadcastAddress(address);
        return (B) this;
    }

    /**
     * 设置是否启用 UDP 降级 TCP。
     *
     * @param enabled 是否启用
     * @return 当前构建器
     */
    public B udpFallbackToTcp(boolean enabled) {
        setting.setUdpFallbackToTcp(enabled);
        return (B) this;
    }

    /**
     * 设置是否在关闭时清除资源。
     *
     * @param cleanup 是否清除
     * @return 当前构建器
     */
    public B cleanupOnClose(boolean cleanup) {
        setting.setCleanupOnClose(cleanup);
        return (B) this;
    }

    /**
     * 设置服务路径。
     *
     * @param servicePath 服务路径
     * @return 当前构建器
     */
    public B servicePath(String servicePath) {
        setting.setServicePath(servicePath);
        return (B) this;
    }

    /**
     * 设置超时时间。
     *
     * @param timeoutMillis 超时（毫秒）
     * @return 当前构建器
     */
    public B timeout(long timeoutMillis) {
        setting.setTimeoutMillis(timeoutMillis);
        return (B) this;
    }

    /**
     * 设置自动检索间隔。
     *
     * @param intervalMillis 间隔（毫秒）
     * @return 当前构建器
     */
    public B autoDiscoveryInterval(long intervalMillis) {
        setting.setAutoDiscoveryIntervalMillis(intervalMillis);
        return (B) this;
    }

    /**
     * 获取配置对象。
     *
     * @return 配置对象
     */
    public ScatterGatherSetting setting() {
        return setting;
    }

    /**
     * 构建远程客户端。
     * <p>按传输协议通过 SPI 创建对应的协议实现，后续新增协议（如 kcp）无需改动本类。</p>
     *
     * @return 远程客户端
     */
    public ScatterGatherRemoteClient<Object> buildClient() {
        return ServiceProvider.of(ScatterGatherRemoteClient.class).getNewExtension(resolveProtocol(), setting);
    }

    /**
     * 构建节点服务。
     * <p>按传输协议通过 SPI 创建对应的协议实现，后续新增协议（如 kcp）无需改动本类。</p>
     *
     * @return 节点服务
     */
    public ScatterGatherNodeServer buildNodeServer() {
        return ServiceProvider.of(ScatterGatherNodeServer.class).getNewExtension(resolveProtocol(), setting);
    }

    /**
     * 解析传输协议，为空时使用默认 tcp。
     *
     * @return 协议名称
     */
    private String resolveProtocol() {
        if (setting.getTransportProtocol() == null || setting.getTransportProtocol().trim().isEmpty()) {
            return DEFAULT_TRANSPORT_PROTOCOL;
        }
        return setting.getTransportProtocol();
    }
}
