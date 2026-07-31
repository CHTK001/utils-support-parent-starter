package com.chua.remote.support.agent.rustdesk;

import java.util.Map;

/**
 * RustDesk Agent 与宿主环境之间的最小接口。
 *
 * <p>RustDeskAgentService 仅通过此接口与外部交互，
 * 不依赖 BaseRemoteAgent、Netty 或其他重框架。</p>
 *
 * @author CH
*/
public interface RustDeskAgentContext {

    /**
     * 获取当前 Agent 的唯一标识。
     */
    String getAgentId();

    /**
     * 获取 Agent capabilities（可读写）。
     */
    Map<String, String> getCapabilities();

    /**
     * 覆盖 Agent capabilities。
     */
    void setCapabilities(Map<String, String> caps);

    /**
     * 向 Gateway 发送 JSON 消息。
     */
    void sendToGateway(String json);
}
