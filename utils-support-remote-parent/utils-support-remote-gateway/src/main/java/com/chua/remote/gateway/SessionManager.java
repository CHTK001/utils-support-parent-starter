package com.chua.remote.gateway;

import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器。
 *
 * <p>管理所有活跃会话、被控端注册信息和控制端连接信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SessionManager {

    /** 活跃会话 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 已注册被控端 */
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

    /** 已接入控制端 */
    private final Map<String, ControllerInfo> controllers = new ConcurrentHashMap<>();

    /**
     * 注册被控端。
     *
     * @param info 被控端信息
     */
    public void registerAgent(AgentInfo info) {
        agents.put(info.getId(), info);
    }

    /**
     * 注册控制端。
     *
     * @param info 控制端信息
     */
    public void registerController(ControllerInfo info) {
        controllers.put(info.getAccessToken(), info);
    }

    /**
     * 获取被控端信息。
     *
     * @param agentId 被控端 id
     * @return 被控端信息
     */
    public AgentInfo getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 获取控制端信息。
     *
     * @param controllerId 控制端 id（接入令牌）
     * @return 控制端信息
     */
    public ControllerInfo getController(String controllerId) {
        return controllers.get(controllerId);
    }

    /**
     * 添加会话。
     *
     * @param session 会话
     */
    public void addSession(Session session) {
        sessions.put(session.getSessionId(), session);
    }

    /**
     * 获取会话。
     *
     * @param sessionId 会话 id
     * @return 会话
     */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 关闭会话。
     *
     * @param sessionId 会话 id
     */
    public void closeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            session.setStatus(Session.SessionStatus.CLOSED);
        }
    }

    /**
     * 获取所有活跃会话。
     *
     * @return 会话映射
     */
    public Map<String, Session> getSessions() {
        return sessions;
    }
}
