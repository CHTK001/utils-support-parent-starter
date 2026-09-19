package com.chua.common.support.network.server.dht;

import com.chua.common.support.network.discovery.DiscoveryOption;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DHT 节点配置。
 * <p>
 * 包含 DHT 协议运行所需的所有配置参数，如端口、K 值、Alpha 并行度、超时时间等。
 * 可通过 {@link #from(DiscoveryOption)} 从通用服务发现配置创建。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder(toBuilder = true)
@Accessors(chain = true)
public class DhtConfig {

    /**
     * UDP 监听端口，默认 6881
     */
    @Builder.Default
    /** 端口 */
    private int port = 6881;

    /**
     * K-Bucket 容量（K 值），默认 8
     */
    @Builder.Default
    /** K存储桶尺寸 */
    private int kBucketSize = 8;

    /**
     * 并行查询节点数（Alpha 值），默认 3
     */
    @Builder.Default
    /** 透明度 */
    private int alpha = 3;

    /**
     * 自动 Bootstrap 间隔（毫秒），默认 60 秒
     */
    @Builder.Default
    /** Bootstrap间隔MS */
    private long bootstrapIntervalMs = 10_000;

    /**
     * 重新发布存储值的间隔（毫秒），默认 300 秒
     */
    @Builder.Default
    /** Republish间隔MS */
    private long republishIntervalMs = 300_000;

    /**
     * 存储值的生存时间（毫秒），默认 600 秒
     */
    @Builder.Default
    /** 值TTLMS */
    private long valueTtlMs = 600_000;

    /**
     * 节点超时时间（毫秒），默认 30 秒
     */
    @Builder.Default
    /** Peer超时MS */
    private long peerTimeoutMs = 30_000;

    /**
     * 节点 标识 字符串（为空则随机生成）
     */
    private String nodeId;

    /**
     * 绑定主机地址
     */
    private String host;

    /**
     * 对外宣告的主机地址（用于 NAT 穿透）
     */
    private String advertisedHost;

    /**
     * 对外宣告的端口号（用于 NAT 穿透）
     */
    private int advertisedPort;

    /**
     * 种子节点地址集合，格式 主机:端口
     */
    private Set<String> seeds;

    /**
     * 从通用服务发现配置创建 DHT 配置。
     *
     * @param discoveryOption 服务发现配置
     * @return DhtConfig 实例
     */
    public static DhtConfig from(DiscoveryOption discoveryOption) {
        DhtConfigBuilder builder = DhtConfig.builder();
        if (discoveryOption == null) {
            return builder.build();
        }
        builder.host(discoveryOption.getAddress());
        if (discoveryOption.getExtra() != null) {
            var opts = discoveryOption.getExtra();
            Object kVal = opts.get("dht.k");
            if (kVal instanceof Number) {
                builder.kBucketSize(((Number) kVal).intValue());
            }
            Object alphaVal = opts.get("dht.alpha");
            if (alphaVal instanceof Number) {
                builder.alpha(((Number) alphaVal).intValue());
            }
            Object vttlVal = opts.get("dht.value-ttl-ms");
            if (vttlVal instanceof Number) {
                builder.valueTtlMs(((Number) vttlVal).longValue());
            }
            Object advHost = opts.get("dht.advertised-host");
            if (advHost instanceof String) {
                builder.advertisedHost((String) advHost);
            }
            Object advPort = opts.get("dht.advertised-port");
            if (advPort instanceof Number) {
                builder.advertisedPort(((Number) advPort).intValue());
            }
            Object portVal = opts.get("dht.port");
            if (portVal instanceof Number) {
                builder.port(((Number) portVal).intValue());
            }
            Object nodeIdVal = opts.get("dht.node-id");
            if (nodeIdVal instanceof String) {
                builder.nodeId((String) nodeIdVal);
            }
            Object seedsVal = opts.get("dht.seeds");
            if (seedsVal instanceof String) {
                Set<String> seedSet = new LinkedHashSet<>();
                for (String s : ((String) seedsVal).split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) {
                        seedSet.add(t);
                    }
                }
                builder.seeds(seedSet);
            }
        }
        return builder.build();
    }

    /**
     * 判断是否配置了对外宣告地址（用于 NAT 穿透）。
     *
     * @return 如果 advertised主机 和 advertised端口 都有效返回 true
     */
    public boolean hasAdvertisedAddress() {
        return advertisedHost != null && !advertisedHost.isEmpty() && advertisedPort > 0;
    }
}
