package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.scatter.node.ScatterNodeHandler;

/**
 * scatter 服务发现接口（实现类同时承担帧处理，见 {@link ScatterNodeHandler}）。
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface ScatterServiceDiscovery extends ServiceDiscovery, ScatterNodeHandler {

    /**
    * 设置远程客户端（未启动前）。
    *
    * @param remoteClient 远程客户端
    * @return 当前实例
    */
    ScatterServiceDiscovery remoteClient(ScatterRemoteClient remoteClient);

    /**
    * 获取分组。
    *
    * @return 分组
    */
    String getGroupId();

    /**
    * 获取配置。
    *
    * @return 配置
    */
    ScatterSetting getSetting();
}
