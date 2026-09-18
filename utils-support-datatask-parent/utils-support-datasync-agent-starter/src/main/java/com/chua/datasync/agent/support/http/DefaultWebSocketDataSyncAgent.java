package com.chua.datasync.agent.support.http;

import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.impl.WebSocketSyncClient;

/**
* 默认 WebSocket 数据同步 Agent，作为 WebSocket 同步客户端 连接 数据同步 服务端。
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultWebSocketDataSyncAgent extends AbstractRemoteDataSyncAgent implements DataSyncAgent {

    /**
    * 构造 WebSocket Agent。
    *
    * @param agentId Agent 唯一标识
    * @param serverUrl 数据同步 服务端 WebSocket 地址，例如 ws://127.0.0.1:9090
    */
    public DefaultWebSocketDataSyncAgent(String agentId, String serverUrl) {
        super(agentId, new WebSocketSyncClient(agentId, serverUrl));
    }
}
