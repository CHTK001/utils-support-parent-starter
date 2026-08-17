package com.chua.common.support.scatter;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * Scatter 节点配置。
 * <p>对等式无中心化发现：每个节点数据一致，自身即 TCP 代理服务器，
 * 同一端口承载数据同步与心跳；按 groupId（scatterId）业务分组隔离扩散。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@Accessors(chain = true)
public class ScatterSetting {

    /**
     * 默认节点ID
     */
    private static final String DEFAULT_NODE_ID = "local";

    /**
     * 默认主机地址
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 默认端口
     */
    private static final int DEFAULT_PORT = 19001;

    /**
     * 默认服务注册路径
     */
    private static final String DEFAULT_SERVICE_PATH = "/scatter";

    /**
     * 默认负载均衡策略
     */
    private static final String DEFAULT_BALANCE = "weight";

    /**
     * 默认查询超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;

    /**
     * 默认心跳间隔（毫秒）
     */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    /**
     * 默认自动发现间隔（毫秒）
     */
    private static final long DEFAULT_AUTO_DISCOVERY_INTERVAL_MILLIS = 60_000L;

    /**
     * 节点ID
     */
    private String nodeId = DEFAULT_NODE_ID;

    /**
     * 业务分组标识(groupId)：等价于 scatterId，仅与同分组节点扩散互通
     */
    private String groupId = "default";

    /**
     * 业务分组标识(兼容旧名)，与 groupId 等价
     */
    private String scatterId;

    /**
     * 主机地址
     */
    private String host = DEFAULT_HOST;

    /**
     * 通信端口(数据同步 + 心跳共用)
     */
    private int port = DEFAULT_PORT;

    /**
     * HTTP 代理端口(0 = 自动分配)
     */
    private int httpPort;

    /**
     * 传输协议：udp / tcp / kcp
     */
    private String protocol = "tcp";

    /**
     * seed 节点地址列表(host 或 host:port，未指定端口使用 defaultPort)
     */
    private List<String> seeds = new ArrayList<>();

    /**
     * 网段模式网段(如 192.168.1.0/24)，网段模式固定相同端口扩散
     */
    private String subnet;

    /**
     * 默认全局端口，seed 未指定端口时使用
     */
    private int defaultPort = DEFAULT_PORT;

    /**
     * 服务注册路径
     */
    private String servicePath = DEFAULT_SERVICE_PATH;

    /**
     * 负载均衡策略，默认 weight
     */
    private String balance = DEFAULT_BALANCE;

    /**
     * 查询超时（毫秒）
     */
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    /**
     * 心跳间隔（毫秒）
     */
    private long heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS;

    /**
     * 是否启用心跳
     */
    private boolean heartbeatEnabled = true;

    /**
     * 自动发现间隔（毫秒）
     */
    private long autoDiscoveryIntervalMillis = DEFAULT_AUTO_DISCOVERY_INTERVAL_MILLIS;

    /**
     * 动态权重上报(心跳携带 cpu+内存计算)，默认开启
     */
    private boolean dynamicWeight = true;

    /**
     * 是否在关闭时清除资源
     */
    private boolean cleanupOnClose = true;

    /**
     * 获取业务分组标识，scatterId 为空时回落到 groupId。
     *
     * @return 分组标识
     */
    public String effectiveGroupId() {
        return scatterId == null || scatterId.isBlank() ? groupId : scatterId;
    }
}
