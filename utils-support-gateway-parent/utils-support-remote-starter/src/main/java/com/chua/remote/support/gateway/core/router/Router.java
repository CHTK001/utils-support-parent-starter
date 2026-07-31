package com.chua.remote.support.gateway.core.router;

import com.chua.remote.support.gateway.config.ConnectionMode;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.channel.Channel;

/**
 * 路由器接口 — 根据协议、连接模式和目标 ID 选择对应的 Agent
 * <p>核心路由策略：将客户端的连接请求映射到已注册的 Agent 目标节点。
 *
 * @author CH
 */
public interface Router {

    /**
     * 路由连接请求
     * @param protocol       请求的协议类型（SSH / DESKTOP 等）
     * @param mode           连接模式（长连接/短连接）
     * @param targetId       目标节点 ID
     * @param clientChannel  客户端 Netty Channel
     * @return 路由结果，包含目标 Agent 地址和会话 ID
     */
    RouteResult route(Protocol protocol, ConnectionMode mode, String targetId, Channel clientChannel);

    /**
     * 路由结果
     * <p>包含目标 Agent 的连接信息和会话标识。
     */
    @lombok.Data
    class RouteResult {
        /** Agent 主机地址 */
        private final String agentHost;
        /** Agent 端口 */
        private final int agentPort;
        /** 会话 ID（路由时分配） */
        /**
         * 会话 ID
         */
        private String sessionId;
        /** 是否需要编解码（桌面远程等场景需要） */
        private boolean codecRequired;
    }
}
