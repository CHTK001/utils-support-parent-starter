package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;

/**
 * 本地数据同步 Agent 标记接口，直接注册到 DataSyncServer，不走网络。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LocalDataSyncAgent extends DataSyncAgent {
}
