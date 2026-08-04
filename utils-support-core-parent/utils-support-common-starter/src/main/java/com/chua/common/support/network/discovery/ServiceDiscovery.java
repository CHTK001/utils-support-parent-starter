package com.chua.common.support.network.discovery;

import java.util.Set;
import org.jspecify.annotations.NullUnmarked;

/**
 * 服务发现接口定义。
 * 该接口用于管理服务的注册、注销、更新和查询操作。
 *
 * @author CH
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface ServiceDiscovery extends AutoCloseable {

    /**
     * 启动服务发现功能。
     *
     * @throws Exception 当启动过程中发生错误时抛出异常。
     */
    void start() throws Exception;

    /**
     * 注册一个服务实例。
     *
     * @param path       服务路径标识。
     * @param discovery  服务发现信息对象。
     * @return 当前服务发现实例，支持链式调用。
     */
    ServiceDiscovery registerService(String path, Discovery discovery);

    /**
     * 根据服务路径和发现信息注销一个服务实例。
     *
     * @param path      服务路径标识。
     * @param discovery 服务发现信息对象。
     * @return 当前服务发现实例，支持链式调用。
     */
    ServiceDiscovery unregisterService(String path, Discovery discovery);

    /**
     * 根据服务路径和服务节点ID注销一个服务实例。
     *
     * @param path     服务路径标识。
     * @param serverId 服务节点唯一标识。
     * @return 当前服务发现实例，支持链式调用。
     */
    ServiceDiscovery unregisterService(String path, String serverId);

    /**
     * 更新指定服务路径下的服务发现信息。
     *
     * @param path      服务路径标识。
     * @param discovery 新的服务发现信息对象。
     * @return 当前服务发现实例，支持链式调用。
     */
    ServiceDiscovery updateService(String path, Discovery discovery);

    /**
     * 获取指定路径、负载均衡策略和协议的服务发现信息。
     *
     * @param path      服务路径标识。
     * @param balance   负载均衡策略（如 "weight", "random" 等）。
     * @param protocol  服务协议类型（如 "http", "tcp" 等），可为 null。
     * @return 匹配的服务发现信息对象，若未找到则返回 null。
     */
    Discovery getService(String path, String balance, String protocol);

    /**
     * 获取指定路径和负载均衡策略的服务发现信息。
     * 此方法为默认实现，内部调用三参数版本，协议参数设为 null。
     *
     * @param path      服务路径标识。
     * @param balance   负载均衡策略。
     * @return 匹配的服务发现信息对象，若未找到则返回 null。
     */
    default Discovery getService(String path, String balance) {
        return getService(path, balance, null);
    }

    /**
     * 获取指定路径下所有可用的服务发现信息集合。
     *
     * @param path 服务路径标识。
     * @return 包含所有匹配服务发现信息的集合，可能为空但不为 null。
     */
    Set<Discovery> getServiceAll(String path);

    /**
     * 获取指定路径的服务发现信息，使用默认负载均衡策略 "weight" 且协议为 null。
     * 此方法为默认实现，内部调用三参数版本。
     *
     * @param path 服务路径标识。
     * @return 匹配的服务发现信息对象，若未找到则返回 null。
     */
    default Discovery getService(String path) {
        return getService(path, "weight", null);
    }

    /**
     * 判断当前服务发现实现是否支持订阅机制。
     *
     * @return 如果支持订阅返回 true，否则返回 false。
     */
    default boolean isSupportSubscribe() {
        return false;
    }

    /**
     * 订阅指定服务名称的变化通知。
     * 默认实现抛出不支持的异常，子类需根据具体实现重写此方法。
     *
     * @param serviceName 服务名称。
     * @param listener    服务发现监听器。
     * @throws UnsupportedOperationException 如果当前实现不支持订阅功能。
     */
    default void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        throw new UnsupportedOperationException("Subscription is not supported by this implementation.");
    }

    /**
     * 取消对指定服务名称的订阅。
     * 默认实现为空操作，子类可根据需要重写以执行清理逻辑。
     *
     * @param serviceName 服务名称。
     * @param listener    服务发现监听器。
     */
    default void unsubscribe(String serviceName, ServiceDiscoveryListener listener) {
    }
}
