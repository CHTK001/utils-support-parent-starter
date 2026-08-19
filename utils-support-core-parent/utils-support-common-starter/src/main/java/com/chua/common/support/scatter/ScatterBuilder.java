package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Arrays;
import java.util.List;

/**
 * Scatter 链式构建器基类。
 * <p>统一入口仅暴露 {@link #udp()}、{@link #tcp()}、{@link #kcp()} 三个静态方法，
 * 分别返回对应协议的构造对象（协议固定，无需手动选择）。</p>
 *
 * <p>配置项：groupId 业务分组、host/port、seeds 引导节点（host 或 host:port）、
 * subnet 网段模式（固定相同端口扩散）、servicePath、balance 负载均衡等。</p>
 *
 * @param <B> 构建器子类型
 * @since 4.0.0.42
 */
@SuppressWarnings("unchecked")
public abstract class ScatterBuilder<B extends ScatterBuilder<B>> {

    /**
     * 配置对象
     */
    protected final ScatterSetting setting;

    /**
     * 协议标识
     */
    private final String protocol;

    /**
     * 构造构建器。
     *
     * @param protocol 传输协议：udp / tcp / kcp
     */
    protected ScatterBuilder(String protocol) {
        this(protocol, new ScatterSetting());
    }

    /**
     * 构造构建器。
     *
     * @param protocol 传输协议：udp / tcp / kcp
     * @param setting  配置对象
     */
    protected ScatterBuilder(String protocol, ScatterSetting setting) {
        this.protocol = protocol;
        this.setting = setting == null ? new ScatterSetting() : setting;
        this.setting.setProtocol(protocol);
    }

    /**
     * 创建 UDP 广播模式构建器（仅支持广播，seed 设置 224 组播地址）。
     *
     * @return UDP 构建器
     */
    public static UdpScatterBuilder udp() {
        return new UdpScatterBuilder();
    }

    /**
     * 创建 TCP 构建器（seed 引导 / 网段模式）。
     *
     * @return TCP 构建器
     */
    public static TcpScatterBuilder tcp() {
        return new TcpScatterBuilder();
    }

    /**
     * 创建 KCP 构建器（seed 引导 / 网段模式）。
     *
     * @return KCP 构建器
     */
    public static KcpScatterBuilder kcp() {
        return new KcpScatterBuilder();
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
     * 设置业务分组（等价 scatterId，仅与同分组节点扩散）。
     *
     * @param groupId 分组
     * @return 当前构建器
     */
    public B groupId(String groupId) {
        setting.setGroupId(groupId);
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
     * 设置端口（数据同步 + 心跳共用）。
     *
     * @param port 端口
     * @return 当前构建器
     */
    public B port(int port) {
        setting.setPort(port);
        return (B) this;
    }

    /**
     * 设置 seed 节点地址列表（host 或 host:port，未指定端口用默认端口）。
     *
     * @param addresses seed 地址
     * @return 当前构建器
     */
    public B seeds(String... addresses) {
        setting.setSeeds(Arrays.asList(addresses));
        return (B) this;
    }

    /**
     * 设置 seed 节点地址列表。
     *
     * @param addresses seed 地址列表
     * @return 当前构建器
     */
    public B seeds(List<String> addresses) {
        setting.setSeeds(addresses);
        return (B) this;
    }

    /**
     * 设置网段模式（如 192.168.1.0/24，固定相同端口扩散）。
     *
     * @param subnet 网段
     * @return 当前构建器
     */
    public B subnet(String subnet) {
        setting.setSubnet(subnet);
        return (B) this;
    }

    /**
     * 设置默认全局端口（seed 未指定端口时使用）。
     *
     * @param defaultPort 默认端口
     * @return 当前构建器
     */
    public B defaultPort(int defaultPort) {
        setting.setDefaultPort(defaultPort);
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
     * 设置负载均衡策略。
     *
     * @param balance weight / random / round-robin
     * @return 当前构建器
     */
    public B balance(String balance) {
        setting.setBalance(balance);
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
     * 设置自动发现间隔。
     *
     * @param intervalMillis 间隔（毫秒）
     * @return 当前构建器
     */
    public B autoDiscoveryInterval(long intervalMillis) {
        setting.setAutoDiscoveryIntervalMillis(intervalMillis);
        return (B) this;
    }

    /**
     * 设置是否启用动态权重（心跳上报 cpu+内存）。
     *
     * @param dynamicWeight 是否启用
     * @return 当前构建器
     */
    public B dynamicWeight(boolean dynamicWeight) {
        setting.setDynamicWeight(dynamicWeight);
        return (B) this;
    }

    /**
     * 获取配置对象。
     *
     * @return 配置对象
     */
    public ScatterSetting setting() {
        return setting;
    }

    /**
     * 获取传输协议。
     *
     * @return 协议标识
     */
    public String protocol() {
        return protocol;
    }

    /**
     * 构建节点门面（服务发现 + 自身即 TCP 代理）。
     *
     * @return Scatter 实例
     */
    public Scatter build() {
        return new DefaultScatter(setting);
    }

    /**
     * 构建服务发现（无中心化 Inmem 发现）。
     *
     * @return 服务发现实例
     */
    public ScatterServiceDiscovery buildDiscovery() {
        return new DefaultScatterServiceDiscovery(setting);
    }

    /**
     * 构建远程客户端，按当前协议通过 SPI 创建。
     *
     * @return 远程客户端，未找到对应协议实现时返回 null
     */
    @SuppressWarnings("unchecked")
    public ScatterRemoteClient<Discovery> buildRemoteClient() {
        ScatterRemoteClient<?> client = ServiceProvider.of(ScatterRemoteClient.class)
                .getNewExtension(protocol, setting);
        return (ScatterRemoteClient<Discovery>) client;
    }

    /**
     * 构建节点服务，按当前协议通过 SPI 创建。
     *
     * @return 节点服务，未找到对应协议实现时返回 null
     */
    public ScatterNodeServer buildNodeServer() {
        return ServiceProvider.of(ScatterNodeServer.class).getNewExtension(protocol, setting);
    }
}
