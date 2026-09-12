package com.chua.springboot.support.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
* Scatter / Cluster 集群配置属性。
*
* <p>绑定前缀 {@code chua.cluster}，示例：</p>
* <pre>
* chua:
*   cluster:
*     scatter-id: order
*     seeds: ["127.0.0.1:19001"]
*     port: 8080
*     http-enabled: true
*     tcp-enabled: false
*     balance: weight
*     timeout-millis: 3000
*     server-entries:
*       - service-path: /api
*         host: 192.168.1.10
*         port: 8080
*         protocol: http
*       - service-path: /pay
*         host: 10.0.0.5
*         port: 9001
*         protocol: tcp
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chua.cluster")
public class ClusterProperties {

    /** 节点 标识（缺省自动生成） */
    private String nodeId;

    /** 本机 主机 */
    private String host = "127.0.0.1";

    /** 业务分组标识（scatterid） */
    private String scatterId = "default";

    /** 集群标识（为空回落到 scatterid） */
    private String clusterId;

    /** 种子节点列表（主机:端口） */
    private List<String> seeds = new ArrayList<>();

    /** 本节点服务路径列表 */
    private List<String> servicePaths = new ArrayList<>();

    /** 本节点业务端口（0=自动分配） */
    private int port = 0;

    /** scatter 通信端口（0=端口+2） */
    private int scatterPort = 0;

    /** 是否启用 HTTP 入口（默认 true） */
    private boolean httpEnabled = true;

    /** 是否启用 TCP 入口（默认 false） */
    private boolean tcpEnabled = false;

    /** 负载均衡策略（权重/round/随机） */
    private String balance = "weight";

    /** 请求超时毫秒 */
    private long timeoutMillis = 3000;

    /** 自动发现间隔毫秒 */
    private long autoDiscoveryIntervalMillis = 1000;

    /**
    * 声明的远端服务条目（scatter 会自动将这些服务注册到集群，供对等发现）。
    * 同一 服务路径 下只允许同一种协议。
     */
    private List<ServerEntryProp> serverEntries = new ArrayList<>();

    /**
    * 单个服务条目元数据。
    * @author CH
    * @since 4.0.0
     */
    @Getter
    @Setter
    public static class ServerEntryProp {

        /** 服务路径，如 "/api" */
        private String servicePath;

        /** 目标主机 */
        private String host;

        /** 目标端口 */
        private int port;

        /** 协议：http / tcp / udp */
        private String protocol = "http";

        /** 业务分组（空 时回落 clusterid/scatterid） */
        private String scatterId;
    }
}
