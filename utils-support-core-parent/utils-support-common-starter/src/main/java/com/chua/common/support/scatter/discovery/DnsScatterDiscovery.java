// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.scatter.discovery;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.dns.DnsConfig;
import com.chua.common.support.network.dns.DnsProvider;
import com.chua.common.support.network.dns.DnsRecord;
import com.chua.common.support.network.dns.DnsResolverProvider;
import com.chua.common.support.network.dns.DnsServerProvider;
import com.chua.common.support.network.dns.JndiDnsResolver;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS 服务发现：以 DNS SRV/TXT/A 记录承载 scatter 节点表。
 *
 * <p>适用跨网段/跨机房、多播与网段扫描不可达，但存在共享 DNS（内嵌
 * {@link DnsServerProvider} 或云 DNS）的场景。每个节点把自身登记为：</p>
 * <ul>
 *   <li>SRV：{@code _scatter-&lt;groupId&gt;._tcp.<zone>} →
 *       {@code "0 1 <port> <nodeLabel>.<zone>"}；</li>
 *   <li>A：{@code <nodeLabel>.<zone>} → 本机宣告地址；</li>
 *   <li>TXT：{@code <nodeLabel>.<zone>} → {@code serverId=<id>;} + 自定义元数据。</li>
 * </ul>
 *
 * <p>每轮发现（{@link #doDiscoveryRound()}）解析 SRV 得到成员列表，
 * 逐条解析 A/TXT 还原 {@link Discovery} 后走 {@link #mergeRemote(List)} 合并，
 * 心跳剔除/事件通知/持久化均由 {@link AbstractScatterDiscovery} 基类承担。</p>
 *
 * <p>记录发布通道二选一：注入本地内嵌 DNS 服务端（{@link #dnsServer(DnsServerProvider)}，
 * 直写内存表，每轮刷新保活）；或注入云 DNS 记录管理器（{@link #dnsPublisher(DnsProvider)}，
 * 启动时登记一次）。未配置发布通道时仅能读取（只读观察者模式，告警一次）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class DnsScatterDiscovery extends AbstractScatterDiscovery {

    /**
     * SRV 服务名前缀
     */
    private static final String SRV_PREFIX = "_scatter-";

    /**
     * SRV 服务名协议段
     */
    private static final String SRV_PROTO = "._tcp.";

    /**
     * TXT 元数据键：节点唯一标识
     */
    private static final String META_SERVER_ID = "serverId";

    private final String zone;
    private final String srvName;
    private final int dnsTtl;

    private DnsServerProvider dnsServer;
    private DnsProvider dnsProvider;
    private volatile DnsResolverProvider resolver;
    private volatile List<DnsRecord> selfRecords;
    private final AtomicBoolean publishWarned = new AtomicBoolean(false);

    /**
     * 构造方法，创建 DNS 服务发现实例。
     *
     * @param setting scatter 配置（dnsZone 非空启用本模式）
     */
    public DnsScatterDiscovery(ScatterSetting setting) {
        super(setting);
        this.zone = normalizeZone(setting.getDnsZone());
        this.srvName = SRV_PREFIX + setting.getGroupId() + SRV_PROTO + zone;
        this.dnsTtl = setting.getDnsTtl() > 0 ? setting.getDnsTtl() : 30;
    }

    /**
     * 注入本地内嵌 DNS 服务端（记录直写其内存表，每轮发现时刷新保活）。
     *
     * @param dnsServer 内嵌 DNS 服务端
     * @return 当前实例（支持链式调用）
     */
    public DnsScatterDiscovery dnsServer(DnsServerProvider dnsServer) {
        this.dnsServer = dnsServer;
        return this;
    }

    /**
     * 注入云 DNS 记录管理器（启动时登记一次自身记录）。
     *
     * @param dnsProvider 云 DNS 提供者
     * @return 当前实例（支持链式调用）
     */
    public DnsScatterDiscovery dnsPublisher(DnsProvider dnsProvider) {
        this.dnsProvider = dnsProvider;
        return this;
    }

    /**
     * 注入解析器（读路径）。缺省按 {@code setting.dnsResolverName} 经 SPI 创建，
     * 兜底 {@link JndiDnsResolver}。
     *
     * @param resolver DNS 解析器
     * @return 当前实例（支持链式调用）
     */
    public DnsScatterDiscovery resolver(DnsResolverProvider resolver) {
        this.resolver = resolver;
        return this;
    }

    @Override
    public synchronized void start() throws Exception {
        ensureResolver();
        super.start();
        publishSelf();
    }

    /**
     * 每轮发现：解析 SRV → 逐条还原 A/TXT → 合并成员表。
     * 本地内嵌 DNS 服务端场景下同时刷新自身记录（等价刷新 TTL）。
     */
    @Override
    protected void doDiscoveryRound() {
        if (dnsServer != null) {
            publishSelf();
        }
        List<DnsRecord> srvRecords;
        try {
            srvRecords = resolver.resolveSrv(srvName);
        } catch (Exception e) {
            log.debug("DNS 发现 SRV 解析失败: {} - {}", srvName, e.getMessage());
            return;
        }
        if (srvRecords == null || srvRecords.isEmpty()) {
            return;
        }
        List<Discovery> remote = new ArrayList<>(srvRecords.size());
        for (DnsRecord srv : srvRecords) {
            Discovery discovery = toDiscovery(srv);
            if (discovery != null) {
                remote.add(discovery);
            }
        }
        if (!remote.isEmpty()) {
            mergeRemote(remote);
        }
    }

    /**
     * SRV 记录还原为节点条目。
     *
     * @param srv SRV 记录（content 形如 "priority weight port target"）
     * @return 节点条目；解析失败返回 null
     */
    private Discovery toDiscovery(DnsRecord srv) {
        if (srv == null || StringUtils.isBlank(srv.getContent())) {
            return null;
        }
        String[] fields = srv.getContent().trim().split("\\s+");
        if (fields.length < 4) {
            log.debug("SRV 记录格式非法: {}", srv.getContent());
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            log.debug("SRV 端口非法: {}", srv.getContent());
            return null;
        }
        String target = stripTrailingDot(fields[3]);
        if (StringUtils.isBlank(target)) {
            return null;
        }
        String host = resolveHost(target);
        Map<String, String> metadata = resolveMetadata(target);
        String serverId = metadata != null && metadata.get(META_SERVER_ID) != null
                ? metadata.get(META_SERVER_ID) : labelOf(target);
        if (StringUtils.isBlank(serverId)) {
            return null;
        }
        return Discovery.builder()
                .id(serverId)
                .serverId(serverId)
                .scatterId(getGroupId())
                .protocol(setting.getProtocol())
                .host(host)
                .port(port)
                .timeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, setting.getTimeoutMillis())))
                .metadata(metadata)
                .build();
    }

    /**
     * 解析 target 的主机地址：IP 字面量直接返回；否则查 A/AAAA；查不到回退域名。
     *
     * @param target SRV target 域名
     * @return 主机地址
     */
    private String resolveHost(String target) {
        if (target.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return target;
        }
        try {
            List<DnsRecord> aRecords = resolver.resolve(target, "A");
            if (aRecords != null && !aRecords.isEmpty()) {
                return aRecords.get(0).getContent();
            }
            List<DnsRecord> aaaaRecords = resolver.resolve(target, "AAAA");
            if (aaaaRecords != null && !aaaaRecords.isEmpty()) {
                return aaaaRecords.get(0).getContent();
            }
        } catch (Exception e) {
            log.debug("DNS 发现 target 地址解析失败: {} - {}", target, e.getMessage());
        }
        return target;
    }

    /**
     * 解析 target 的 TXT 元数据（k=v 对，分号或空白分隔）。
     *
     * @param target SRV target 域名
     * @return 元数据表；无记录返回 null
     */
    private Map<String, String> resolveMetadata(String target) {
        List<DnsRecord> txtRecords;
        try {
            txtRecords = resolver.resolve(target, "TXT");
        } catch (Exception e) {
            return null;
        }
        if (txtRecords == null || txtRecords.isEmpty()) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (DnsRecord txt : txtRecords) {
            if (txt == null || StringUtils.isBlank(txt.getContent())) {
                continue;
            }
            for (String pair : txt.getContent().split("[;\\s]+")) {
                int i = pair.indexOf('=');
                if (i > 0) {
                    metadata.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
                }
            }
        }
        return metadata.isEmpty() ? null : metadata;
    }

    /**
     * 把本节点登记到 DNS（SRV + A + TXT）。
     */
    private void publishSelf() {
        if (dnsServer == null && dnsProvider == null) {
            if (publishWarned.compareAndSet(false, true)) {
                log.warn("DNS 发现未配置发布通道（dnsServer/dnsPublisher），本节点不登记 DNS: {}", srvName);
            }
            return;
        }
        String host = advertiseHost();
        int port = setting.getScatterPort() > 0 ? setting.getScatterPort() : setting.discoveryPort();
        if (port <= 0) {
            log.debug("DNS 发现端口未就绪（scatterPort/port 均为 0），跳过登记");
            return;
        }
        String target = nodeLabel() + "-" + stableSuffix(setting.getNodeId()) + "." + zone;
        StringBuilder txt = new StringBuilder(META_SERVER_ID).append('=').append(setting.getNodeId());
        if (setting.getMetadata() != null) {
            for (Map.Entry<String, String> entry : setting.getMetadata().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    txt.append(';').append(entry.getKey()).append('=').append(entry.getValue());
                }
            }
        }
        DnsRecord srv = DnsRecord.builder().name(srvName).type("SRV")
                .content("0 1 " + port + " " + target).ttl(dnsTtl).build();
        DnsRecord a = DnsRecord.builder().name(target).type("A")
                .content(host).ttl(dnsTtl).build();
        DnsRecord txtRecord = DnsRecord.builder().name(target).type("TXT")
                .content(txt.toString()).ttl(dnsTtl).build();
        List<DnsRecord> records = Arrays.asList(srv, a, txtRecord);
        selfRecords = records;
        try {
            if (dnsServer != null) {
                for (DnsRecord record : records) {
                    dnsServer.addRecord(record);
                }
            } else {
                for (DnsRecord record : records) {
                    dnsProvider.addRecord(record);
                }
            }
            log.debug("DNS 发现自身已登记: {} -> {}:{}", srvName, host, port);
        } catch (Exception e) {
            log.warn("DNS 发现自身登记失败: {} - {}", srvName, e.getMessage());
        }
    }

    /**
     * 从 DNS 注销本节点记录（close/leave 时调用）。
     * 按 name+type+content 精确删除，避免误删同组其它节点共名 SRV 下的记录。
     */
    private void unpublishSelf() {
        List<DnsRecord> records = selfRecords;
        selfRecords = null;
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            if (dnsServer != null) {
                for (DnsRecord record : records) {
                    dnsServer.removeRecord(record);
                }
            } else if (dnsProvider != null) {
                for (DnsRecord record : records) {
                    dnsProvider.removeRecord(record);
                }
            }
        } catch (Exception e) {
            log.debug("DNS 发现注销失败: {} - {}", srvName, e.getMessage());
        }
    }

    /**
     * 确保解析器就绪：未注入时按 setting 经 SPI 创建，兜底 JNDI。
     */
    private void ensureResolver() {
        if (resolver != null) {
            return;
        }
        String resolverName = StringUtils.isBlank(setting.getDnsResolverName())
                ? "jndi" : setting.getDnsResolverName().trim();
        DnsResolverProvider created = null;
        try {
            created = DnsResolverProvider.create(resolverName);
        } catch (Exception e) {
            log.debug("DNS 解析器 SPI 创建失败: {} - {}", resolverName, e.getMessage());
        }
        if (created == null) {
            created = new JndiDnsResolver();
        }
        DnsConfig.DnsConfigBuilder builder = DnsConfig.builder()
                .zoneName(zone)
                .timeoutMs(Math.max(1_000L, setting.getTimeoutMillis()));
        if (StringUtils.isNotBlank(setting.getDnsServerAddress())) {
            builder.baseUrl(setting.getDnsServerAddress().trim());
        }
        resolver = created.config(builder.build());
    }

    /**
     * 节点 DNS label：serverId 归一化为 {@code [a-z0-9-]}。
     *
     * @return 合法 label
     */
    private String nodeLabel() {
        String id = StringUtils.isBlank(setting.getNodeId()) ? "node" : setting.getNodeId();
        String label = id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return label.startsWith("-") ? label.substring(1) : label;
    }

    /**
     * serverId 稳定短后缀（降低不同 serverId 归一化后同 label 的碰撞概率；
     * String.hashCode 算法跨 JVM 稳定）。
     *
     * @param serverId 节点标识
     * @return 4 位十六进制后缀
     */
    private static String stableSuffix(String serverId) {
        return String.format("%04x", Math.abs((serverId == null ? "node" : serverId).hashCode()) % 0xFFFF);
    }

    /**
     * 从 target 域名取节点 label（首段）。
     *
     * @param target 域名
     * @return 首段 label
     */
    private static String labelOf(String target) {
        if (target == null) {
            return null;
        }
        int i = target.indexOf('.');
        return i > 0 ? target.substring(0, i) : target;
    }

    /**
     * 归一化 zone：小写、去空白、剥末尾点。
     *
     * @param zone 原始 zone
     * @return 归一化 zone
     */
    private static String normalizeZone(String zone) {
        String text = StringUtils.isBlank(zone) ? "scatter.local" : zone.trim().toLowerCase(Locale.ROOT);
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 剥末尾点。
     *
     * @param name 域名
     * @return 无末尾点域名
     */
    private static String stripTrailingDot(String name) {
        String text = name == null ? "" : name.trim();
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    @Override
    public void leave() {
        unpublishSelf();
        super.leave();
    }

    @Override
    public void gracefulClose() {
        unpublishSelf();
        super.gracefulClose();
    }

    @Override
    public void close() {
        unpublishSelf();
        super.close();
    }
}
