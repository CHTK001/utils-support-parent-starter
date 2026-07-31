package com.chua.remote.support.gateway.core.session;

import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.channel.Channel;
import lombok.Builder;
import lombok.Data;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * 网关会话，表示一次远程控制连接。
 * <p>每个 {@code GatewaySession} 实例对应一条从客户端（控制端）经过网关到达 Agent（被控端）
 * 的数据通路，支持 SSH 终端和桌面远程控制两种场景。
 * 使用 {@link AtomicLongFieldUpdater} 实现字节与帧计数器的无锁原子更新，
 * 适合在 Netty Handler 热路径上高频调用。</p>
 *
 * @author CH
 */
@Data
@Builder
public class GatewaySession {

    // ===== 原子计数器（volatile long + FieldUpdater 实现无锁原子 RMW） =====

    /** 发送字节计数器的原子更新器 */
    private static final AtomicLongFieldUpdater<GatewaySession> BYTES_SENT_UPDATER =
            AtomicLongFieldUpdater.newUpdater(GatewaySession.class, "bytesSent");
    /** 接收字节计数器的原子更新器 */
    private static final AtomicLongFieldUpdater<GatewaySession> BYTES_RECEIVED_UPDATER =
            AtomicLongFieldUpdater.newUpdater(GatewaySession.class, "bytesReceived");
    /** 帧传输计数器的原子更新器 */
    private static final AtomicLongFieldUpdater<GatewaySession> FRAMES_UPDATER =
            AtomicLongFieldUpdater.newUpdater(GatewaySession.class, "framesTransferred");

    /** 会话唯一标识 */
    /**
     * 会话 ID
     */
    private String sessionId;
    /** 所属 Agent ID */
    private String agentId;
    /** 目标 ID */
    private String targetId;
    /** 客户端 ID */
    /**
     * 客户端 ID
     */
    private String clientId;
    /** 通信协议（SSH / DESKTOP / HTTP 等） */
    private Protocol protocol;
    /** 连接模式（长连接 / 短连接） */
    private ConnectionMode mode;
    /** 会话状态 */
    /**
     * 状态
     */
    private SessionStatus status;
    /** 客户端地址 */
    private InetSocketAddress clientAddress;
    /** 目标地址 */
    private InetSocketAddress targetAddress;
    /** 客户端的 Netty 通道 */
    private Channel clientChannel;
    /** Agent 端的 Netty 通道 */
    private Channel agentChannel;
    /** 会话创建时间 */
    /**
     * 创建时间
     */
    private Instant createdAt;
    /** 会话最后更新时间 */
    /**
     * 更新时间
     */
    private Instant updatedAt;

    /** 累计发送字节数 */
    @Builder.Default
    private volatile long bytesSent = 0;
    /** 累计接收字节数 */
    @Builder.Default
    private volatile long bytesReceived = 0;
    /** 累计传输帧数（桌面远程场景） */
    @Builder.Default
    private volatile long framesTransferred = 0;
    /** 最后活动时间 */
    private volatile Instant lastActivityAt;

    /** 会话扩展属性，用于携带自定义上下文信息 */
    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    // ===== 高性能原子累加方法（供 Handler 热路径调用） =====

    /**
     * 累计发送字节数并更新最后活动时间。
     *
     * @param delta 本次发送的字节增量
     */
    public void addBytesSent(long delta) {
        BYTES_SENT_UPDATER.addAndGet(this, delta);
        lastActivityAt = Instant.now();
    }

    /**
     * 累计接收字节数并更新最后活动时间。
     *
     * @param delta 本次接收的字节增量
     */
    public void addBytesReceived(long delta) {
        BYTES_RECEIVED_UPDATER.addAndGet(this, delta);
        lastActivityAt = Instant.now();
    }

    /**
     * 累计发送帧数与字节数并更新最后活动时间。
     * <p>桌面远程控制场景专用，一次调用同时完成帧计数和字节计数更新，
     * 减少对 {@link #lastActivityAt} 的重复赋值。</p>
     *
     * @param payloadBytes 本次发送帧的有效载荷字节数
     */
    public void addFrameSent(long payloadBytes) {
        BYTES_SENT_UPDATER.addAndGet(this, payloadBytes);
        FRAMES_UPDATER.incrementAndGet(this);
        lastActivityAt = Instant.now();
    }
}
