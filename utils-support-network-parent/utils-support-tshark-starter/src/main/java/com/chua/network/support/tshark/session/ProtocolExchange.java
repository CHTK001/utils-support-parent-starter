package com.chua.network.support.tshark.session;

import com.chua.network.support.tshark.PacketRecord;
import com.chua.network.support.tshark.stream.TcpStreamKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 一次请求与响应的配对。
 *
 * <p>单包还原给出的是"这一包说了什么"，业务真正关心的是"这次调用是什么、
 * 对方回了什么、耗时多久"。本对象把客户端的一次请求与服务端的一次响应配成一对，
 * 并在有条件时给出往返时延。</p>
 *
 * <p>并非所有协议都有清晰的请求/响应边界：单向推送（MQTT PUBLISH、Syslog）
 * 只有请求侧。因此 {@link #response()} 与 {@link #responseText()} 允许为空，
 * 不以"凑齐一对"为目标而虚构响应。</p>
 *
 * @param key            所属会话
 * @param streamId       tshark 流号
 * @param protocol       会话协议
 * @param request        请求方数据包
 * @param response       响应方数据包，单向协议为 {@code null}
 * @param requestText    请求还原文本
 * @param responseText   响应还原文本，缺失为 {@code null}
 * @param requestMillis  请求时间戳
 * @param responseMillis 响应时间戳，缺失为 {@code 0}
 * @author CH
 * @since 4.0.0.42
 */
public record ProtocolExchange(
        TcpStreamKey key,
        String streamId,
        String protocol,
        PacketRecord request,
        PacketRecord response,
        String requestText,
        String responseText,
        long requestMillis,
        long responseMillis
) {

    /**
     * 构造只有请求的配对。
     *
     * @param key           所属会话
     * @param streamId      tshark 流号
     * @param protocol      会话协议
     * @param request       请求方数据包
     * @param requestText   请求还原文本
     * @param requestMillis 请求时间戳
     * @return 配对
     */
    @Nonnull
    public static ProtocolExchange requestOnly(@Nonnull TcpStreamKey key, @Nullable String streamId,
                                               @Nullable String protocol, @Nonnull PacketRecord request,
                                               @Nullable String requestText, long requestMillis) {
        return new ProtocolExchange(key, streamId, protocol, request, null,
                requestText, null, requestMillis, 0L);
    }

    /**
     * 是否已配到响应。
     *
     * @return 有响应返回 true
     */
    public boolean paired() {
        return response != null;
    }

    /**
     * 往返时延。
     *
     * @return 毫秒数，未配到响应或时间戳缺失返回 {@code 0}
     */
    public long latencyMillis() {
        if (responseMillis <= 0L || requestMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, responseMillis - requestMillis);
    }

    @Override
    public String toString() {
        return "ProtocolExchange{" + protocol + " " + key
                + ", request=" + firstLine(requestText)
                + (paired() ? ", response=" + firstLine(responseText)
                + ", latency=" + latencyMillis() + "ms" : ", response=<none>")
                + '}';
    }

    /**
     * 取文本首行，避免多行还原结果把摘要撑爆。
     *
     * @param text 文本
     * @return 首行
     */
    @Nonnull
    private static String firstLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        int index = text.indexOf('\n');
        String line = index < 0 ? text : text.substring(0, index);
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }
}
