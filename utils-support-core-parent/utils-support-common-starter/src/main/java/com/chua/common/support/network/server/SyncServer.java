package com.chua.common.support.network.server;

import java.util.List;
import java.util.Map;

/**
* 同步服务端接口，基于长连接双向通道提供主题发布、会话管理和消息推送能力。
* <p>
* 服务端维护客户端连接池，支持按主题广播、按会话推送以及客户端上下线通知。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface SyncServer extends Server {

    /**
    * 向指定主题发布消息（广播给所有订阅该主题的客户端）。
    *
    * @param topic   主题
    * @param message 消息内容
     */
    void publish(String topic, Object message);

    /**
    * 向指定客户端发送消息。
    *
    * @param clientId 客户端标识
    * @param topic    主题
    * @param message  消息内容
     */
    void send(String clientId, String topic, Object message);

    /**
    * 获取当前所有已连接的客户端标识列表。
    *
    * @return 客户端标识列表
     */
    List<String> getConnectedClients();

    /**
    * 获取指定客户端的元数据。
    *
    * @param clientId 客户端标识
    * @return 元数据映射，不存在时返回空 Map
     */
    Map<String, Object> getClientMetadata(String clientId);

    /**
    * 添加同步事件监听器。
    *
    * @param listener 监听器
     */
    void addListener(SyncServerListener listener);

    /**
    * 移除同步事件监听器。
    *
    * @param listener 监听器
     */
    void removeListener(SyncServerListener listener);
}
