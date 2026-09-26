package com.chua.network.support.tshark.stream;

import com.chua.network.support.tshark.PacketMeta;
import com.chua.network.support.tshark.PacketRecord;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * TCP 流重组器：把乱序、重传、分片的 TCP 段拼回连续的应用层字节流。
 *
 * <h3>为什么需要</h3>
 * <p>单包协议还原只能看到一帧的载荷。真实流量里一个 HTTP 请求正文、
 * 一条 MySQL 语句、一个 Redis 事务经常横跨多个 TCP 段，拆开看全是残缺片段。
 * 重组后才能把完整字节交给
 * {@link com.chua.common.support.network.protocol.ProtocolRestorer}，
 * 得到可读的业务内容。</p>
 *
 * <h3>状态划分</h3>
 * <p>一条 TCP 连接有两个方向，因此每条流持有<b>两个</b>方向状态，
 * 各自独立维护序列号期望与乱序缓冲。方向归属以先见到 SYN（无 ACK）的一侧为客户端；
 * 两侧都未见握手时退化为"端口较小的一侧为客户端"，保证同一流方向判定稳定。</p>
 *
 * <h3>算法</h3>
 * <p>收到段时按三种情形处理：</p>
 * <ol>
 *   <li>序列号小于期望值 — 重传或已被覆盖的字节，丢弃；</li>
 *   <li>等于期望值 — 追加并前移期望值，随后把乱序缓冲中恰好接上的段一并排出；</li>
 *   <li>大于期望值 — 存在空洞，暂存等待缺失段到达。</li>
 * </ol>
 * <p>TCP 序列号是 32 位且会回绕，因此比较一律用无符号语义
 * （{@link Integer#compareUnsigned}）；否则跨越 2^31 的流会被误判为严重乱序而永不排出。</p>
 *
 * <h3>内存上界</h3>
 * <p>抓包无界，而重组缓冲区一旦遇到永不填补的空洞就会持续增长。
 * 本类因此设三道上界，全部可配：单方向待拼字节上限（超出丢弃序列号最小的乱序段并计数）、
 * 并发流上限（超出按最近最少使用淘汰）、空洞等待时长上限
 * （超时后强制排出已缓冲字节并标记 {@code truncated}）。
 * 被丢弃与被截断的量都有计数器可观测，不静默丢失。</p>
 *
 * <h3>线程安全</h3>
 * <p>非线程安全，约定由单个抓包读循环串行调用。
 * {@link #evictIdle(long)} 需由调用方周期驱动，
 * 因为长时间无流量时不会再有 {@link #accept} 调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class TcpStreamAssembler {

    /**
     * 单方向待拼字节默认上限（1MB）
     */
    public static final int DEFAULT_MAX_BUFFERED_BYTES = 1024 * 1024;

    /**
     * 并发流默认上限
     */
    public static final int DEFAULT_MAX_STREAMS = 512;

    /**
     * 空洞等待默认时长（毫秒）
     */
    public static final long DEFAULT_GAP_TIMEOUT_MILLIS = 30_000L;

    /**
     * 知名端口上界（不含）
     *
     * <p>小于该值的端口视为服务端知名端口（80、3306、6379 等），
     * 用于在缺少握手信息时判定哪一侧是服务端。</p>
     */
    private static final int WELL_KNOWN_PORT_LIMIT = 1024;

    /**
     * 单方向待拼字节上限
     */
    private final int maxBufferedBytes;

    /**
     * 并发流上限
     */
    private final int maxStreams;

    /**
     * 空洞等待时长上限（毫秒）
     */
    private final long gapTimeoutMillis;

    /**
     * 流状态表，按最近访问排序以便做最近最少使用淘汰
     */
    private final Map<TcpStreamKey, StreamState> streams = new LinkedHashMap<>(64, 0.75f, true);

    /**
     * 因超过上界而丢弃的字节数
     */
    private long droppedBytes;

    /**
     * 因空洞超时被强制排出的消息数
     */
    private long truncatedMessages;

    /**
     * 使用默认上界构造。
     */
    public TcpStreamAssembler() {
        this(DEFAULT_MAX_BUFFERED_BYTES, DEFAULT_MAX_STREAMS, DEFAULT_GAP_TIMEOUT_MILLIS);
    }

    /**
     * 构造重组器。
     *
     * @param maxBufferedBytes 单方向待拼字节上限，非正值取默认值
     * @param maxStreams       并发流上限，非正值取默认值
     * @param gapTimeoutMillis 空洞等待时长上限（毫秒），非正值取默认值
     */
    public TcpStreamAssembler(int maxBufferedBytes, int maxStreams, long gapTimeoutMillis) {
        this.maxBufferedBytes = maxBufferedBytes > 0 ? maxBufferedBytes : DEFAULT_MAX_BUFFERED_BYTES;
        this.maxStreams = maxStreams > 0 ? maxStreams : DEFAULT_MAX_STREAMS;
        this.gapTimeoutMillis = gapTimeoutMillis > 0 ? gapTimeoutMillis : DEFAULT_GAP_TIMEOUT_MILLIS;
    }

    /**
     * 投喂一个数据包，排出就绪的连续字节段。
     *
     * @param record 数据包
     * @return 本次可排出的重组消息，可能为空
     */
    @Nonnull
    public List<ReassembledMessage> accept(@Nullable PacketRecord record) {
        if (record == null || !record.tcp() || !record.hasPayload()) {
            return List.of();
        }
        TcpStreamKey key = keyOf(record);
        if (key == null) {
            return List.of();
        }
        StreamState stream = streams.computeIfAbsent(key, k -> new StreamState());
        stream.lastSeenMillis = System.currentTimeMillis();
        evictOverflow();

        boolean outbound = isOutbound(record, stream);
        DirectionState direction = outbound ? stream.clientToServer : stream.serverToClient;
        String streamId = record.meta() == null ? null : record.meta().streamId();
        long seq = record.meta().tcpSeq();
        byte[] payload = record.payload();

        if (direction.expectedSeq == null) {
            // 该方向首段，以它为基准
            direction.expectedSeq = seq;
        }
        int comparison = compareUnsigned(seq, direction.expectedSeq);
        if (comparison < 0) {
            // 重传或重复字节：这些数据已持有，直接丢弃
            return List.of();
        }
        if (comparison > 0) {
            direction.buffer(seq, payload, key);
            return List.of();
        }
        direction.append(payload, record);
        // 必须立刻前移期望序列号：否则重传段（seq 小于期望值）会被当成"连续段"再次追加，
        // 造成字节重复；而后续正常段会因 seq 大于陈旧的期望值被误判为乱序
        direction.advanceExpected(payload.length);
        return direction.drain(key, streamId, outbound);
    }

    /**
     * 强制排出因空洞而停滞的字节，并回收长期无流量的流。
     *
     * @param nowMillis 当前时间（毫秒）
     * @return 被强制排出的消息
     */
    @Nonnull
    public List<ReassembledMessage> evictIdle(long nowMillis) {
        List<ReassembledMessage> messages = new ArrayList<>();
        Iterator<Map.Entry<TcpStreamKey, StreamState>> iterator = streams.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TcpStreamKey, StreamState> entry = iterator.next();
            StreamState stream = entry.getValue();
            boolean idle = nowMillis - stream.lastSeenMillis > gapTimeoutMillis;
            if (idle) {
                messages.addAll(stream.flushPending(entry.getKey(), gapTimeoutMillis));
            }
            if (idle && stream.isEmpty()) {
                iterator.remove();
            }
        }
        if (!messages.isEmpty()) {
            truncatedMessages += messages.size();
        }
        return messages;
    }

    /**
     * 排出全部流中已缓冲的字节并清空状态。
     *
     * @param nowMillis 当前时间（毫秒）
     * @return 被排出的消息
     */
    @Nonnull
    public List<ReassembledMessage> flushAll(long nowMillis) {
        List<ReassembledMessage> messages = new ArrayList<>();
        for (Map.Entry<TcpStreamKey, StreamState> entry : streams.entrySet()) {
            messages.addAll(entry.getValue().flushPending(entry.getKey(), 0L));
        }
        streams.clear();
        truncatedMessages += messages.size();
        return messages;
    }

    /**
     * 重置全部状态与计数器。
     */
    public void reset() {
        streams.clear();
        droppedBytes = 0L;
        truncatedMessages = 0L;
    }

    /**
     * 当前跟踪的流数量。
     *
     * @return 流数量
     */
    public int streamCount() {
        return streams.size();
    }

    /**
     * 因超过内存上界而丢弃的字节数。
     *
     * @return 累计丢弃字节数
     */
    public long droppedBytes() {
        return droppedBytes;
    }

    /**
     * 因空洞超时被截断的消息数。
     *
     * @return 累计截断消息数
     */
    public long truncatedMessages() {
        return truncatedMessages;
    }

    // ==================== 内部方法 ====================

    /**
     * 取出数据包所属的归一化流键。
     *
     * @param record 数据包
     * @return 流键，缺少地址时返回 {@code null}
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
     * 判断报文方向是否为客户端到服务端。
     *
     * @param record 数据包
     * @param stream 流状态
     * @return 客户端到服务端返回 true
     */
    private boolean isOutbound(@Nonnull PacketRecord record, @Nonnull StreamState stream) {
        TcpStreamKey canonical = keyOf(record);
        if (canonical == null) {
            return true;
        }
        TcpStreamKey raw = rawKeyOf(record);
        PacketMeta meta = record.meta();
        String flags = meta == null ? null : meta.tcpFlags();
        boolean isSyn = flags != null && flags.contains("SYN");
        boolean isAck = flags != null && flags.contains("ACK");
        if (isSyn && !isAck && stream.client == null) {
            stream.client = canonical;
            return true;
        }
        TcpStreamKey client = stream.client != null ? stream.client : lowerPortSide(canonical);
        // 必须与报文的"原始方向"比较：keyOf 返回的是归一化键，
        // 已丢失谁是源端，用它比较会把客户端发出的包判成服务端方向
        return raw != null && raw.equals(client);
    }

    /**
     * 取出报文未归一化的键，保留源与目的的原始方向。
     *
     * @param record 数据包
     * @return 原始方向的键，缺少地址时返回 {@code null}
     */
    @Nullable
    private TcpStreamKey rawKeyOf(@Nonnull PacketRecord record) {
        if (record.sourceIp() == null || record.destinationIp() == null) {
            return null;
        }
        return new TcpStreamKey(record.sourceIp(), record.sourcePort(),
                record.destinationIp(), record.destinationPort());
    }

    /**
     * 在没有握手信息时推断客户端一侧。
     *
     * <p>不能用"端口较小的一侧是客户端"：客户端用的是临时端口（通常 1024 以上，
     * 且往往远大于服务端），而服务端是知名端口（80/3306/6379 等，1024 以下）。
     * 按端口大小判会把 HTTP 这类流量整个判反，表现为"请求响应配不上对"。</p>
     *
     * <p>因此先看是否存在知名端口：存在则知名端口一侧是服务端，另一侧是客户端；
     * 两侧都是临时端口（少见）时退回"端口较小的一侧是客户端"。</p>
     *
     * @param key 归一化流键
     * @return 推断出的客户端端点
     */
    @Nonnull
    private TcpStreamKey lowerPortSide(@Nonnull TcpStreamKey key) {
        Integer source = key.sourcePort();
        Integer destination = key.destinationPort();
        if (source == null || destination == null || source.equals(destination)) {
            return key;
        }
        boolean sourceWellKnown = source < WELL_KNOWN_PORT_LIMIT;
        boolean destinationWellKnown = destination < WELL_KNOWN_PORT_LIMIT;
        if (sourceWellKnown != destinationWellKnown) {
            return sourceWellKnown ? key.reversed() : key;
        }
        return source < destination ? key : key.reversed();
    }

    /**
     * 按无符号语义比较两个 TCP 序列号。
     *
     * @param left  左值
     * @param right 右值
     * @return 左值小于右值返回负数，相等返回 0，大于返回正数
     */
    private static int compareUnsigned(long left, long right) {
        return Integer.compareUnsigned((int) left, (int) right);
    }

    /**
     * 流数量超限时按最近最少使用淘汰。
     */
    private void evictOverflow() {
        while (streams.size() > maxStreams) {
            Map.Entry<TcpStreamKey, StreamState> eldest = streams.entrySet().iterator().next();
            droppedBytes += eldest.getValue().bufferedBytes();
            streams.remove(eldest.getKey());
        }
    }

    /**
     * 单条 TCP 流的状态，含两个方向。
     */
    private final class StreamState {

        /**
         * 客户端端点，首次见到 SYN 时确定
         */
        private TcpStreamKey client;

        /**
         * 客户端到服务端方向的状态
         */
        private final DirectionState clientToServer = new DirectionState();

        /**
         * 服务端到客户端方向的状态
         */
        private final DirectionState serverToClient = new DirectionState();

        /**
         * 最近一次见到报文的时间
         */
        private long lastSeenMillis = System.currentTimeMillis();

        /**
         * 两个方向是否都已排空且无待拼字节。
         *
         * @return 已排空返回 true
         */
        boolean isEmpty() {
            return clientToServer.isEmpty() && serverToClient.isEmpty();
        }

        /**
         * 两个方向待拼字节之和。
         *
         * @return 字节数
         */
        long bufferedBytes() {
            return clientToServer.bufferedBytes() + serverToClient.bufferedBytes();
        }

        /**
         * 强制排出两个方向已缓冲的字节。
         *
         * @param key                流键
         * @param gapTimeoutMillis   空洞等待上限，用于判断是否有停滞
         * @return 排出的消息
         */
        @Nonnull
        List<ReassembledMessage> flushPending(@Nonnull TcpStreamKey key, long gapTimeoutMillis) {
            List<ReassembledMessage> messages = new ArrayList<>(2);
            messages.addAll(clientToServer.forceDrain(key, gapTimeoutMillis));
            messages.addAll(serverToClient.forceDrain(key, gapTimeoutMillis));
            return messages;
        }
    }

    /**
     * 单个方向（客户端到服务端或服务端到客户端）的重组状态。
     */
    private final class DirectionState {

        /**
         * 期望的下一个序列号
         */
        private Long expectedSeq;

        /**
         * 乱序段：序列号 -> 段字节
         */
        private final NavigableMap<Long, byte[]> pending = new TreeMap<>();

        /**
         * 已连续就绪的字节
         */
        private ByteArrayOutputStream ready = new ByteArrayOutputStream();

        /**
         * 已就绪的段数
         */
        private int readySegments;

        /**
         * 首帧号
         */
        private Long firstFrame;

        /**
         * 末帧号
         */
        private Long lastFrame;

        /**
         * 首帧时间戳（毫秒）
         */
        private long epochMillis;

        /**
         * 待拼字节数
         */
        private long bufferedBytes;

        /**
         * 空洞开始时间
         */
        private long gapSinceMillis;

        /**
         * 把期望序列号按已消费字节前移。
         *
         * @param length 字节数
         */
        void advanceExpected(int length) {
            if (expectedSeq != null) {
                expectedSeq = advance(expectedSeq, length);
            }
        }

        /**
         * 暂存一个乱序段。
         *
         * @param seq     序列号
         * @param payload 段字节
         * @param key     流键，仅用于日志
         */
        void buffer(long seq, byte[] payload, @Nonnull TcpStreamKey key) {
            if (pending.isEmpty()) {
                gapSinceMillis = System.currentTimeMillis();
            }
            pending.put(seq, payload);
            bufferedBytes += payload.length;
            if (bufferedBytes > maxBufferedBytes) {
                // 丢弃序列号最小的乱序段：越靠前的段越可能永久缺失，
                // 留着它只会占用上界，并把后续段一起堵住
                Map.Entry<Long, byte[]> eldest = pending.pollFirstEntry();
                if (eldest != null) {
                    bufferedBytes -= eldest.getValue().length;
                    droppedBytes += eldest.getValue().length;
                    log.debug("流 {} 待拼字节超过 {} 上限，丢弃序列号 {} 的 {} 字节",
                            key, maxBufferedBytes, eldest.getKey(), eldest.getValue().length);
                }
            }
        }

        /**
         * 追加一个连续段。
         *
         * @param payload 段字节
         * @param record  来源数据包
         */
        void append(byte[] payload, @Nonnull PacketRecord record) {
            ready.write(payload, 0, payload.length);
            readySegments++;
            PacketMeta meta = record.meta();
            Long frameNumber = meta == null ? null : meta.frameNumber();
            if (firstFrame == null) {
                firstFrame = frameNumber;
                epochMillis = record.epochMillis();
            }
            lastFrame = frameNumber;
        }

        /**
         * 排出就绪字节，并把接得上的乱序段一并排出。
         *
         * @param key      流键
         * @param streamId tshark 流号
         * @param outbound 是否为客户端到服务端方向
         * @return 可排出的消息
         */
        @Nonnull
        List<ReassembledMessage> drain(@Nonnull TcpStreamKey key, @Nullable String streamId,
                                       boolean outbound) {
            while (!pending.isEmpty()) {
                Map.Entry<Long, byte[]> first = pending.firstEntry();
                if (compareUnsigned(first.getKey(), expectedSeq) != 0) {
                    break;
                }
                Map.Entry<Long, byte[]> taken = pending.pollFirstEntry();
                bufferedBytes -= taken.getValue().length;
                byte[] chunk = taken.getValue();
                expectedSeq = advance(expectedSeq, chunk.length);
                ready.write(chunk, 0, chunk.length);
                readySegments++;
            }
            ReassembledMessage message = build(key, streamId, outbound, false);
            return message == null ? List.of() : List.of(message);
        }

        /**
         * 强制排出已缓冲的字节。
         *
         * @param key              流键
         * @param gapTimeoutMillis 空洞等待上限，非正值表示无条件排出
         * @return 排出的消息
         */
        @Nonnull
        List<ReassembledMessage> forceDrain(@Nonnull TcpStreamKey key, long gapTimeoutMillis) {
            if (pending.isEmpty()) {
                return List.of();
            }
            if (gapTimeoutMillis > 0 && System.currentTimeMillis() - gapSinceMillis < gapTimeoutMillis) {
                return List.of();
            }
            Map.Entry<Long, byte[]> taken = pending.pollFirstEntry();
            bufferedBytes -= taken.getValue().length;
            byte[] chunk = taken.getValue();
            expectedSeq = advance(expectedSeq, chunk.length);
            ready.write(chunk, 0, chunk.length);
            readySegments++;
            log.debug("流 {} 空洞等待超过 {}ms，强制排出 {} 字节", key, gapTimeoutMillis, chunk.length);
            ReassembledMessage message = build(key, null, true, true);
            return message == null ? List.of() : List.of(message);
        }

        /**
         * 组装就绪字节为消息，无内容时返回 {@code null}。
         *
         * @param key       流键
         * @param streamId  tshark 流号
         * @param outbound  是否为客户端到服务端方向
         * @param truncated 是否截断
         * @return 消息
         */
        @Nullable
        private ReassembledMessage build(@Nonnull TcpStreamKey key, @Nullable String streamId,
                                         boolean outbound, boolean truncated) {
            if (readySegments == 0 || ready.size() == 0) {
                return null;
            }
            ReassembledMessage message = new ReassembledMessage(key, streamId, outbound,
                    firstFrame, lastFrame, epochMillis, readySegments, ready.toByteArray(), truncated);
            ready = new ByteArrayOutputStream();
            readySegments = 0;
            firstFrame = null;
            lastFrame = null;
            epochMillis = 0L;
            return message;
        }

        /**
         * 该方向是否已排空且无待拼字节。
         *
         * @return 已排空返回 true
         */
        boolean isEmpty() {
            return pending.isEmpty() && readySegments == 0;
        }

        /**
         * 该方向待拼字节数。
         *
         * @return 字节数
         */
        long bufferedBytes() {
            return bufferedBytes;
        }

        /**
         * 序列号按长度前移，保留 32 位无符号语义。
         *
         * @param seq    当前序列号
         * @param length 字节数
         * @return 前移后的序列号
         */
        @Nonnull
        private Long advance(@Nonnull Long seq, int length) {
            return (int) (seq + length) & 0xFFFFFFFFL;
        }
    }
}
