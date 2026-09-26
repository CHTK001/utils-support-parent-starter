package com.chua.datalake.support.manager;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.subscriber.Subscriber;
import com.chua.datalake.support.subscriber.SubscriberRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

/**
 * 订阅管理器 —— {@link SubscriberRegistry} 面向应用的门面。
 *
 * <p>订阅器只有登记进注册表才会被 {@code RealTimeSink} 的投递链路命中。
 * 注册表是进程级唯一事实源（Sink 侧经 SPI 实例化通道，拿不到本管理器实例），
 * 本管理器只负责转发登记与查询，不另存一份订阅器列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SubscriberManager {

    /**
     * 订阅器注册表
     */
    private final SubscriberRegistry registry;

    /**
     * 构造订阅管理器，绑定进程级注册表。
     */
    public SubscriberManager() {
        this(SubscriberRegistry.getInstance());
    }

    /**
     * 构造订阅管理器。
     *
     * @param registry 订阅器注册表
     */
    public SubscriberManager(SubscriberRegistry registry) {
        this.registry = registry;
    }

    /**
     * 启动订阅管理器
     */
    public void start() {
        log.info("[datalake-server] SubscriberManager 启动，已登记 {} 个订阅器", registry.size());
    }

    /**
     * 停止订阅管理器，清空注册表。
     */
    public void stop() {
        registry.clear();
        log.info("[datalake-server] SubscriberManager 停止");
    }

    /**
     * 登记订阅器，使其参与实时投递。
     *
     * @param subscriber 订阅器
     * @return this
     */
    public SubscriberManager register(Subscriber subscriber) {
        registry.register(subscriber);
        return this;
    }

    /**
     * 注销订阅器。
     *
     * @param subscriberId 订阅器标识
     * @return 被注销的订阅器，未登记时为空
     */
    public Subscriber unregister(String subscriberId) {
        return registry.unregister(subscriberId);
    }

    /**
     * 判断订阅器是否已登记。
     *
     * @param subscriberId 订阅器标识
     * @return 已登记返回 true
     */
    public boolean contains(String subscriberId) {
        return registry.contains(subscriberId);
    }

    /**
     * 当前已登记的订阅器快照。
     *
     * @return 订阅器列表
     */
    public Collection<Subscriber> subscribers() {
        return registry.subscribers();
    }

    /**
     * 已登记订阅器数量。
     *
     * @return 数量
     */
    public int subscriberCount() {
        return registry.size();
    }

    /**
     * 主动推送一条信封给命中的订阅器。
     *
     * <p>正常链路由 Sink 触发；本方法供补偿重投与测试直接下发。</p>
     *
     * @param envelope 数据信封
     * @return 成功收下的订阅器数量
     */
    public int push(DataEnvelope envelope) {
        return registry.dispatch(envelope);
    }
}
