package com.chua.common.support.scatter;

import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.network.tcp.TcpServer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
* scatter 配置。
*
* <p><b>模式</b>（tcp 双模式）：</p>
* <ul>
*   <li>路由模式：{@code subnet} 非空，网段内 gossip 探测扩散；</li>
*   <li>seed 引导模式：{@code seeds} 非空，仅与 seed 同步 hash、seed 扩散、最小 nodeId 选举。</li>
* </ul>
*
* <p><b>SPI 注入</b>（未启动前设置）：{@code spiName} 指定实现（如 "tcp"/"vertx-tcp"），
* 或直接注入 {@code server}/{@code client} 实现对象；都为空则默认 jdk 实现。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
public class ScatterSetting {

    /** 节点唯一标识 */
    private String nodeId;
    /** 分组标识（仅同分组节点互相发现/合并） */
    private String groupId = "default";
    /** 监听地址 */
    private String host = "0.0.0.0";
    /** 监听端口（0 = 系统分配）。注意：这是业务端口，scatter 通信端口 = 此值 + 2，存储在 scatter端口 字段中 */
    private int port;
    /** scatter 通信端口（由 节点服务端 启动后自动填充） */
    private int scatterPort;
    /** 传输协议：tcp / udp */
    private String protocol = "tcp";
    /** 对外宣告地址（announce主机 非空时优先用于注册，便于 NAT 场景） */
    private String announceHost;

    /** 服务路径 */
    private String servicePath = "/scatter";

    /** seed 引导模式：seed 地址列表（如 "192.168.1.10:19000"），非空即 seed 模式 */
    private List<String> seeds = new ArrayList<>();

    /** 路由模式：网段（如 "192.168.1.0/24"），非空即路由模式 */
    private String subnet;

    /** SPI 实现名（如 "tcp"/"vertx-tcp"），空则默认 jdk */
    private String spiName;
    /** 直接注入服务端实现对象（未启动） */
    private TcpServer server;
    /** 直接注入客户端实现对象（未启动） */
    private TcpClient client;

    /** 自动发现（gossip/同步）周期毫秒 */
    private long autoDiscoveryIntervalMillis = 30_000L;
    /** 心跳探活周期毫秒 */
    private long heartbeatIntervalMillis = 30_000L;
    /** 连续心跳失败剔除阈值 */
    private int failRemoveCount = 3;
    /** 单次同步超时毫秒 */
    private long timeoutMillis = 2000L;
    /** 单次心跳超时毫秒（默认与 超时millis 相同，可单独配置以加快剔除速度） */
    private long heartbeatTimeoutMillis = 0L;

    /** 持久化开关 */
    private boolean persistenceEnabled = true;
    /** 持久化文件 */
    private String persistenceFile = ".scatter-nodes.json";

    /** gossip 扩散目标数（路由模式抽样） */
    private int gossipTargetCount = 4;

    /**
    * 对外宣告地址：announce主机 非空时优先，否则回落 主机。
    *
    * @return 宣告地址
     */
    public String effectiveHost() {
        return announceHost == null || announceHost.isBlank() ? host : announceHost;
    }
}
