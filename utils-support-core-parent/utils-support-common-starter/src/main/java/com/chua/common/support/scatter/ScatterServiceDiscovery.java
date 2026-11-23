package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.network.discovery.ServiceDiscoveryListener;
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

    /**
     * 获取运行观测快照（成员表 + 发现/同步/心跳/帧计数器，自节点视角）。
     *
     * @return 观测快照
     */
    ScatterStats stats();

    /**
     * 优雅离网：向集群内其他节点广播 LEAVE 通知（尽力而为），
     * 使对端立即从成员表剔除本节点，无需等待心跳超限。应在 {@link #close()} / {@code stop()} 前调用。
     */
    void leave();

    /**
     * 订阅本分组（servicePath）的成员变更事件（ADD/UPDATE/REMOVE）。
     * 仅回调订阅之后发生的变更；如需当前全量成员请先读 {@link #getServiceAll(String)}。
     *
     * @param listener 监听器
     */
    default void subscribe(ServiceDiscoveryListener listener) {
        subscribe(getSetting().getServicePath(), listener);
    }

    /**
     * 取消订阅本分组（servicePath）的成员变更事件。
     *
     * @param listener 监听器
     */
    default void unsubscribe(ServiceDiscoveryListener listener) {
        unsubscribe(getSetting().getServicePath(), listener);
    }
}

