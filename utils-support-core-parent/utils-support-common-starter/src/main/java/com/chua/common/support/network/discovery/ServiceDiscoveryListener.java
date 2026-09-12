package com.chua.common.support.network.discovery;


/**
* 服务发现监听器接口。
* 用于监听服务注册、注销或状态变更等事件。
* @author CH
* @since 4.0.0.42
 */
public interface ServiceDiscoveryListener {

    /**
    * 当发生服务发现事件时调用此方法。
    *
    * @param serverName 服务名称
    * @param discovery  发现的服务信息对象
    * @param event      触发的事件类型（如注册、注销、更新等）
     */
    void listen(String serverName, Discovery discovery, Event event);
}
