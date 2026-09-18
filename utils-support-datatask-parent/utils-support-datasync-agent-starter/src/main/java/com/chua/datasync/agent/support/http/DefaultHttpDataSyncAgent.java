package com.chua.datasync.agent.support.http;

import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.impl.HttpSyncClient;

/**
* 默认 HTTP 数据同步 Agent，作为 HTTP 同步客户端 连接 数据同步 服务端。
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultHttpDataSyncAgent extends AbstractRemoteDataSyncAgent implements DataSyncAgent {

    /**
    * 构造 HTTP Agent。
    *
    * @param agentId Agent 唯一标识
    * @param serverUrl 数据同步 服务端 地址，例如 http://127.0.0.1:9090
    */
    public DefaultHttpDataSyncAgent(String agentId, String serverUrl) {
        super(agentId, new HttpSyncClient(agentId, serverUrl));
    }
}
