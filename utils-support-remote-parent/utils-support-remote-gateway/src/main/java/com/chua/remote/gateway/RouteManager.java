package com.chua.remote.gateway;

import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.Session;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信令路由管理器。
 *
 * <p>根据消息类型和会话标识，将帧路由到正确的对端。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RouteManager {

    /** 会话映射 */
    private final SessionManager sessionManager;

    /** 会话对应的连接映射 */
    private final Map<String, String> sessionConnections = new ConcurrentHashMap<>();

    public RouteManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 路由信令帧到目标客户端。
     *
     * @param frame   帧
     * @param sessionId 会话标识
     */
    public void routeSignal(Frame frame, String sessionId) {
        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            log.warn("会话不存在: sessionId={}", sessionId);
            return;
        }
        // 根据帧类型和目标路由
        if (frame.getType() == MessageType.SIGNAL) {
            routeToAgent(session, frame);
        }
    }

    /**
     * 路由数据帧（屏幕流）到控制端。
     *
     * @param session 会话
     * @param frame   数据帧
     */
    public void routeData(Session session, Frame frame) {
        routeToController(session, frame);
    }

    /**
     * 路由键鼠事件到被控端。
     *
     * @param session 会话
     * @param frame   控制帧
     */
    public void routeInputEvent(Session session, Frame frame) {
        routeToAgent(session, frame);
    }

    /**
     * 路由到被控端。
     */
    private void routeToAgent(Session session, Frame frame) {
        log.debug("路由到被控端: agentId={}, sessionId={}", session.getAgentId(), session.getSessionId());
    }

    /**
     * 路由到控制端。
     */
    private void routeToController(Session session, Frame frame) {
        log.debug("路由到控制端: controllerId={}, sessionId={}", session.getControllerSessionId(), session.getSessionId());
    }
}
