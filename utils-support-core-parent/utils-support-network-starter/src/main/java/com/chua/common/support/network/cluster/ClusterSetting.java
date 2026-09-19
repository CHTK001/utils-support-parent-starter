package com.chua.common.support.network.cluster;

import com.chua.common.support.scatter.ScatterSetting;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群配置：封装 scatter 配置 + HTTP/TCP 双协议开关 + 服务元数据声明。
 *
 * <p>每个节点通过 seeds 引导加入对等网格，
 * 按 scatterid 业务分组自动发现、注册/路由、负载均衡与故障退避。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Setter
public class ClusterSetting {

    /**
     * 节点 标识（缺省自动生成：主机:端口）
    */
    private String nodeId;

    /**
     * 本机 主机
    */
    private String host = "127.0.0.1";

    /**
     * 业务分组标识：同一服务路径下仅与同 scatterid 节点互通
    */
    private String scatterId = "default";

    /**
     * 集群标识：为空时回落到 scatterid
    */
    private String clusterId;

    /**
     * 集群种子节点（主机:端口 列表，引导无中心化发现）
    */
    private List<String> seeds = new ArrayList<>();

    /**
     * 本节点对外提供的服务路径（用于 scatter 路由前缀匹配）
    */
    private List<String> servicePaths = new ArrayList<>();

    /**
     * 显式声明的远端服务元数据列表。
     * <p>ClusterServer builder 在启动前填入，ClusterNode.start() 会将它们注册进 scatter 供对等扩散。</p>
     * <p>同一 servicePath 下只允许同一种协议，混用时 {@link ClusterManager#addServer} 会拒绝。</p>
     */
    private List<ServerEntry> serverEntries = new ArrayList<>();

    /**
     * 本节点业务端口（HTTP 与 TCP 共用；0=自动分配）
    */
    private int port = 0;

    /**
     * scatter 通信端口（节点服务端 监听；0=端口+2，与 HTTP/TCP 代理分离）
     */
    private int scatterPort = 0;

    /**
     * 是否启用 HTTP 代理入口
    */
    private boolean httpEnabled = true;

    /**
     * 是否启用 TCP 代理入口
    */
    private boolean tcpEnabled = true;

    /**
     * 负载均衡策略（权重/round/随机）
    */
    private String balance = "weight";

    /**
     * 请求超时（毫秒）
    */
    private long timeoutMillis = 3000;

    /**
     * 自动发现间隔（毫秒），默认 1000
    */
    private long autoDiscoveryIntervalMillis = 1000;

    /**
     * 集群 master（域名或主入口地址）。
     * <p>仅作为元数据记录，供运维/监控识别主入口，不影响 scatter 对等发现逻辑。</p>
     */
    private String master;

    /**
     * 获取有效业务分组：clusterid 为空时回落到 scatterid。
     * @return effective群体id的结果
     */
    public String effectiveGroupId() {
        return clusterId != null && !clusterId.isBlank() ? clusterId : scatterId;
    }

    /**
     * 转换为 scatter 配置。
     * @return 转为scattersetting的结果
     */
    public ScatterSetting toScatterSetting() {
        ScatterSetting setting = new ScatterSetting();
        setting.setNodeId(nodeId != null ? nodeId : (host + ":" + port));
        setting.setHost(host);
        // scatter 通信端口：显式 scatterPort 或默认 port+2，与 HTTP(port)/TCP 代理(port+1)分离
        setting.setPort(scatterPort > 0 ? scatterPort : (port > 0 ? port + 2 : 0));
        setting.setGroupId(effectiveGroupId());
        if (!seeds.isEmpty()) {
            setting.setSeeds(seeds);
        }
        setting.setServicePath(servicePaths.isEmpty() ? "/" : servicePaths.getFirst());
        setting.setTimeoutMillis(timeoutMillis);
        setting.setAutoDiscoveryIntervalMillis(autoDiscoveryIntervalMillis);
        return setting;
    }
}
