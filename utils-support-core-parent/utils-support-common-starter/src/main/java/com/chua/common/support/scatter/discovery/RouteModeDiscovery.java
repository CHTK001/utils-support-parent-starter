package com.chua.common.support.scatter.discovery;

import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.ScatterSetting;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 路由模式发现：subnet 网段内 gossip 探测扩散。
 *
 * <p>策略：首启全量探测一次（解决已开启节点没数据），后续随机抽样扩散
 * （每次取 gossipTargetCount 台已知节点 + 网段随机抽样），周期全量兜底保证最终一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RouteModeDiscovery extends AbstractScatterDiscovery {

    /** 全量兜底周期（轮） */
    private static final int FULL_PROBE_INTERVAL_ROUNDS = 10;
    /** 并发同步线程池（固定大小，避免节点数过多时线程爆炸） */
    private static final int SYNC_POOL_SIZE = 8;
    private static final ExecutorService syncExecutor = Executors.newFixedThreadPool(
            SYNC_POOL_SIZE, r -> {
                Thread t = new Thread(r, "scatter-sync");
                t.setDaemon(true);
                return t;
            });

    /** probeRound */
    private long probeRound = 0;
    /** 每轮同步请求 ID 计数器 */
    private final AtomicInteger roundRequestIdSeq = new AtomicInteger(0);

    /**
     * 构造方法，创建 Route模式Discovery 实例。
     *
     * @param setting 方法入参 setting
     */
    public RouteModeDiscovery(ScatterSetting setting) {
        super(setting);
    }

    @Override
    protected void doDiscoveryRound() {
        List<ScatterNode> targets = resolveSubnetNodes();
        if (targets.isEmpty()) {
            return;
        }
        boolean fullProbe = probeRound == 0 || probeRound % FULL_PROBE_INTERVAL_ROUNDS == 0;
        if (!fullProbe && targets.size() > setting.getGossipTargetCount()) {
            Collections.shuffle(targets);
            targets = targets.subList(0, setting.getGossipTargetCount());
        }
        probeRound++;

        // 并发拉取，等待所有目标完成（任一失败不中断其他）
        List<CompletableFuture<Void>> futures = targets.stream()
                .map(node -> CompletableFuture.runAsync(() -> syncWith(node), syncExecutor))
                .collect(Collectors.toList());
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 向目标节点拉取服务表并合并（gossip 扩散）。
     * @param node 节点，不允许为 null
     */
    protected void syncWith(ScatterNode node) {
        ScatterContext ctx = new ScatterContext(String.valueOf(roundRequestIdSeq.incrementAndGet()),
                setting.getServicePath(), setting.getTimeoutMillis());
        var result = ScatterSyncHelper.fetch(ctx, node, setting.getTimeoutMillis());
        if (result != null && result.isSuccess() && result.getData() != null) {
            mergeRemote(result.getData());
            log.debug("gossip 合并: {}:{}", node.getHost(), node.getPort());
        }
    }

    /**
     * 解析网段内所有可达主机（前 254 个地址，跳过网络/广播地址）。
     *
     * @return 网段节点列表
     */
    private List<ScatterNode> resolveSubnetNodes() {
        String subnet = setting.getSubnet();
        if (subnet == null || subnet.isBlank()) {
            return List.of();
        }
        String[] parts = subnet.trim().split("/");
        if (parts.length != 2) {
            return List.of();
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return List.of();
        }
        if (prefix < 0 || prefix > 32) {
            return List.of();
        }
        try {
            byte[] base = InetAddress.getByName(parts[0].trim()).getAddress();
            int hostBits = 32 - prefix;
            long baseIp = toLong(base) & (prefix == 0 ? 0L : (~0L << hostBits));
            List<ScatterNode> nodes = new ArrayList<>();
            int maxHosts = (1 << Math.max(1, 32 - prefix)) - 2;
            int limit = Math.min(maxHosts, 254);
            for (int i = 1; i <= limit; i++) {
                String host = toIp(baseIp + i);
                if (host.equals(setting.effectiveHost())) {
                    continue;
                }
                nodes.add(new ScatterNode(host + ":" + setting.getPort(), host, setting.getPort(),
                        setting.getProtocol(), getGroupId(), setting.getServicePath()));
            }
            return nodes;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 转为Long。
     *
     * @param addr 方法入参 addr
     * @return 结果数值
     */
    private static long toLong(byte[] addr) {
        long v = 0;
        for (byte b : addr) {
            v = (v << 8) | (b & 0xffL);
        }
        return v;
    }

    /**
     * 转为IP。
     *
     * @param value 值，不允许为 null
     * @return 结果字符串
     */
    private static String toIp(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "."
                + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }
}
