package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.DiscoveryOption;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullUnmarked;

/**
 * Scatter-Gather 节点配置。
 * <p>集中管理节点、发现、容错、重试、心跳等所有配置项。</p>
 *
 * @author CH
 */
@NullUnmarked
@Getter
@Setter
@Accessors(chain = true)
public class ScatterGatherSetting {

    /**
     * 节点ID，默认 "local"
     */
    private String nodeId = "local";

    /**
     * 主机地址，默认 127.0.0.1
     */
    private String host = "127.0.0.1";

    /**
     * TCP端口，默认 19001
     */
    private int tcpPort = 19001;

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
    private String servicePath = "/scatter-gather";

    /**
     * API路径，默认 /scatter-gather/query
     */
    private String apiPath = "/scatter-gather/query";

    /**
     * 负载均衡策略，默认 weight
     */
    private String balance = "weight";

    /**
     * 查询超时时间（毫秒），默认 3000
     */
    private long timeoutMillis = 3000L;

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
    private long deduplicationTtlMillis = 30_000L;

    /**
     * 远程超时时间（毫秒），默认 3000
     */
    private long remoteTimeoutMillis = 3000L;

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
    private int failureThreshold = 3;

    /**
     * 恢复阈值，默认 1
     */
    private int recoveryThreshold = 1;

    /**
     * 故障检查间隔（毫秒），默认 5000
     */
    private long faultCheckIntervalMillis = 5000L;

    /**
     * 最大重试次数，默认 3
     */
    private int maxRetries = 3;

    /**
     * 重试延迟（毫秒），默认 500
     */
    private long retryDelayMillis = 500L;

    /**
     * 是否启用重试，默认 true
     */
    private boolean retryEnabled = true;

    /**
     * HTTP方法，默认 POST
     */
    private String httpMethod = "POST";

    /**
     * HTTP API路径（多路径逗号分隔），默认 /scatter-gather/query
     */
    private String httpApiPaths = "/scatter-gather/query";

    /**
     * 心跳间隔（毫秒），默认 30秒
     */
    private long heartbeatIntervalMillis = 30_000L;

    /**
     * 是否启用心跳，默认 true
     */
    private boolean heartbeatEnabled = true;
}
