package com.chua.common.support.network.cluster;

import com.chua.common.support.scattergather.ScatterGatherSetting;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群配置:封装 scatter 配置 + HTTP/TCP 双协议开关。
 *
 * <p>无中心化集群:每个节点通过 seeds 引导加入对等网格,
 * 自动发现彼此、按 scatterId 业务分组注册/路由、自动负载均衡与故障退避。</p>
 *
 * @author CH
 * @since 2026/08/16
 */
@Getter
@Setter
public class ClusterSetting {

    /** 节点 ID(缺省自动生成:host:port) */
    private String nodeId;

    /** 本机 host */
    private String host = "127.0.0.1";

    /** 业务分组标识:同一服务路径下仅与同 scatterId 节点互通 */
    private String scatterId = "default";

    /** 集群种子节点(host:port 列表,引导无中心化发现) */
    private List<String> seeds = new ArrayList<>();

    /** 本节点对外提供的服务路径(注册到集群) */
    private List<String> servicePaths = new ArrayList<>();

    /** 业务端口(HTTP 与 TCP 共用;0=自动分配) */
    private int port = 0;

    /** 是否启用 HTTP 代理入口 */
    private boolean httpEnabled = true;

    /** 是否启用 TCP 代理入口 */
    private boolean tcpEnabled = true;

    /** 负载均衡策略(weight/round/random) */
    private String balance = "weight";

    /** 请求超时(毫秒) */
    private long timeoutMillis = 3000;

    /**
     * 转换为 scatter 配置。
     *
     * @return ScatterGatherSetting
     */
    public ScatterGatherSetting toScatterSetting() {
        ScatterGatherSetting setting = new ScatterGatherSetting();
        setting.setNodeId(nodeId != null ? nodeId : (host + ":" + port));
        setting.setHost(host);
        setting.setTcpPort(port);
        setting.setScatterId(scatterId);
        if (!seeds.isEmpty()) {
            setting.setSeedAddresses(seeds);
        }
        setting.setServicePath(servicePaths.isEmpty() ? "/" : servicePaths.get(0));
        setting.setTimeoutMillis(timeoutMillis);
        setting.setBalance(balance);
        return setting;
    }
}
