// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter.discovery;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNetworks;
import com.chua.common.support.scatter.ScatterNode;
import com.chua.common.support.scatter.ScatterSyncHelper;
import com.chua.common.support.scatter.ScatterSetting;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.StringUtils;

/**
 * 路由模式发现：subnet 网段内 gossip 探测扩散（tcp 自动发现默认实现）。
 *
 * <p>策略：首启全量探测一次（解决已开启节点没数据），后续随机抽样扩散
 * （每次取 gossipTargetCount 台已知节点 + 网段随机抽样），周期全量兜底保证最终一致。
 * {@code subnet} 为空时默认取本机非回环 IPv4 所在网段（同网段自动发现）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RouteModeDiscovery extends AbstractScatterDiscovery {

    /**
     * 全量兜底周期（轮）
     */
    private static final int FULL_PROBE_INTERVAL_ROUNDS = 10;
    /**
     * 并发同步线程池（固定大小，避免节点数过多时线程爆炸）
     */
    private static final int SYNC_POOL_SIZE = 8;

    /**
     * 并发同步线程池队列容量：与线程数同量级，队列满时由调用线程兜底执行，避免任务无限堆积。
     */
    private static final int SYNC_QUEUE_SIZE = 8;

    /**
     * 并发同步线程池实例。
     */
// [P3C C1 豁免] 场景化创建线程，需文档化
    private static final ThreadPoolExecutor SYNC_EXECUTOR = new ThreadPoolExecutor(
            SYNC_POOL_SIZE,
            SYNC_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SYNC_QUEUE_SIZE),
            r -> {
// [P3C C1 豁免] 场景化创建线程，需文档化
                Thread t = new Thread(r, "scatter-sync");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * probeRound
     */
    private long probeRound = 0;
    /**
     * 每轮同步请求 ID 计数器
     */
    private final AtomicInteger roundRequestIdSeq = new AtomicInteger(0);
    /**
     * 端口未配置告警（仅告警一次）
     */
    private final AtomicBoolean portWarned = new AtomicBoolean(false);

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
                .map(node -> CompletableFuture.runAsync(() -> syncWith(node), SYNC_EXECUTOR))
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
            syncSuccesses.incrementAndGet();
            mergeRemote(result.getData());
            log.debug("gossip 合并: {}:{}", node.getHost(), node.getPort());
        } else {
            syncFailures.incrementAndGet();
        }
    }

    /**
     * 解析网段内所有可达主机（前 254 个地址，跳过网络/广播地址）。
     *
     * @return 网段节点列表
     */
    private List<ScatterNode> resolveSubnetNodes() {
        String subnet = setting.getSubnet();
        if (StringUtils.isBlank(subnet)) {
            // 自动发现默认：本机非回环 IPv4 所在网段（如 192.168.1.0/24）
            subnet = ScatterNetworks.localSubnetCidr();
            if (StringUtils.isBlank(subnet)) {
                if (portWarned.compareAndSet(false, true)) {
                    log.warn("自动发现未配置 subnet 且无法推断本机网段，跳过本轮网段扫描");
                }
                return List.of();
            }
        }
        int port = setting.discoveryPort();
        if (port <= 0) {
            // 自动发现要求各节点使用固定一致端口，port=0（系统分配）时无法定位对端
            if (portWarned.compareAndSet(false, true)) {
                log.warn("自动发现要求固定端口（port>0），当前 port=0，跳过网段扫描");
            }
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
            List<ScatterNode> nodes = Wraps.list();
            int maxHosts = (1 << Math.max(1, 32 - prefix)) - 2;
            int limit = Math.min(maxHosts, 254);
            for (int i = 1; i <= limit; i++) {
                String host = toIp(baseIp + i);
                if (isSelf(host)) {
                    continue;
                }
                nodes.add(new ScatterNode(host + ":" + port, host, port,
                        setting.getProtocol(), getGroupId(), setting.getServicePath()));
            }
            return nodes;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 判断目标地址是否为本机（host 配置值或本机任一非回环 IPv4），避免自我探测。
     *
     * @param host 目标地址，不允许为 null
     * @return 是否本机
     */
    private boolean isSelf(String host) {
        if (host.equals(setting.effectiveHost())) {
            return true;
        }
        return ScatterNetworks.localIPv4s().contains(host);
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
                + ((value >> 8) & 0xff) + "." + (value & 0xff);    }}