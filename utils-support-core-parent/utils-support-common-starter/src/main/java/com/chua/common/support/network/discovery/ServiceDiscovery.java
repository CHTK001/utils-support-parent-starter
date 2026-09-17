package com.chua.common.support.network.discovery;

import java.util.Set;

/**
* 服务发现接口。
* 该接口定义了服务注册、注销和查询等功能。
*
* @author CH
* @since 4.0.0.42
 */
public interface ServiceDiscovery extends AutoCloseable {

    /**
    * 启动服务发现功能。
    *
    * @throws Exception 当服务发现初始化或启动失败时抛出异常
    */
    void start() throws Exception;

    /**
    * 注册一个服务实例
    *
    * @param path      服务路径标识符
    * @param discovery 服务信息对象
    * @return 当前服务发现实例，支持链式调用
    */
    ServiceDiscovery registerService(String path, Discovery discovery);

    /**
    * 根据服务路径和服务信息注销一个服务实例
    *
    * @param path      服务路径标识符
    * @param discovery 服务信息对象
    * @return 当前服务发现实例，支持链式调用
    */
    ServiceDiscovery unregisterService(String path, Discovery discovery);

    /**
    * 根据服务路径和服务节点ID注销一个服务实例
    *
    * @param path     服务路径标识符
    * @param serverId 服务节点的唯一标识
    * @return 当前服务发现实例，支持链式调用
    */
    ServiceDiscovery unregisterService(String path, String serverId);

    /**
    * 更新指定路径下的服务信息
    *
    * @param path      服务路径标识符
    * @param discovery 新的服务信息对象
    * @return 当前服务发现实例，支持链式调用
    */
    ServiceDiscovery updateService(String path, Discovery discovery);

    /**
    * 获取指定路径下匹配负载均衡策略和服务信息的服务
    *
    * @param path      服务路径标识符
    * @param balance   负载均衡策略（如 "weight", "random" 等）
    * @param protocol  网络协议类型（如 "http", "tcp" 等），可为 null
    * @return 匹配的服务信息对象，未找到则返回 null
    */
    default Discovery getService(String path, String balance, String protocol) {
        return getService(path, null, balance, protocol);
    }

    /**
    * 获取指定路径、业务分组、负载均衡策略和协议的服务信息
    * <p>scatterId 用于业务分组隔离：相同 scatterId 的节点互相同步负载均衡，
    * 不同业务节点互不污染；为 null 时无过滤（等同于任意）。</p>
    *
    * @param path      服务路径标识符
    * @param scatterId 业务分组标识，可为 null（不过滤）
    * @param balance   负载均衡策略（如 "weight", "random" 等）
    * @param protocol  网络协议类型（如 "http", "tcp" 等），可为 null
    * @return 匹配的服务信息对象，未找到则返回 null
    */
    Discovery getService(String path, String scatterId, String balance, String protocol);

    /**
    * 获取指定路径和负载均衡策略的服务信息
    * 此方法为默认实现，内部会使用默认协议（为 null）
    *
    * @param path    服务路径标识符
    * @param balance 负载均衡策略
    * @return 匹配的服务信息对象，未找到则返回 null
    */
    default Discovery getService(String path, String balance) {
        return getService(path, balance, null);
    }

    /**
    * 获取指定路径下所有可用的服务信息集合。
    *
    * @param path 服务路径标识符
    * @return 满足匹配条件的服务信息对象集合，若未找到则返回 null
    */
    Set<Discovery> getServiceAll(String path);

    /**
    * 获取指定路径的服务信息，使用默认负载均衡策略 "weight"，协议为 null
    * 此方法为默认实现，内部使用默认的负载均衡算法
    *
    * @param path 服务路径标识符
    * @return 匹配的服务信息对象，未找到则返回 null
    */
    default Discovery getService(String path) {
        return getService(path, "weight", null);
    }

    /**
    * 判断当前服务发现实例是否支持订阅模式。
    *
    * @return 支持订阅的模式返回 true，否则返回 false
    */
    default boolean isSupportSubscribe() {
        return false;
    }

    /**
    * 订阅指定服务的变更通知
    * 默认实现在不支持时抛出异常，具体实现类可根据需要重写此方法
    *
    * @param serviceName 服务名称
    * @param listener    服务监听器回调对象
    * @throws UnsupportedOperationException 如果当前实现不支持该功能
    */
    default void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        throw new UnsupportedOperationException("Subscription is not supported by this implementation.");
    }

    /**
    * 取消订阅指定服务的变更通知
    * 默认实现为空操作，具体实现类可根据需要重写此方法
    *
    * @param serviceName 服务名称
    * @param listener    服务监听器回调对象
    */
    default void unsubscribe(String serviceName, ServiceDiscoveryListener listener) {
    }

    /**
    * 清除本地服务表缓存，移除所有已注册的服务条目。
    * <p>主要用于测试隔离，确保不同测试用例之间的服务表互不干扰。</p>
    */
    default void clearCache() {
    }
}
