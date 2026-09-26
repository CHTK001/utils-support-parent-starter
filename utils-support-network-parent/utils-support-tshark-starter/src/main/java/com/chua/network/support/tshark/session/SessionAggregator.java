package com.chua.network.support.tshark.session;

import com.chua.network.support.tshark.PacketRecord;
import com.chua.network.support.tshark.stream.ReassembledMessage;
import com.chua.network.support.tshark.stream.TcpStreamKey;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话聚合器：把数据包按对端归并为会话，并把请求与响应配对。
 *
 * <p>与 {@link com.chua.network.support.tshark.stream.TcpStreamAssembler} 的分工：
 * 重组器解决"一个包被拆散了"，本类解决"多个包属于同一次业务调用"。</p>
 *
 * <p>重组后的消息可以直接并入会话：它已经是一段完整应用层字节，
 * 用它替代首段参与配对，能避免把一个跨段请求拆成多条"无响应请求"。</p>
 *
 * <p><b>内存上界</b>：会话数量与每会话明细均有上界，
 * 超限时按最近最少使用淘汰整个会话；被淘汰的会话可通过
 * {@link #evictedConversations()} 观测到。</p>
 *
 * <p><b>线程安全</b>：非线程安全，需由单个抓包读循环串行调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SessionAggregator {

    /**
     * 并发会话默认上限
     */
    public static final int DEFAULT_MAX_CONVERSATIONS = 256;

    /**
     * 会话表，按最近访问排序以便做最近最少使用淘汰
     */
    private final Map<TcpStreamKey, PacketConversation> conversations = new LinkedHashMap<>();

    /**
     * 会话数量上限
     */
    private final int maxConversations;

    /**
     * 每会话包明细上限
     */
    private final int maxPacketsPerConversation;

    /**
     * 每会话请求响应对上限
     */
    private final int maxExchangesPerConversation;

    /**
     * 累计包数
     */
    private long totalPackets;

    /**
     * 累计字节数
     */
    private long totalBytes;

    /**
     * 因超过上限而淘汰的会话数
     */
    private long evictedConversations;

    /**
     * 累计还原出的协议分布
     */
    private final Map<String, Long> protocolPackets = new LinkedHashMap<>();

    /**
     * 使用默认上界构造。
     */
    public SessionAggregator() {
        this(DEFAULT_MAX_CONVERSATIONS, PacketConversation.DEFAULT_MAX_PACKETS,
                PacketConversation.DEFAULT_MAX_EXCHANGES);
    }

    /**
     * 构造聚合器。
     *
     * @param maxConversations          会话数量上限，非正值取默认值
     * @param maxPacketsPerConversation 每会话包明细上限，非正值取默认值
     * @param maxExchangesPerConversation 每会话请求响应对上限，非正值取默认值
     */
    public SessionAggregator(int maxConversations, int maxPacketsPerConversation,
                             int maxExchangesPerConversation) {
        this.maxConversations = maxConversations > 0 ? maxConversations : DEFAULT_MAX_CONVERSATIONS;
        this.maxPacketsPerConversation = maxPacketsPerConversation;
        this.maxExchangesPerConversation = maxExchangesPerConversation;
    }

    /**
     * 投喂一个数据包。
     *
     * @param record 数据包
     * @return 本次新完成的请求响应对
     */
    @Nonnull
    public List<ProtocolExchange> accept(@Nullable PacketRecord record) {
        if (record == null) {
            return List.of();
        }
        TcpStreamKey key = keyOf(record);
        if (key == null) {
            return List.of();
        }
        PacketConversation conversation = conversations.computeIfAbsent(key,
                k -> new PacketConversation(k, maxPacketsPerConversation, maxExchangesPerConversation));
        evictOverflow();
        totalPackets++;
        if (record.length() != null) {
            totalBytes += record.length();
        }
        if (record.protocol() != null) {
            protocolPackets.merge(record.protocol(), 1L, Long::sum);
        }
        return conversation.accept(record);
    }

    /**
     * 投喂一个重组后的消息。
     *
     * <p>重组消息的 {@link ReassembledMessage#key()} 直接给出归一化会话键，
     * 无需再从地址推导。段级明细仍以首段数据包计入，
     * 避免同一段字节在会话里被重复计数。</p>
     *
     * @param message     重组消息
     * @param restoredText 重组字节的还原文本
     * @param requestPacket 对应的首个段数据包，可为空
     * @return 本次新完成的请求响应对
     */
    @Nonnull
    public List<ProtocolExchange> acceptReassembled(@Nullable ReassembledMessage message,
                                                     @Nullable String restoredText,
                                                     @Nullable PacketRecord requestPacket) {
        if (message == null || !message.hasContent()) {
            return List.of();
        }
        PacketConversation conversation = conversations.computeIfAbsent(message.key(),
                k -> new PacketConversation(k, maxPacketsPerConversation, maxExchangesPerConversation));
        evictOverflow();
        if (requestPacket != null) {
            if (requestPacket.protocol() != null) {
                protocolPackets.merge(requestPacket.protocol(), 1L, Long::sum);
            }
            totalPackets++;
            if (requestPacket.length() != null) {
                totalBytes += requestPacket.length();
            }
        }
        return conversation.accept(requestPacket == null ? placeholder(message) : requestPacket);
    }

    /**
     * 取出全部会话。
     *
     * @return 会话列表
     */
    @Nonnull
    public List<PacketConversation> conversations() {
        return List.copyOf(new ArrayList<>(conversations.values()));
    }

    /**
     * 按包数降序取前若干个会话。
     *
     * @param limit 取前几个
     * @return 会话列表
     */
    @Nonnull
    public List<PacketConversation> topConversations(int limit) {
        List<PacketConversation> all = new ArrayList<>(conversations.values());
        all.sort(Comparator.comparingLong(PacketConversation::packetCount).reversed());
        return List.copyOf(all.size() > limit ? all.subList(0, limit) : all);
    }

    /**
     * 汇总统计快照。
     *
     * @return 统计
     */
    @Nonnull
    public Statistics statistics() {
        return new Statistics(totalPackets, totalBytes, conversations.size(),
                evictedConversations, Map.copyOf(protocolPackets),
                protocolPackets.keySet().stream()
                        .max(Comparator.comparingLong(protocolPackets::get))
                        .orElse(null));
    }

    /**
     * 累计包数。
     *
     * @return 包数
     */
    public long totalPackets() {
        return totalPackets;
    }

    /**
     * 累计字节数。
     *
     * @return 字节数
     */
    public long totalBytes() {
        return totalBytes;
    }

    /**
     * 当前会话数。
     *
     * @return 会话数
     */
    public int conversationCount() {
        return conversations.size();
    }

    /**
     * 因超过上限而淘汰的会话数。
     *
     * @return 淘汰会话数
     */
    public long evictedConversations() {
        return evictedConversations;
    }

    /**
     * 重置全部状态。
     */
    public void reset() {
        conversations.clear();
        protocolPackets.clear();
        totalPackets = 0L;
        totalBytes = 0L;
        evictedConversations = 0L;
    }

    // ==================== 内部方法 ====================

    /**
     * 取出数据包所属的归一化会话键。
     *
     * @param record 数据包
     * @return 会话键，缺少地址时返回 {@code null}
     */
    @Nullable
    private TcpStreamKey keyOf(@Nonnull PacketRecord record) {
        if (record.sourceIp() == null || record.destinationIp() == null) {
            return null;
        }
        return new TcpStreamKey(record.sourceIp(), record.sourcePort(),
                record.destinationIp(), record.destinationPort()).canonical();
    }

    /**
     * 会话数量超限时按最近最少使用淘汰。
     */
    private void evictOverflow() {
        while (conversations.size() > maxConversations) {
            TcpStreamKey eldest = conversations.keySet().iterator().next();
            conversations.remove(eldest);
            evictedConversations++;
            log.debug("会话数超过 {} 上限，淘汰最久未访问的会话 {}", maxConversations, eldest);
        }
    }

    /**
     * 为没有对应段数据包的重组消息构造占位记录。
     *
     * <p>只在该重组消息确实找不到首段时使用，
     * 保证会话的计数与配对逻辑不因缺失段而漏掉这条消息。</p>
     *
     * @param message 重组消息
     * @return 占位记录
     */
    @Nonnull
    private PacketRecord placeholder(@Nonnull ReassembledMessage message) {
        TcpStreamKey key = message.key();
        return new PacketRecord(
                message.clientToServer() ? key.sourceIp() : key.destinationIp(),
                message.clientToServer() ? key.destinationIp() : key.sourceIp(),
                message.clientToServer() ? key.sourcePort() : key.destinationPort(),
                message.clientToServer() ? key.destinationPort() : key.sourcePort(),
                "REASSEMBLED", message.length(),
                (message.clientToServer() ? "客户端->服务端 " : "服务端->客户端 ")
                        + message.length() + " 字节 / " + message.segmentCount() + " 段",
                null, "{}", null,
                new com.chua.network.support.tshark.PacketMeta(
                        message.firstFrameNumber(), null, message.epochMillis(), null,
                        message.streamId(), null, null, null, "REASSEMBLED", null, null),
                message.payload(), List.of());
    }

    /**
     * 会话聚合统计快照。
     *
     * @param totalPackets        累计包数
     * @param totalBytes          累计字节数
     * @param conversationCount   当前会话数
     * @param evictedConversations 被淘汰的会话数
     * @param protocolPackets     协议分布
     * @param dominantProtocol    包数最多的协议
     */
    public record Statistics(long totalPackets, long totalBytes, int conversationCount,
                             long evictedConversations, Map<String, Long> protocolPackets,
                             String dominantProtocol) {

        /**
         * 规范构造器：对协议分布做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。{@link #statistics()} 产出的
         * 是计数器的只读快照，而聚合器后续仍会 {@code merge} 累加同一张分布表；
         * 快照若直接持有该表，读到的将不是调用时刻的分布。</p>
         *
         * <p>{@code dominantProtocol} 由 {@code max(...).orElse(null)} 产出，允许为
         * {@code null}，故不施加非空约束。</p>
         *
         * @param totalPackets         累计包数
         * @param totalBytes           累计字节数
         * @param conversationCount    当前会话数
         * @param evictedConversations 被淘汰的会话数
         * @param protocolPackets      协议分布
         * @param dominantProtocol     包数最多的协议
         */
        public Statistics {
            protocolPackets = Map.copyOf(Objects.requireNonNull(protocolPackets, "protocolPackets 不能为 null"));
        }

        @Override
        public String toString() {
            return "Statistics{packets=" + totalPackets
                    + ", bytes=" + totalBytes
                    + ", conversations=" + conversationCount
                    + ", dominant=" + dominantProtocol
                    + (evictedConversations > 0 ? ", evicted=" + evictedConversations : "")
                    + '}';
        }
    }
}
