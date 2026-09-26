package com.chua.network.support.tshark.capture;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 抓包统计计数。
 *
 * <p>全部为 {@link AtomicLong}：抓包读循环在独立线程里累加，
 * 而查询方（监控面板、健康检查）在另一个线程读取，必须保证可见性。</p>
 *
 * <p>{@link #droppedByTshark()} 统计的是 tshark 自己的抓包层丢包数，
 * 与本模块的重组/会话丢弃（{@link #droppedByAssembler()}、
 * {@link #droppedByAggregator()}）分开计，因为二者的处置方式不同：
 * 前者说明链路或抓包缓冲跟不上，需调大 snaplen 之外的抓包缓冲；
 * 后者说明本模块的处理上界被打到，可调大配置或收窄过滤条件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CaptureStatistics {

    /**
     * 已解析的数据包数
     */
    private final AtomicLong packets = new AtomicLong();

    /**
     * 捕获的字节数
     */
    private final AtomicLong bytes = new AtomicLong();

    /**
     * 解析失败的数据包数
     */
    private final AtomicLong parseFailures = new AtomicLong();

    /**
     * 已重组出的应用层消息数
     */
    private final AtomicLong reassembledMessages = new AtomicLong();

    /**
     * 已聚合出的会话数
     */
    private final AtomicLong conversations = new AtomicLong();

    /**
     * tshark 抓包层丢包数
     */
    private final AtomicLong droppedByTshark = new AtomicLong();

    /**
     * 流重组因上界丢弃的字节数
     */
    private final AtomicLong droppedByAssembler = new AtomicLong();

    /**
     * 会话聚合因上界淘汰的会话数
     */
    private final AtomicLong droppedByAggregator = new AtomicLong();

    /**
     * 解析队列因消费不过来而丢弃的报文数
     */
    private final AtomicLong droppedByParser = new AtomicLong();

    /**
     * 会话启动时间（毫秒）
     */
    private final long startMillis;

    /**
     * 构造统计并记录起始时间。
     *
     * @param startMillis 会话启动时间（毫秒）
     */
    public CaptureStatistics(long startMillis) {
        this.startMillis = startMillis;
    }

    /**
     * 累加一个数据包。
     *
     * @param packetBytes 数据包字节数
     */
    public void addPacket(int packetBytes) {
        packets.incrementAndGet();
        bytes.addAndGet(packetBytes);
    }

    /**
     * 累加一次解析失败。
     */
    public void addParseFailure() {
        parseFailures.incrementAndGet();
    }

    /**
     * 累加一条重组消息。
     */
    public void addReassembledMessage() {
        reassembledMessages.incrementAndGet();
    }

    /**
     * 设置当前会话数。
     *
     * @param count 会话数
     */
    public void setConversations(int count) {
        conversations.set(count);
    }

    /**
     * 累加 tshark 抓包层丢包数。
     *
     * @param count 丢包数
     */
    public void addDroppedByTshark(long count) {
        if (count > 0) {
            droppedByTshark.addAndGet(count);
        }
    }

    /**
     * 设置流重组丢弃的字节数。
     *
     * @param count 字节数
     */
    public void setDroppedByAssembler(long count) {
        droppedByAssembler.set(count);
    }

    /**
     * 设置会话聚合淘汰的会话数。
     *
     * @param count 会话数
     */
    public void setDroppedByAggregator(long count) {
        droppedByAggregator.set(count);
    }

    /**
     * 设置解析队列丢弃的报文数。
     *
     * @param count 报文数
     */
    public void setDroppedByParser(long count) {
        droppedByParser.set(count);
    }

    /**
     * 已解析的数据包数。
     *
     * @return 包数
     */
    public long packets() {
        return packets.get();
    }

    /**
     * 捕获的字节数。
     *
     * @return 字节数
     */
    public long bytes() {
        return bytes.get();
    }

    /**
     * 解析失败的数据包数。
     *
     * @return 失败数
     */
    public long parseFailures() {
        return parseFailures.get();
    }

    /**
     * 已重组出的应用层消息数。
     *
     * @return 消息数
     */
    public long reassembledMessages() {
        return reassembledMessages.get();
    }

    /**
     * 当前会话数。
     *
     * @return 会话数
     */
    public long conversations() {
        return conversations.get();
    }

    /**
     * tshark 抓包层丢包数。
     *
     * @return 丢包数
     */
    public long droppedByTshark() {
        return droppedByTshark.get();
    }

    /**
     * 流重组因上界丢弃的字节数。
     *
     * @return 字节数
     */
    public long droppedByAssembler() {
        return droppedByAssembler.get();
    }

    /**
     * 会话聚合因上界淘汰的会话数。
     *
     * @return 会话数
     */
    public long droppedByAggregator() {
        return droppedByAggregator.get();
    }

    /**
     * 解析队列因消费不过来而丢弃的报文数。
     *
     * @return 报文数
     */
    public long droppedByParser() {
        return droppedByParser.get();
    }

    /**
     * 已运行时长。
     *
     * @return 毫秒数
     */
    public long elapsedMillis() {
        return Math.max(0L, System.currentTimeMillis() - startMillis);
    }

    /**
     * 平均包速率。
     *
     * @return 包/秒
     */
    public double packetsPerSecond() {
        long elapsed = elapsedMillis();
        return elapsed <= 0L ? 0.0d : packets.get() * 1000.0d / elapsed;
    }

    /**
     * 平均吞吐速率。
     *
     * @return 字节/秒
     */
    public double bytesPerSecond() {
        long elapsed = elapsedMillis();
        return elapsed <= 0L ? 0.0d : bytes.get() * 1000.0d / elapsed;
    }

    @Override
    public String toString() {
        return "CaptureStatistics{packets=" + packets.get()
                + ", bytes=" + bytes.get()
                + ", conversations=" + conversations.get()
                + ", reassembled=" + reassembledMessages.get()
                + ", parseFailures=" + parseFailures.get()
                + ", droppedByTshark=" + droppedByTshark.get()
                + ", droppedByParser=" + droppedByParser.get()
                + ", droppedByAssembler=" + droppedByAssembler.get()
                + ", droppedByAggregator=" + droppedByAggregator.get()
                + ", elapsed=" + elapsedMillis() + "ms"
                + '}';
    }
}
