package com.chua.common.support.network.sync.support;

import java.util.Map;

/**
 * 同步服务端持有的客户端最小视图。
 *
 * <p>不同传输（UDP 数据报、TCP 通道等）对「已接入客户端」的承载结构不同，
 * 但服务端统一按 clientId 索引并读取元数据。本接口只要求实现方给出元数据，
 * 传输细节（地址、通道、写队列等）留在各实现内部。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface SyncClientInfo {

    /**
     * 获取客户端元数据。
     *
     * @return 元数据映射（至少包含 clientId）
     */
    Map<String, Object> getMetadata();
}
