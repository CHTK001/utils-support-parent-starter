package com.chua.remote.support.agent.desktop;

import com.chua.remote.support.agent.BaseRemoteAgent;

import java.util.Map;

/**
 * 桌面代理服务 SPI 抽象。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DesktopAgentService {

    /**
     * 处理连接请求。
     *
     * @param sessionId 会话标识
     * @param target 目标信息
     * @param auth 认证信息
     */
    void handleConnect(String sessionId, Map<String, Object> target, Map<String, Object> auth);

    /**
     * 处理断开连接。
     *
     * @param sessionId 会话标识
     */
    void handleDisconnect(String sessionId);

    /**
     * 断开所有连接。
     */
    void handleDisconnectAll();

    /**
     * 处理控制指令。
     *
     * @param sessionId 会话标识
     * @param type 指令类型
     * @param payload 指令负载
     */
    void handleControl(String sessionId, String type, Map<String, Object> payload);

    /**
     * 更新目标尺寸。
     *
     * @param sessionId 会话标识
     * @param width 目标宽度
     * @param height 目标高度
     */
    void updateTargetSize(String sessionId, int width, int height);

    /**
     * 处理输入事件。
     *
     * @param sessionId 会话标识
     * @param type 输入类型
     * @param payload 输入负载
     */
    void handleInput(String sessionId, String type, Map<String, Object> payload);

    /**
     * 获取底层代理实例。
     *
     * @return BaseRemoteAgent 实例
     */
    BaseRemoteAgent getAgent();
}
