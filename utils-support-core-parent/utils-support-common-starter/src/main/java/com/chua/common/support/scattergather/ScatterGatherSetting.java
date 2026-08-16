package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.DiscoveryOption;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Scatter-Gather 节点配置。
 * <p>集中管理节点、发现、容错、重试、心跳等所有配置项。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
@Accessors(chain = true)
public class ScatterGatherSetting {

    /**
     * 默认节点ID
     */
    private static final String DEFAULT_NODE_ID = "local";

    /**
     * 默认主机地址
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 默认 TCP 端口
     */
    private static final int DEFAULT_TCP_PORT = 19001;

    /**
     * 默认服务注册路径
     */
    private static final String DEFAULT_SERVICE_PATH = "/scatter-gather";

    /**
     * 默认 API 路径
     */
    private static final String DEFAULT_API_PATH = "/scatter-gather/query";

    /**
     * 默认负载均衡策略
     */
    private static final String DEFAULT_BALANCE = "weight";

    /**
     * 默认查询超时时间（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;

    /**
     * 默认去重 TTL（毫秒）
     */
    private static final long DEFAULT_DEDUPLICATION_TTL_MILLIS = 30_000L;

    /**
     * 默认故障阈值
     */
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /**
     * 默认恢复阈值
     */
    private static final int DEFAULT_RECOVERY_THRESHOLD = 1;

    /**
     * 默认故障检查间隔（毫秒）
     */
    private static final long DEFAULT_FAULT_CHECK_INTERVAL_MILLIS = 5000L;

    /**
     * 默认最大重试次数
     */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 默认重试延迟（毫秒）
     */
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 500L;

    /**
     * 默认 HTTP 方法
     */
    private static final String DEFAULT_HTTP_METHOD = "POST";

    /**
     * 默认心跳间隔（毫秒）
     */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    /**
     * 默认传输协议
     */
    private static final String DEFAULT_TRANSPORT_PROTOCOL = "tcp";

    /**
     * 默认 UDP 广播地址
     */
    private static final String DEFAULT_UDP_BROADCAST_ADDRESS = "255.255.255.255";

    /**
     * 默认 TCP 模式
     */
    private static final String DEFAULT_TCP_MODE = "auto";

    /**
     * 默认自动检索间隔（毫秒）
     */
    private static final long DEFAULT_AUTO_DISCOVERY_INTERVAL_MILLIS = 60_000L;

    /**
     * 节点ID，默认 "local"
     */
    private String nodeId = DEFAULT_NODE_ID;

    /**
     * 主机地址，默认 127.0.0.1
     */
    private String host = DEFAULT_HOST;

    /**
     * TCP端口，默认 19001
     */
    private int tcpPort = DEFAULT_TCP_PORT;

    /**
     * HTTP端口
     */
    private int httpPort;

    /**
     * 是否启用HTTP API
     */
    private boolean httpApiEnabled;

    /**
     * 服务注册路径，默认 /scatter-gather
     */
    private String servicePath = DEFAULT_SERVICE_PATH;

    /**
     * API路径，默认 /scatter-gather/query
     */
    private String apiPath = DEFAULT_API_PATH;

    /**
     * 负载均衡策略，默认 weight
     */
    private String balance = DEFAULT_BALANCE;

    /**
     * 查询超时时间（毫秒），默认 3000
     */
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    /**
     * 服务发现选项
     */
    private DiscoveryOption discoveryOption = new DiscoveryOption();

    /**
     * 远程并发度，默认无限
     */
    private int remoteConcurrency = Integer.MAX_VALUE;

    /**
     * 是否启用去重
     */
    private boolean deduplicationEnabled = false;

    /**
     * 去重TTL（毫秒），默认 30秒
     */
    private long deduplicationTtlMillis = DEFAULT_DEDUPLICATION_TTL_MILLIS;

    /**
     * 远程超时时间（毫秒），默认 3000
     */
    private long remoteTimeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    /**
     * 是否启用降级
     */
    private boolean enableFallback = false;

    /**
     * 降级结果
     */
    private Object fallbackResult;

    /**
     * 故障阈值，默认 3
     */
    private int failureThreshold = DEFAULT_FAILURE_THRESHOLD;

    /**
     * 恢复阈值，默认 1
     */
    private int recoveryThreshold = DEFAULT_RECOVERY_THRESHOLD;

    /**
     * 故障检查间隔（毫秒），默认 5000
     */
    private long faultCheckIntervalMillis = DEFAULT_FAULT_CHECK_INTERVAL_MILLIS;

    /**
     * 最大重试次数，默认 3
     */
    private int maxRetries = DEFAULT_MAX_RETRIES;

    /**
     * 重试延迟（毫秒），默认 500
     */
    private long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;

    /**
     * 是否启用重试，默认 true
     */
    private boolean retryEnabled = true;

    /**
     * HTTP方法，默认 POST
     */
    private String httpMethod = DEFAULT_HTTP_METHOD;

    /**
     * HTTP API路径（多路径逗号分隔），默认 /scatter-gather/query
     */
    private String httpApiPaths = DEFAULT_API_PATH;

    /**
     * 心跳间隔（毫秒），默认 30秒
     */
    private long heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS;

    /**
     * 是否启用心跳，默认 true
     */
    private boolean heartbeatEnabled = true;

    // ======================== 新增配置项 ========================

    /**
     * 传输协议：tcp / udp，默认 tcp
     */
    private String transportProtocol = DEFAULT_TRANSPORT_PROTOCOL;

    /**
     * UDP 是否广播模式，默认 false
     */
    private boolean udpBroadcast = false;

    /**
     * UDP 广播地址，默认 255.255.255.255
     */
    private String udpBroadcastAddress = DEFAULT_UDP_BROADCAST_ADDRESS;

    /**
     * 是否启用 UDP 降级 TCP 回退，默认 true
     */
    private boolean udpFallbackToTcp = true;

    /**
     * TCP 模式：seed / auto / bootstrap，默认 auto
     */
    private String tcpMode = DEFAULT_TCP_MODE;

    /**
     * Bootstrap 引导节点地址（host 或 host:port），默认空。
     * <p>bootstrap 模式下各节点与引导节点一次性交换 hash，用于节点互认。</p>
     */
    private String bootstrapNode;

    /**
     * Seed 节点地址列表（host:port 格式），默认空
     */
    private java.util.List<String> seedAddresses = new java.util.ArrayList<>();

    /**
     * 默认全局端口，当 seed 地址未指定端口时使用，默认 19001
     */
    private int defaultPort = DEFAULT_TCP_PORT;

    /**
     * 是否在关闭时清除资源，默认 true
     */
    private boolean cleanupOnClose = true;

    /**
     * 自动检索间隔（毫秒），默认 60秒
     */
    private long autoDiscoveryIntervalMillis = DEFAULT_AUTO_DISCOVERY_INTERVAL_MILLIS;
}
