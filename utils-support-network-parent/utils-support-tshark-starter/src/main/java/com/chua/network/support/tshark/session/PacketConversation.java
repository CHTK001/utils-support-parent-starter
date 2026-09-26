package com.chua.network.support.tshark.session;

import com.chua.network.support.tshark.PacketRecord;
import com.chua.network.support.tshark.stream.TcpStreamKey;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个会话（同一对端之间的全部通信）的聚合视图。
 *
 * <p>按归一化四元组聚合，因此客户端的请求与服务端的响应落在同一会话里。
 * 会话同时保留三个视角：</p>
 * <ul>
 *   <li><b>计数</b> — 包数、字节数、起止时间，用于排行与筛选；</li>
 *   <li><b>请求响应对</b> — {@link #exchanges()}，用于还原业务时序；</li>
 *   <li><b>协议分布</b> — {@link #protocolBreakdown()}，一条连接上跑多种协议时可分辨。</li>
 * </ul>
 *
 * <p>包列表有上界：长时间存活的连接（如数据库连接池）会持续产出包，
 * 无界保留会把堆吃光。超出上界后丢弃最旧的包但保留计数，
 * 因此统计数字仍然准确，只是明细被截断——这一点由 {@link #packetsTruncated()} 显式暴露。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class PacketConversation {

    /**
     * 会话内保留的包明细默认上限
     */
    public static final int DEFAULT_MAX_PACKETS = 2048;

    /**
     * 请求响应对默认上限
     */
    public static final int DEFAULT_MAX_EXCHANGES = 512;

    /**
     * 知名端口上界（不含）
     *
     * <p>小于该值的端口视为服务端知名端口，用于在缺少握手信息时判定哪一侧是服务端。</p>
     */
    private static final int WELL_KNOWN_PORT_LIMIT = 1024;

    /**
     * 所属会话键
     */
    private final TcpStreamKey key;

    /**
     * 包明细，按到达顺序
     */
    private final List<PacketRecord> packets = new ArrayList<>();

    /**
     * 请求响应对
     */
    private final List<ProtocolExchange> exchanges = new ArrayList<>();

    /**
     * 协议计数
     */
    private final Map<String, Integer> protocolCounts = new LinkedHashMap<>();

    /**
     * 客户端到服务端的待配对请求
     */
    private PacketRecord pendingRequest;

    /**
     * 待配对请求的还原文本
     */
    private String pendingRequestText;

    /**
     * 待配对请求的时间戳
     */
    private long pendingRequestMillis;

    /**
     * 客户端端点
     */
    private TcpStreamKey client;

    /**
     * 累计包数（含已丢弃的明细）
     */
    private long packetCount;

    /**
     * 累计字节数
     */
    private long byteCount;

    /**
     * 首包时间戳
     */
    private long firstMillis;

    /**
     * 末包时间戳
     */
    private long lastMillis;

    /**
     * 因超过明细上限而丢弃的包数
     */
    private long droppedPackets;

    /**
     * 包明细上限
     */
    private final int maxPackets;

    /**
     * 请求响应对上限
     */
    private final int maxExchanges;

    /**
     * 构造会话。
     *
     * @param key    会话键
     * @param maxPackets    包明细上限，非正值取默认值
     * @param maxExchanges  请求响应对上限，非正值取默认值
     */
    public PacketConversation(@Nonnull TcpStreamKey key, int maxPackets, int maxExchanges) {
        this.key = key;
        this.maxPackets = maxPackets > 0 ? maxPackets : DEFAULT_MAX_PACKETS;
        this.maxExchanges = maxExchanges > 0 ? maxExchanges : DEFAULT_MAX_EXCHANGES;
    }

    /**
     * 投喂一个数据包。
     *
     * @param record 数据包
     * @return 本次新完成的请求响应对，可能为空
     */
    @Nonnull
    public List<ProtocolExchange> accept(@Nonnull PacketRecord record) {
        packetCount++;
        if (record.length() != null) {
            byteCount += record.length();
        }
        long millis = record.epochMillis();
        if (firstMillis == 0L || (millis > 0L && millis < firstMillis)) {
            firstMillis = millis;
        }
        if (millis > lastMillis) {
            lastMillis = millis;
        }
        if (record.protocol() != null) {
            protocolCounts.merge(record.protocol(), 1, Integer::sum);
        }
        appendPacket(record);
        return pair(record, millis);
    }

    /**
     * 追加包明细，超出上限时丢弃最旧的。
     *
     * @param record 数据包
     */
    private void appendPacket(@Nonnull PacketRecord record) {
        if (packets.size() >= maxPackets) {
            packets.remove(0);
            droppedPackets++;
        }
        packets.add(record);
    }

    /**
     * 尝试把当前包与待配对请求配对。
     *
     * <p>规则：客户端到服务端的包成为待配对请求；
     * 收到服务端到客户端的包时与待配对请求组成一对并清空待配对。
     * 这样对"请求→响应"型协议能自然配对，
     * 对"连续推送"型协议则每个请求各自成为一条无响应的记录。</p>
     *
     * @param record 数据包
     * @param millis 时间戳
     * @return 新完成的配对
     */
    @Nonnull
    private List<ProtocolExchange> pair(@Nonnull PacketRecord record, long millis) {
        boolean outbound = isOutbound(record);
        if (outbound) {
            // 同一方向又来了包：上一个请求没有等到响应，按无响应落账
            if (pendingRequest != null) {
                ProtocolExchange orphan = ProtocolExchange.requestOnly(key, streamIdOf(record),
                        record.protocol(), pendingRequest, pendingRequestText, pendingRequestMillis);
                pushExchange(orphan);
                pendingRequest = null;
                pendingRequestText = null;
            }
            pendingRequest = record;
            pendingRequestText = record.restoredText();
            pendingRequestMillis = millis;
            return List.of();
        }
        if (pendingRequest == null) {
            // 服务端先发言（协议握手方向相反），无请求可配
            return List.of();
        }
        ProtocolExchange exchange = new ProtocolExchange(key, streamIdOf(record), record.protocol(),
                pendingRequest, record, pendingRequestText, record.restoredText(),
                pendingRequestMillis, millis);
        pendingRequest = null;
        pendingRequestText = null;
        pendingRequestMillis = 0L;
        pushExchange(exchange);
        return List.of(exchange);
    }

    /**
     * 追加请求响应对，超出上限时丢弃最旧的。
     *
     * @param exchange 配对
     */
    private void pushExchange(@Nonnull ProtocolExchange exchange) {
        if (exchanges.size() >= maxExchanges) {
            exchanges.remove(0);
        }
        exchanges.add(exchange);
    }

    /**
     * 判断报文是否为客户端到服务端方向。
     *
     * @param record 数据包
     * @return 客户端到服务端返回 true
     */
    private boolean isOutbound(@Nonnull PacketRecord record) {
        if (record.sourceIp() == null || record.destinationIp() == null) {
            return true;
        }
        TcpStreamKey canonical = key.canonical();
        TcpStreamKey raw = new TcpStreamKey(record.sourceIp(), record.sourcePort(),
                record.destinationIp(), record.destinationPort());
        String flags = record.meta() == null ? null : record.meta().tcpFlags();
        if (flags != null && flags.contains("SYN") && !flags.contains("ACK") && client == null) {
            client = canonical;
            return true;
        }
        TcpStreamKey resolved = client != null ? client : defaultClient(canonical);
        // 必须与报文的"原始方向"比较：归一化键已丢失谁是源端，
        // 用它比较会把客户端发出的请求判成响应，导致配不上对
        return raw.equals(resolved);
    }

    /**
     * 没有握手信息时推断客户端一侧。
     *
     * <p>不能用"端口较小的一侧是客户端"：客户端用临时端口（通常远大于服务端），
     * 服务端是知名端口（80/3306/6379 等）。按端口大小判会把 HTTP 这类流量判反，
     * 表现为请求与响应配不上对。因此先看是否存在知名端口。</p>
     *
     * @param recordKey 归一化会话键
     * @return 客户端端点
     */
    @Nonnull
    private TcpStreamKey defaultClient(@Nonnull TcpStreamKey recordKey) {
        Integer source = recordKey.sourcePort();
        Integer destination = recordKey.destinationPort();
        if (source == null || destination == null || source.equals(destination)) {
            return recordKey;
        }
        boolean sourceWellKnown = source < WELL_KNOWN_PORT_LIMIT;
        boolean destinationWellKnown = destination < WELL_KNOWN_PORT_LIMIT;
        if (sourceWellKnown != destinationWellKnown) {
            return sourceWellKnown ? recordKey.reversed() : recordKey;
        }
        return source < destination ? recordKey : recordKey.reversed();
    }

    /**
     * 取报文携带的 tshark 流号。
     *
     * @param record 数据包
     * @return 流号，缺失返回 {@code null}
     */
    private String streamIdOf(@Nonnull PacketRecord record) {
        return record.meta() == null ? null : record.meta().streamId();
    }

    /**
     * 会话键。
     *
     * @return 会话键
     */
    @Nonnull
    public TcpStreamKey key() {
        return key;
    }

    /**
     * 会话内出现最多的协议。
     *
     * @return 主协议，无包时返回 {@code null}
     */
    public String dominantProtocol() {
        String dominant = null;
        int best = -1;
        for (Map.Entry<String, Integer> entry : protocolCounts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }

    /**
     * 协议计数。
     *
     * @return 协议到包数的映射
     */
    @Nonnull
    public Map<String, Integer> protocolBreakdown() {
        return Map.copyOf(protocolCounts);
    }

    /**
     * 包明细。
     *
     * @return 包列表副本
     */
    @Nonnull
    public List<PacketRecord> packets() {
        return List.copyOf(packets);
    }

    /**
     * 请求响应对。
     *
     * @return 配对列表副本
     */
    @Nonnull
    public List<ProtocolExchange> exchanges() {
        return List.copyOf(exchanges);
    }

    /**
     * 累计包数。
     *
     * @return 包数
     */
    public long packetCount() {
        return packetCount;
    }

    /**
     * 累计字节数。
     *
     * @return 字节数
     */
    public long byteCount() {
        return byteCount;
    }

    /**
     * 首包时间戳。
     *
     * @return 毫秒时间戳
     */
    public long firstMillis() {
        return firstMillis;
    }

    /**
     * 末包时间戳。
     *
     * @return 毫秒时间戳
     */
    public long lastMillis() {
        return lastMillis;
    }

    /**
     * 会话持续时长。
     *
     * @return 毫秒数
     */
    public long durationMillis() {
        return lastMillis > firstMillis ? lastMillis - firstMillis : 0L;
    }

    /**
     * 因超过明细上限而丢弃的包数。
     *
     * @return 丢弃包数
     */
    public long packetsTruncated() {
        return droppedPackets;
    }

    @Override
    public String toString() {
        return "PacketConversation{" + key
                + ", protocol=" + dominantProtocol()
                + ", packets=" + packetCount
                + ", bytes=" + byteCount
                + ", exchanges=" + exchanges.size()
                + (droppedPackets > 0 ? ", dropped=" + droppedPackets : "")
                + '}';
    }
}
