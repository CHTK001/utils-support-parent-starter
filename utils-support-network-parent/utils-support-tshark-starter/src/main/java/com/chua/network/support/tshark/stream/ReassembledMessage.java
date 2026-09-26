package com.chua.network.support.tshark.stream;

import java.util.Arrays;

/**
 * 重组后的一段应用层消息。
 *
 * <p>由若干个 TCP 段按序列号拼接而成，是单包还原做不到的那部分结果：
 * 一个 HTTP 请求正文被切成 3 个 TCP 段时，单包视角只能看到 3 个残缺片段，
 * 本对象给出完整字节，可直接交给
 * {@link com.chua.common.support.network.protocol.ProtocolRestorer} 还原。</p>
 *
 * @param key           所属 TCP 流
 * @param streamId      tshark 流号，缺失时为 {@code null}
 * @param clientToServer true 表示方向为客户端到服务端
 * @param firstFrameNumber 组成该消息的第一个帧号
 * @param lastFrameNumber  组成该消息的最后一个帧号
 * @param epochMillis      首个帧的时间戳（毫秒）
 * @param segmentCount     合并的 TCP 段数
 * @param payload          合并后的应用层字节
 * @param truncated        是否因内存上限或空洞超时而被截断
 * @author CH
 * @since 4.0.0.42
 */
public record ReassembledMessage(
        TcpStreamKey key,
        String streamId,
        boolean clientToServer,
        Long firstFrameNumber,
        Long lastFrameNumber,
        long epochMillis,
        int segmentCount,
        byte[] payload,
        boolean truncated
) {

    /**
     * 规范构造器：载荷数组做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。本类的 {@link #length()} 显式容忍
     * {@code null} 载荷，因此这里保留 {@code null} 语义，不抛 {@link NullPointerException}。</p>
     *
     * @param payload 合并后的应用层字节
     */
    public ReassembledMessage {
        payload = payload == null ? null : payload.clone();
    }

    /**
     * 访问器覆写：返回内部载荷数组的副本，避免外部持有内部引用。
     *
     * @return 载荷副本；载荷为 {@code null} 时返回 {@code null}
     */
    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }

    /**
     * 取载荷长度。
     *
     * @return 字节数
     */
    public int length() {
        return payload == null ? 0 : payload.length;
    }

    /**
     * 是否有可用内容。
     *
     * @return 载荷非空返回 true
     */
    public boolean hasContent() {
        return length() > 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ReassembledMessage that
                && clientToServer == that.clientToServer
                && java.util.Objects.equals(key, that.key)
                && java.util.Objects.equals(streamId, that.streamId)
                && segmentCount == that.segmentCount
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(key, streamId, clientToServer, segmentCount)
                + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "ReassembledMessage{" + (clientToServer ? "C->S" : "S->C") + " " + key
                + ", stream=" + streamId
                + ", segments=" + segmentCount
                + ", bytes=" + length()
                + (truncated ? ", TRUNCATED" : "")
                + '}';
    }
}
