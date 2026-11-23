// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.network.tcp.TcpServer;
import lombok.Data;
import java.util.List;
import com.chua.common.support.utils.StringUtils;

/**
 * scatter 配置。
 *
 * <p><b>发现方式二选一</b>：</p>
 * <ul>
 *   <li>自动发现（默认）：未配置 {@code seeds} 且 {@code autoDiscovery=true}。
 *       tcp（默认协议）按网段 gossip 扫描，{@code subnet} 为空时默认本机同网段；
 *       udp 则为多播 + 广播宣告（{@code multicastGroup}）；</li>
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

    /**
     * 节点唯一标识
     */
    private String nodeId;
    /**
     * 分组标识（仅同分组节点互相发现/合并）
     */
    private String groupId = "default";
    /**
     * 监听地址
     */
    private String host = "0.0.0.0";
    /**
     * 监听端口（0 = 系统分配）。注意：这是业务端口，scatter 通信端口 = 此值 + 2，存储在 scatterPort 字段中
     */
    private int port;
    /**
     * scatter 通信端口（由 NodeServer 启动后自动填充）
     */
    private int scatterPort;
    /**
     * 传输协议：tcp / udp
     */
    private String protocol = "tcp";
    /**
     * 对外宣告地址（announceHost 非空时优先用于注册，便于 NAT 场景）
     */
    private String announceHost;

    /**
     * 服务路径
     */
    private String servicePath = "/scatter";

    /**
     * seed 引导模式：seed 地址列表（如 "192.168.1.10:19000"），非空即 seed 模式
     */
    private List<String> seeds = Wraps.list();

    /**
     * 路由模式：网段（如 "192.168.1.0/24"），非空即按该网段自动发现
     */
    private String subnet;

    /**
     * 自动发现开关（与 seeds 二选一，seeds 非空时优先 seed 模式）。
     * true=自动发现：tcp 默认本机同网段扫描（subnet 可显式指定），udp 为多播+广播宣告
     */
    private boolean autoDiscovery = true;
    /**
     * UDP 自动发现多播组地址（protocol=udp 时生效）
     */
    private String multicastGroup = "239.255.0.1";

    /**
     * SPI 实现名（如 "tcp"/"vertx-tcp"），空则默认 jdk
     */
    private String spiName;
    /**
     * 直接注入服务端实现对象（未启动）
     */
    private TcpServer server;
    /**
     * 直接注入客户端实现对象（未启动）
     */
    private TcpClient client;

    /**
     * 自动发现（gossip/同步）周期毫秒
     */
    private long autoDiscoveryIntervalMillis = 30_000L;
    /**
     * 心跳探活周期毫秒
     */
    private long heartbeatIntervalMillis = 30_000L;
    /**
     * 连续心跳失败剔除阈值
     */
    private int failRemoveCount = 3;
    /**
     * 单次同步超时毫秒
     */
    private long timeoutMillis = 2000L;
    /**
     * 单次心跳超时毫秒（默认与 timeoutMillis 相同，可单独配置以加快剔除速度）
     */
    private long heartbeatTimeoutMillis = 0L;

    /**
     * 持久化开关
     */
    private boolean persistenceEnabled = true;
    /**
     * 持久化文件
     */
    private String persistenceFile = ".scatter-nodes.json";

    /**
     * gossip 扩散目标数（路由模式抽样）
     */
    private int gossipTargetCount = 4;

    /**
     * 是否 seed 引导模式（seeds 非空；与自动发现二选一，seeds 优先）。
     *
     * @return 是否成功（true 表示成功）
     */
    public boolean isSeedMode() {
        return seeds != null && !seeds.isEmpty();
    }

    /**
     * 自动发现目标端口：显式 port &gt; 0 用之，否则回落 scatter 通信端口。
     * 自动发现要求各节点使用固定一致端口；port=0（系统分配）时无法定位对端。
     *
     * @return 发现目标端口（&lt;=0 表示不可用）
     */
    public int discoveryPort() {
        return port > 0 ? port : scatterPort;
    }

    /**
     * 对外宣告地址：announceHost 非空时优先，否则回落 host。
     *
     * @return 宣告地址
     */
    public String effectiveHost() {
        return StringUtils.isBlank(announceHost) ? host : announceHost;
    }
}
