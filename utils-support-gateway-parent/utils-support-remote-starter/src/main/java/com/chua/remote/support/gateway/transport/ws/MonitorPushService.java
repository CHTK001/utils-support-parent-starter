package com.chua.remote.support.gateway.transport.ws;

import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.core.session.GatewaySession;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.core.session.SessionStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 监控推送服务
 * <p>
 * 管理监控订阅者的 WebSocket 通道，定时（默认间隔 2 秒）向所有订阅者
 * 推送网关会话快照数据，包括系统级统计（总会话数、活跃数、流量）和
 * 各会话详情（ID、协议、状态、吞吐量等）。
 * <p>
 * 使用独立的单线程调度器执行推送，避免阻塞业务 IO 线程。
 *
 * @author CH
 */
@Slf4j
public class MonitorPushService {

    /** 会话管理器，用于获取会话统计数据 */
    private final SessionManager sessionManager;
    /** 网关配置服务，用于获取网关全局配置 */
    private final GatewayConfigService configService;
    /** JSON 序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 订阅者集合（WebSocket 通道），线程安全 */
    private final Set<Channel> subscribers = ConcurrentHashMap.newKeySet();
    /** 定时推送调度器 */
    private ScheduledExecutorService scheduler;
    /** 默认推送间隔（毫秒） */
    private int defaultIntervalMs = 2000;

    /**
     * 构造监控推送服务
     *
     * @param sessionManager 会话管理器
     * @param configService  网关配置服务
     */
    public MonitorPushService(SessionManager sessionManager, GatewayConfigService configService) {
        this.sessionManager = sessionManager;
        this.configService = configService;
    }

    /**
     * 启动定时推送服务
     *
     * @param intervalMs 推送间隔（毫秒）
     */
    public void start(int intervalMs) {
        this.defaultIntervalMs = intervalMs;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-push");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pushSnapshot, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[MonitorPush] 启动, 间隔={}ms", intervalMs);
    }

    /**
     * 停止推送服务，清理调度器和所有订阅者
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        subscribers.clear();
        log.info("[MonitorPush] 已停止");
    }

    /**
     * 订阅监控推送。订阅后立即执行一次推送，之后按指定间隔定时推送。
     *
     * @param ch         订阅者的 WebSocket 通道
     * @param intervalMs 推送间隔（毫秒）
     */
    public void subscribe(Channel ch, int intervalMs) {
        subscribers.add(ch);
        log.info("[MonitorPush] 订阅: {} (间隔={}ms, 总数={})", ch.remoteAddress(), intervalMs, subscribers.size());
        // 立即推送一次
        try {
            ch.writeAndFlush(new TextWebSocketFrame(buildSnapshotJson()));
        }
 catch (Exception e) {
            log.warn("[MonitorPush] 首次推送失败: {}", e.getMessage());
        }
    }

    /**
     * 取消订阅监控推送
     *
     * @param ch 要取消订阅的 WebSocket 通道
     */
    public void unsubscribe(Channel ch) {
        subscribers.remove(ch);
        log.info("[MonitorPush] 取消订阅: {} (剩余={})", ch.remoteAddress(), subscribers.size());
    }

    /**
     * 向所有活跃订阅者推送快照。遍历订阅者集合，对已断开的通道自动移除。
     */
    private void pushSnapshot() {
        if (subscribers.isEmpty()) { return; }
        String snapshot = buildSnapshotJson();
        for (Channel ch : subscribers) {
            if (ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(snapshot));
            }
 else {
                subscribers.remove(ch);
            }
        }
    }

    /**
     * 构建监控快照 JSON 字符串，包含系统统计和所有会话详情
     *
     * @return 格式化的 JSON 快照字符串
     */
    private String buildSnapshotJson() {
        try {
            SessionStats stats = sessionManager.getStats();
            List<GatewaySession> sessions = sessionManager.allSessions();

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "monitor_update");

            Map<String, Object> statsMap = new LinkedHashMap<>();
            statsMap.put("totalSessions", stats.totalSessions());
            statsMap.put("activeSessions", stats.activeSessions());
            statsMap.put("totalBytesSent", stats.totalBytesSent());
            statsMap.put("totalBytesReceived", stats.totalBytesReceived());
            statsMap.put("totalFramesTransferred", stats.totalFramesTransferred());
            root.put("stats", statsMap);

            root.put("sessions", sessions.stream().map(this::toSessionMap).toList());

            return mapper.writeValueAsString(root);
        }
 catch (Exception e) {
            log.warn("[MonitorPush] 序列化失败: {}", e.getMessage());
            return "{\"type\":\"monitor_update\",\"error\":\"serialize failed\"}";
        }
    }

    /**
     * 将会话对象转为 Map，用于 JSON 序列化
     *
     * @param s 网关会话
     * @return 包含会话关键信息的 Map
     */
    private Map<String, Object> toSessionMap(GatewaySession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("agentId", s.getAgentId());
        m.put("targetId", s.getTargetId());
        m.put("protocol", s.getProtocol() != null ? s.getProtocol().name() : null);
        m.put("status", s.getStatus() != null ? s.getStatus().name() : null);
        m.put("bytesSent", s.getBytesSent());
        m.put("bytesReceived", s.getBytesReceived());
        m.put("framesTransferred", s.getFramesTransferred());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("lastActivityAt", s.getLastActivityAt() != null ? s.getLastActivityAt().toString() : null);
        return m;
    }
}
