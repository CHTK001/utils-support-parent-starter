package com.chua.network.support.tshark.capture;

import com.chua.network.support.tshark.PacketRecord;
import com.chua.network.support.tshark.session.PacketConversation;
import com.chua.network.support.tshark.session.ProtocolExchange;
import com.chua.network.support.tshark.stream.ReassembledMessage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * 抓包会话回调。
 *
 * <p>全部为 default 空实现，按需覆写关心的回调。
 * 所有回调在抓包读循环线程上被串行调用，因此实现里不要做阻塞操作；
 * 需要异步处理请自行提交到线程池，否则会拖慢抓包、造成丢包。</p>
 *
 * <p>单个回调抛出的异常会被捕获并计入
 * {@link CaptureStatistics#parseFailures()}，不影响后续投递与抓包继续。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface CaptureListener {

    /**
     * 会话启动完成。
     *
     * @param session 抓包会话
     */
    default void onStart(@Nonnull TsharkCapturePolledDirectory session) {
  // 默认空实现
    }

    /**
     * 收到一个数据包（单包协议还原结果）。
     *
     * @param packet 数据包
     */
    default void onPacket(@Nonnull PacketRecord packet) {
  // 默认空实现
    }

    /**
     * 收到一批数据包。
     *
     * <p>批量投递可显著降低高流量下的回调次数。</p>
     *
     * @param packets 数据包批次
     */
    default void onPackets(@Nonnull List<PacketRecord> packets) {
        for (PacketRecord packet : packets) {
            onPacket(packet);
        }
    }

    /**
     * 完成一条 TCP 流重组。
     *
     * <p>这是单包还原拿不到的结果：一个跨多个 TCP 段的完整应用层消息。</p>
     *
     * @param message     重组消息
     * @param restoredText 重组字节的协议还原文本，不可还原时为 {@code null}
     */
    default void onReassembled(@Nonnull ReassembledMessage message, @Nullable String restoredText) {
  // 默认空实现
    }

    /**
     * 配对出一个请求与响应。
     *
     * @param exchange 请求响应对
     */
    default void onExchange(@Nonnull ProtocolExchange exchange) {
  // 默认空实现
    }

    /**
     * 会话聚合的周期性快照。
     *
     * @param conversations 当前全部会话
     */
    default void onSessions(@Nonnull List<PacketConversation> conversations) {
  // 默认空实现
    }

    /**
     * 会话停止。
     *
     * @param state 终止后的状态
     */
    default void onStop(@Nonnull CaptureSessionState state) {
  // 默认空实现
    }

    /**
     * 会话异常终止。
     *
     * @param error 异常
     */
    default void onError(@Nonnull Throwable error) {
  // 默认空实现
    }
}
