package com.chua.common.support.network.discovery.peermesh;

import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PeerMesh 服务发现配置。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class MeshConfig {

    /**
     * 通信模式：udp 或 tcp。默认 tcp。
     */
    @lombok.Builder.Default
    /** 模式 */
    private String mode = "tcp";

    /**
    * 发现模式：c / seed / c-seed。默认 c-seed。
    */
    @lombok.Builder.Default
    /** Discovery */
    private String discovery = "c-seed";

    /**
    * 主端口。默认 9876。
    */
    @lombok.Builder.Default
    /** 端口 */
    private int port = 9876;

    /**
    * 备用端口。默认 9877。
    */
    @lombok.Builder.Default
    /** ALT端口 */
    private int altPort = 9877;

    /**
    * 绑定 IP，null 表示自动选择私网 IP。
    */
    private String bindIp;

    /**
     * 绑定网卡名称，null 表示自动选择。
     */
    private String bindInterface;

    /**
     * C 模式扫描间隔（秒）。默认 30 秒。
     */
    @lombok.Builder.Default
    /** Scan间隔 */
    private int scanInterval = 30;

    /**
    * 心跳间隔（秒）。默认 5 秒。
    */
    @lombok.Builder.Default
    /** Heartbeat间隔 */
    private int heartbeatInterval = 5;

    /**
    * 剔除超时时间（秒）。默认 15 秒。
    */
    @lombok.Builder.Default
    /** Evict超时 */
    private int evictTimeout = 15;

    /**
    * 种子节点列表（仅在 SEED 模式下使用）。
    * 格式: host:port
    */
    private List<String> seeds;

    /**
     * 扫描网段列表（仅在 C 模式下使用）。
     * 格式: CIDR (如 192.168.1.0/24)
     */
    private List<String> scanSubnets;

    /**
     * 持久化文件路径（known_peers.json）。可为 null 表示仅内存。
     */
    private String peersFile;

}
