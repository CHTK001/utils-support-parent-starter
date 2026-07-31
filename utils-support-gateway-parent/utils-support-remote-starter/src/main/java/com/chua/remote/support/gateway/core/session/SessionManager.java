package com.chua.remote.support.gateway.core.session;

import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器。
 * <p>负责网关级会话的创建、维护与销毁，支持 SSH 终端和桌面远程控制两种场景。
 * 内部使用 {@link ConcurrentHashMap} 保证并发安全，提供基于 Agent、Channel 的会话检索
 * 以及批量关闭、统计等功能。</p>
 *
 * @author CH
 */
@Slf4j
public class SessionManager {

    /** 会话标识 -> 会话对象的映射 */
    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();
    /** 允许的最大并发会话数 */
    /**
     * 最大会话数
     */
    private volatile int maxSessions;

    /**
     * 构造会话管理器。
     *
     * @param maxSessions 最大并发会话数
     */
    public SessionManager(int maxSessions) { this.maxSessions = maxSessions; }

    /**
     * 更新最大会话数。
     *
     * @param maxSessions 新的最大会话数
     */
    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
        log.info("[SessionManager] maxSessions 更新为: {}", maxSessions);
    }

    /**
     * 获取最大会话数。
     *
     * @return 最大会话数
     */
    public int getMaxSessions() { return maxSessions; }

    /**
     * 创建一个新会话。
     * <p>生成全局唯一的会话 ID（UUID 去除连字符），与会话所属的 target、agent、client
     * 关联后存入会话表。</p>
     *
     * @param targetId      目标 ID
     * @param agentId       Agent ID
     * @param clientId      客户端 ID
     * @param protocol      通信协议（SSH / DESKTOP / HTTP 等）
     * @param mode          连接模式
     * @param clientChannel 客户端的 Netty 通道
     * @return 创建成功的会话对象
     * @throws IllegalStateException 如果当前会话数已达上限
     */
    public GatewaySession createSession(String targetId, String agentId, String clientId,
                                         Protocol protocol, ConnectionMode mode, Channel clientChannel) {
        if (sessions.size() >= maxSessions) { throw new IllegalStateException("超出最大会话数: " + maxSessions); }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        GatewaySession session = GatewaySession.builder()
                .sessionId(sessionId).targetId(targetId).agentId(agentId).clientId(clientId)
                .protocol(protocol).mode(mode).status(SessionStatus.ACTIVE)
                .clientChannel(clientChannel).createdAt(Instant.now()).updatedAt(Instant.now()).build();
        sessions.put(sessionId, session);
        log.info("会话创建: {} target={} protocol={}", sessionId, targetId, protocol);
        return session;
    }

    /**
     * 根据会话 ID 获取会话。
     *
     * @param sessionId 会话 ID
     * @return 会话对象，不存在时返回 {@code null}
     */
    public GatewaySession getSession(String sessionId) { return sessions.get(sessionId); }

    /**
     * 将会话与 Agent 端通道关联。
     * <p>在 Agent 通道建立后调用，完成 client 通道与 agent 通道的双向绑定。</p>
     *
     * @param sessionId    会话 ID
     * @param agentChannel Agent 端的 Netty 通道
     */
    public void attachAgentChannel(String sessionId, Channel agentChannel) {
        GatewaySession s = sessions.get(sessionId);
        if (s != null) { s.setAgentChannel(agentChannel); s.setUpdatedAt(Instant.now()); }
    }

    /**
     * 关闭指定会话。
     * <p>将会话从注册表中移除，标记为 CLOSED 状态，并静默关闭 client 和 agent 两端通道。</p>
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        GatewaySession s = sessions.remove(sessionId);
        if (s != null) {
            s.setStatus(SessionStatus.CLOSED);
            closeQuietly(s.getClientChannel());
            closeQuietly(s.getAgentChannel());
            log.info("会话关闭: {} target={}", sessionId, s.getTargetId());
        }
    }

    /**
     * 强制关闭指定会话。
     * <p>与会话不存在时返回 {@code false} 而不抛异常。</p>
     *
     * @param sessionId 会话 ID
     * @return 是否存在并关闭了该会话
     */
    public boolean forceCloseSession(String sessionId) {
        if (!sessions.containsKey(sessionId)) { return false; }
        closeSession(sessionId);
        return true;
    }

    /**
     * 获取所有活跃会话的列表（快照）。
     *
     * @return 当前所有会话的列表
     */
    public List<GatewaySession> allSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 获取会话统计摘要。
     * <p>遍历所有会话，汇总总字节发送/接收量、总帧数及活跃会话数。</p>
     *
     * @return 会话统计对象
     */
    public SessionStats getStats() {
        long totalSent = 0, totalRecv = 0, totalFrames = 0;
        int active = 0;
        for (GatewaySession s : sessions.values()) {
            totalSent += s.getBytesSent();
            totalRecv += s.getBytesReceived();
            totalFrames += s.getFramesTransferred();
            if (s.getStatus() == SessionStatus.ACTIVE) { active++; }
        }
        return new SessionStats(sessions.size(), active, totalSent, totalRecv, totalFrames);
    }

    /**
     * 根据 Agent ID 查询所有关联会话。
     *
     * @param agentId Agent ID
     * @return 关联该 Agent 的会话列表
     */
    public List<GatewaySession> getSessionsByAgent(String agentId) {
        return sessions.values().stream().filter(s -> agentId.equals(s.getAgentId())).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 关闭指定 Agent 的所有会话。
     *
     * @param agentId Agent ID
     */
    public void closeAgentSessions(String agentId) {
        getSessionsByAgent(agentId).forEach(s -> closeSession(s.getSessionId()));
    }

    /**
     * 获取指定 WebSocket 通道关联的所有会话。
     *
     * @param wsChannel WebSocket 通道
     * @return 关联该通道的会话列表
     */
    public List<GatewaySession> getSessionsByChannel(Channel wsChannel) {
        return sessions.values().stream()
                .filter(s -> wsChannel != null && wsChannel.equals(s.getClientChannel()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 移除指定 WebSocket 通道关联的所有旧会话。
     * <p>控制端重连时调用，仅清除会话记录，不会关闭 WS 通道本身。</p>
     *
     * @param wsChannel WebSocket 通道
     */
    public void removeSessionsByChannel(Channel wsChannel) {
        if (wsChannel == null) { return; }
        sessions.values().removeIf(s -> wsChannel.equals(s.getClientChannel()));
    }

    /**
     * 获取当前活跃会话数。
     *
     * @return 状态为 ACTIVE 的会话数
     */
    public int activeCount() { return (int) sessions.values().stream().filter(s -> s.getStatus() == SessionStatus.ACTIVE).count(); }

    /**
     * 获取当前总会话数（含非活跃）。
     *
     * @return 所有已注册的会话数
     */
    public int totalCount() { return sessions.size(); }

    /**
     * 静默关闭通道。
     * <p>仅在通道不为空且处于活跃状态时关闭，不抛出异常。</p>
     *
     * @param ch Netty 通道
     */
    private void closeQuietly(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.close();
        }
    }
}
