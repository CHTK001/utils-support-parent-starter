package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.network.discovery.Discovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * C 模式探针：并发扫描网段，发现存活节点。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CModeProbe implements ProbeStrategy {

    private static final Logger log = LoggerFactory.getLogger(CModeProbe.class);
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int MAX_HOSTS_PER_CIDR = 256;

    private final MeshConfig config;
    private final String localServerId;
    private final List<NodeTable.NodeEntry> discovered = new ArrayList<>();
    private volatile boolean stopped;

    /**
     * 构造函数。
     *
     * @param config 配置
     * @param localServerId 本地 serverId（用于排除自身）
     */
    public CModeProbe(MeshConfig config, String localServerId) {
        this.config = config;
        this.localServerId = localServerId;
    }

    @Override
    public void start() throws Exception {
        List<String> subnets = config.getScanSubnets();
        if (subnets == null || subnets.isEmpty()) {
            return;
        }
        List<String> allHosts = new ArrayList<>();
        for (String cidr : subnets) {
            List<String> hosts = parseCidrHosts(cidr);
            if (hosts != null) {
                allHosts.addAll(hosts);
            }
        }
        if (allHosts.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        for (String host : allHosts) {
            futures.add(executor.submit(() -> probeHost(host, config.getPort())));
        }
        for (Future<?> f : futures) {
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
    }

    @Override
    public void stop() throws Exception {
        stopped = true;
    }

    @Override
    public List<NodeTable.NodeEntry> getDiscoveredNodes() {
        return Collections.unmodifiableList(discovered);
    }

    /**
     * 探测指定主机端口。
     *
     * @param host 目标主机
     * @param port 目标端口
     */
    private void probeHost(String host, int port) {
        if (stopped) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            String serverId = host + ":" + port;
            if (serverId.equals(localServerId)) {
                return;
            }
            Discovery d = Discovery.builder()
                    .serverId(serverId)
                    .host(host)
                    .port(port)
                    .protocol(config.getMode())
                    .build();
            discovered.add(new NodeTable.NodeEntry(d, System.currentTimeMillis(), 0));
        } catch (Exception ignored) {
            // 连接失败视为不存在
        }
    }

    /**
     * 解析 CIDR，返回所有可能的主机 IP 列表。
     * 仅支持 IPv4。
     *
     * @param cidr CIDR 字符串，如 192.168.1.0/24
     * @return 主机 IP 列表
     */
    private static List<String> parseCidrHosts(String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return Collections.emptyList();
            }
            String ipStr = parts[0];
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                return Collections.emptyList();
            }
            int ip = inetAtoN(ipStr);
            if (ip == -1) {
                return Collections.emptyList();
            }
            int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
            int network = ip & mask;
            int broadcast = network | ~mask;
            int start = network + 1;
            int end = broadcast - 1;
            if (start > end) {
                return Collections.emptyList();
            }
            if ((end - start + 1) > MAX_HOSTS_PER_CIDR) {
                log.warn("CIDR {} 主机数量过多，已截断为 {} 个", cidr, MAX_HOSTS_PER_CIDR);
                end = start + MAX_HOSTS_PER_CIDR - 1;
            }
            List<String> hosts = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                hosts.add(inetNtoA(i));
            }
            return hosts;
        } catch (Exception e) {
            log.warn("解析 CIDR 失败: {}", cidr, e);
            return Collections.emptyList();
        }
    }

    private static int inetAtoN(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 4) {
                return -1;
            }
            return ((bytes[0] & 0xFF) << 24)
                    | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8)
                    | (bytes[3] & 0xFF);
        } catch (UnknownHostException e) {
            return -1;
        }
    }

    private static String inetNtoA(int ip) {
        return String.format("%d.%d.%d.%d",
                (ip >> 24) & 0xFF,
                (ip >> 16) & 0xFF,
                (ip >> 8) & 0xFF,
                ip & 0xFF);
    }
}